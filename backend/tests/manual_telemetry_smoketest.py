"""Manual smoke test for the Phase 4 kex -> telemetry WS pipeline, using synthetic frames/
IMU (not meant to show real correlation -- just proves the wire format, AEAD, and JSON
round trip all work end to end before testing against a real phone)."""

import asyncio
import base64
import json
import sys

import cv2
import httpx
import numpy as np
import websockets
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import x25519

sys.path.insert(0, ".")
from app.core.crypto import derive_session_keys  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

BASE = "http://127.0.0.1:8000"
WS_BASE = "ws://127.0.0.1:8000"


def make_jpeg(seed: int) -> bytes:
    rng = np.random.default_rng(seed)
    img = rng.integers(0, 255, (120, 160), dtype=np.uint8)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 40])
    assert ok
    return buf.tobytes()


async def main(session_id: str):
    client_priv = x25519.X25519PrivateKey.generate()
    epk_c = client_priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

    async with httpx.AsyncClient() as http:
        resp = await http.post(f"{BASE}/api/v1/session/kex", json={
            "session_id": session_id,
            "epk_c_b64": base64.b64encode(epk_c).decode(),
        })
        resp.raise_for_status()
        epk_s = base64.b64decode(resp.json()["epk_s_b64"])
        print("kex OK, epk_s:", epk_s.hex()[:16], "...")

    shared = client_priv.exchange(x25519.X25519PublicKey.from_public_bytes(epk_s))
    k_tel, _ = derive_session_keys(shared, session_id)
    aesgcm = AESGCM(k_tel)
    print("derived k_tel:", k_tel.hex()[:16], "...")

    uri = f"{WS_BASE}/ws/telemetry/{session_id}"
    async with websockets.connect(uri) as ws:
        for seq in range(1, 21):
            t_base = 1_000_000_000 * seq
            frames = [
                {"t_ns": t_base + i * 66_000_000, "jpeg_b64": base64.b64encode(make_jpeg(seq * 10 + i)).decode()}
                for i in range(3)
            ]
            gyro = [[t_base + i * 5_000_000, 0.5, 0.1, 0.0] for i in range(100)]
            plaintext = json.dumps({"seq": seq, "frames": frames, "imu": {"gyro": gyro}}).encode()

            nonce12 = seq.to_bytes(8, "big") + b"\x00\x00\x00\x00"
            aad = b"SC-TEL-v1" + session_id.encode() + seq.to_bytes(8, "big")
            ct = aesgcm.encrypt(nonce12, plaintext, aad)

            await ws.send(nonce12 + ct)
            reply = await ws.recv()
            print(f"seq={seq} -> {reply}")


if __name__ == "__main__":
    with open("/tmp/kex_test_session.txt") as f:
        session_id = f.read().strip()
    asyncio.run(main(session_id))
