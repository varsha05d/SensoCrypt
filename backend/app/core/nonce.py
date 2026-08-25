"""In-memory single-use nonces (plan.md §4.2 att_challenge, §4.3 auth nonce, §9 R3).

Both the enrollment attestation challenge and the per-call auth nonce are
server-generated, CSPRNG, single-use, and TTL-bound. Consuming a nonce is an
atomic pop from a process-local dict so a signature can never be replayed
against the same nonce twice, even under concurrent requests.

In-memory rather than Redis-backed: the free-tier deployment target runs a
single process instance, so there's no cross-instance state to share, and
losing in-flight nonces on a cold-start restart is harmless since they're
short-lived (seconds to low-minutes TTL) and would already be irrelevant by
the time a restart happens (Render's free tier only restarts after ~15min of
total inactivity, at which point no session was actually in flight).
"""

import asyncio
import secrets
import time

_store: dict[str, tuple[str, float]] = {}  # key -> (value, expires_at_monotonic)
_lock = asyncio.Lock()


def new_nonce(nbytes: int = 32) -> str:
    return secrets.token_hex(nbytes)


def _sweep_expired(now: float) -> None:
    expired = [k for k, (_, exp) in _store.items() if exp <= now]
    for k in expired:
        del _store[k]


async def store(key: str, value: str, ttl_s: int) -> None:
    async with _lock:
        now = time.monotonic()
        _sweep_expired(now)
        if key not in _store:
            _store[key] = (value, now + ttl_s)


async def consume(key: str) -> str | None:
    """Atomically fetch-and-delete. Returns None if the key never existed,
    already expired, or was already consumed -- all three read as "invalid"."""
    async with _lock:
        entry = _store.pop(key, None)
        if entry is None:
            return None
        value, expires_at = entry
        if expires_at <= time.monotonic():
            return None
        return value
