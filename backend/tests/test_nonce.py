"""test_nonce_single_use from plan.md §17.4 -- exercises the in-memory nonce store."""

import pytest

from app.core import nonce


@pytest.mark.asyncio
async def test_nonce_single_use():
    key = f"test:{nonce.new_nonce(8)}"
    value = nonce.new_nonce(32)

    await nonce.store(key, value, ttl_s=10)

    first = await nonce.consume(key)
    assert first == value

    second = await nonce.consume(key)
    assert second is None  # already consumed -> replay of the signature is now impossible
