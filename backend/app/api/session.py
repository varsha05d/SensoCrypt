"""Session key agreement (plan.md §4.4, §17.1) -- derives k_tel/k_chal for the telemetry
WS. See app/core/crypto.py::derive_session_keys for the scoped-down-vs-full-SIGMA-I tradeoff.
"""

import base64
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import crypto, nonce
from app.db.models import Session as SessionModel
from app.db.session import get_db
from app.schemas import KexRequest, KexResponse

router = APIRouter(prefix="/api/v1/session", tags=["session"])


@router.post("/kex", response_model=KexResponse)
async def session_kex(body: KexRequest, db: AsyncSession = Depends(get_db)) -> KexResponse:
    try:
        session_uuid = uuid.UUID(body.session_id)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="invalid session") from exc

    session_row = await db.get(SessionModel, session_uuid)
    if session_row is None or session_row.state != "AUTHED":
        raise HTTPException(status_code=401, detail="session not authenticated")

    epk_c = base64.b64decode(body.epk_c_b64)
    server_priv, epk_s = crypto.generate_x25519_keypair()
    shared = crypto.x25519_agree(server_priv, epk_c)
    k_tel, _k_chal = crypto.derive_session_keys(shared, body.session_id)

    # Single-use: the telemetry WS consumes this once at connect time and holds it for the
    # life of that connection. A reconnect needs a fresh /session/kex call.
    await nonce.store(f"ktel:{session_uuid}", k_tel.hex(), ttl_s=3600)

    session_row.state = "KEYED"
    await db.commit()

    return KexResponse(epk_s_b64=base64.b64encode(epk_s).decode())
