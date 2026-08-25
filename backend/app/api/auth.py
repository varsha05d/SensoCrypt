"""Enrollment and session authentication (plan.md §4.2, §4.3, §17.1).

Two flows:
  enroll/init -> enroll/finish   -- once per device, verifies Key Attestation
  challenge -> verify            -- once per call, proves possession of the
                                     enrolled hardware key over a fresh nonce
"""

import base64
import json
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import crypto, nonce
from app.core.attestation import AttestationError, verify_chain
from app.core.config import settings
from app.db.models import Device
from app.db.models import Session as SessionModel
from app.db.session import get_db
from app.schemas import (
    ChallengeRequest,
    ChallengeResponse,
    EnrollFinishRequest,
    EnrollFinishResponse,
    EnrollInitRequest,
    EnrollInitResponse,
    VerifyRequest,
    VerifyResponse,
)

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


@router.post("/enroll/init", response_model=EnrollInitResponse)
async def enroll_init(body: EnrollInitRequest) -> EnrollInitResponse:
    enroll_id = uuid.uuid4().hex
    att_challenge = bytes.fromhex(nonce.new_nonce(32))

    record = json.dumps(
        {
            "att_challenge_hex": att_challenge.hex(),
            "device_model": body.device_model,
            "os_version": body.os_version,
        }
    )
    await nonce.store(f"enroll:{enroll_id}", record, ttl_s=settings.attestation_challenge_ttl_s)

    return EnrollInitResponse(
        enroll_id=enroll_id,
        att_challenge_b64=base64.b64encode(att_challenge).decode(),
    )


@router.post("/enroll/finish", response_model=EnrollFinishResponse)
async def enroll_finish(body: EnrollFinishRequest, db: AsyncSession = Depends(get_db)) -> EnrollFinishResponse:
    raw = await nonce.consume(f"enroll:{body.enroll_id}")
    if raw is None:
        raise HTTPException(status_code=401, detail="enrollment expired or already used")
    record = json.loads(raw)
    expected_challenge = bytes.fromhex(record["att_challenge_hex"])

    try:
        chain_der = [base64.b64decode(c) for c in body.cert_chain_b64]
        info = verify_chain(chain_der, expected_challenge)
    except AttestationError as exc:
        raise HTTPException(status_code=401, detail=f"attestation rejected: {exc}") from exc

    device = Device(
        device_id=uuid.uuid4(),
        public_key_der=info.public_key_der,
        security_level=info.security_level,
        package_name=info.package_name,
        signing_digest=info.signature_digests[0] if info.signature_digests else b"",
        verified_boot=(info.verified_boot_state == "verified" and info.device_locked),
        os_version=record.get("os_version"),
        model=record.get("device_model"),
    )
    db.add(device)
    await db.commit()

    return EnrollFinishResponse(device_id=str(device.device_id))


@router.post("/challenge", response_model=ChallengeResponse)
async def auth_challenge(body: ChallengeRequest, db: AsyncSession = Depends(get_db)) -> ChallengeResponse:
    try:
        device_uuid = uuid.UUID(body.device_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="unknown device") from exc

    device = await db.get(Device, device_uuid)
    if device is None or device.revoked_at is not None:
        raise HTTPException(status_code=404, detail="unknown or revoked device")

    session_id = uuid.uuid4()
    challenge_nonce = bytes.fromhex(nonce.new_nonce(32))
    await nonce.store(f"authnonce:{session_id}", challenge_nonce.hex(), ttl_s=settings.auth_nonce_ttl_s)

    db.add(SessionModel(session_id=session_id, device_id=device_uuid, state="INIT"))
    await db.commit()

    return ChallengeResponse(
        session_id=str(session_id),
        nonce_b64=base64.b64encode(challenge_nonce).decode(),
        server_ts=int(time.time() * 1000),
    )


@router.post("/verify", response_model=VerifyResponse)
async def auth_verify(body: VerifyRequest, db: AsyncSession = Depends(get_db)) -> VerifyResponse:
    try:
        session_uuid = uuid.UUID(body.session_id)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="invalid session") from exc

    raw_nonce_hex = await nonce.consume(f"authnonce:{session_uuid}")
    if raw_nonce_hex is None:
        raise HTTPException(status_code=401, detail="challenge expired or already used")

    session_row = await db.get(SessionModel, session_uuid)
    if session_row is None:
        raise HTTPException(status_code=401, detail="unknown session")
    device = await db.get(Device, session_row.device_id)
    if device is None or device.revoked_at is not None:
        raise HTTPException(status_code=401, detail="unknown or revoked device")

    channel_binding = base64.b64decode(body.channel_binding_b64) if body.channel_binding_b64 else b""
    message = crypto.build_auth_message(
        nonce=bytes.fromhex(raw_nonce_hex),
        session_id=body.session_id,
        pubkey_der=device.public_key_der,
        channel_binding=channel_binding,
    )
    signature = base64.b64decode(body.sig_der_b64)

    if not crypto.verify_ecdsa_p256(device.public_key_der, message, signature):
        raise HTTPException(status_code=401, detail="signature verification failed")

    session_row.state = "AUTHED"
    await db.commit()

    token = crypto.issue_session_token(device_id=str(device.device_id), session_id=body.session_id)
    return VerifyResponse(token=token, expires_in=settings.session_token_ttl_s)
