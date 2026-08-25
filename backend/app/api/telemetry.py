"""Encrypted telemetry ingest (plan.md §4.5, §10.3): AES-256-GCM chunks bound to the
session via AAD, monotonic seq gives replay/reorder rejection for free. Feeds the
per-connection LivenessEngine and streams verdicts back over the same socket.
"""

import asyncio
import base64
import json
import logging

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.core import nonce as nonce_store
from app.liveness.worker import LivenessEngine

logger = logging.getLogger(__name__)
router = APIRouter()


@router.websocket("/ws/telemetry/{session_id}")
async def telemetry_ws(ws: WebSocket, session_id: str):
    await ws.accept()

    k_tel_hex = await nonce_store.consume(f"ktel:{session_id}")
    if k_tel_hex is None:
        await ws.close(code=4401)
        return
    aesgcm = AESGCM(bytes.fromhex(k_tel_hex))

    quick = ws.query_params.get("quick") == "1"
    engine = LivenessEngine(quick=quick)
    last_seq = -1

    try:
        while True:
            raw = await ws.receive_bytes()
            nonce12, ct = raw[:12], raw[12:]
            seq = int.from_bytes(nonce12[:8], "big")

            if seq <= last_seq:
                continue  # replay/reorder -- drop silently

            aad = b"SC-TEL-v1" + session_id.encode() + seq.to_bytes(8, "big")
            try:
                pt = aesgcm.decrypt(nonce12, ct, aad)
            except InvalidTag:
                await ws.close(code=4403)  # forged chunk = terminate, no retries
                return
            last_seq = seq

            chunk = json.loads(pt)
            for f in chunk.get("frames", []):
                f["jpeg_bytes"] = base64.b64decode(f.pop("jpeg_b64"))

            try:
                # engine.ingest() does real CPU work (OpenCV optical flow, scipy bandpass
                # filtering, brute-force axis-mapping search) synchronously -- run it off
                # the event loop thread. Without this, one session's per-chunk processing
                # blocks every OTHER concurrent session's WebSocket entirely (a call needs
                # exactly two simultaneous sessions by design), which surfaced as one phone
                # stalling for 15+ seconds and receiving a wildly stale illumination
                # challenge while the other phone's session was being processed. Safe to
                # offload: each connection owns its own LivenessEngine instance, and this
                # connection's own loop awaits one ingest() at a time, so there's no
                # concurrent access to the same engine's state from multiple threads.
                verdict = await asyncio.to_thread(engine.ingest, chunk)
            except Exception:  # noqa: BLE001
                # A scoring bug should degrade this one window, not silently drop the
                # connection -- that reads to the client as an unexplained "socket error".
                logger.exception("liveness engine failed on seq=%s", seq)
                verdict = {"verdict": "engine_error"}
            verdict["seq"] = seq
            if verdict.get("new_challenge") is not None:
                logger.info("seq=%s ISSUING challenge %s", seq, verdict["new_challenge"]["id"])
            illum = verdict.get("S_illum") or {}
            logger.info(
                "seq=%s verdict=%s r=%s S_A=%s energy=%s illum_verdict=%s S_illum=%s illum_n=%s "
                "p_trust=%s state=%s axis_locked=%s",
                seq,
                verdict.get("verdict"),
                verdict.get("r"),
                verdict.get("S_A"),
                verdict.get("energy"),
                illum.get("verdict"),
                illum.get("S_illum"),
                illum.get("n"),
                verdict.get("p_trust"),
                verdict.get("trust_state"),
                verdict.get("axis_map_locked"),
            )
            await ws.send_json(verdict)
    except WebSocketDisconnect:
        pass
