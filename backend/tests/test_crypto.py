"""plan.md §17.4 crypto/ acceptance tests -- the ones testable without a real
Android device's attestation bytes on hand. Attestation-chain parsing (rejects
unlocked bootloader / wrong package / wrong signing digest / stale challenge /
software-only key) needs a real or fixture-captured cert chain and is exercised
manually against a physical device during Phase 2's build-out.
"""

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.hashes import SHA256

from app.core import crypto


def _gen_keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    pub_der = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return priv, pub_der


def test_signature_verifies_with_correct_key():
    priv, pub_der = _gen_keypair()
    message = crypto.build_auth_message(b"n" * 32, "session-1", pub_der)
    sig = priv.sign(message, ec.ECDSA(SHA256()))
    assert crypto.verify_ecdsa_p256(pub_der, message, sig)


def test_signature_bound_to_channel():
    """valid sig, wrong EM -> verification fails (plan.md §17.4)."""
    priv, pub_der = _gen_keypair()
    message = crypto.build_auth_message(b"n" * 32, "session-1", pub_der, channel_binding=b"real-channel")
    sig = priv.sign(message, ec.ECDSA(SHA256()))

    tampered_message = crypto.build_auth_message(b"n" * 32, "session-1", pub_der, channel_binding=b"attacker-channel")
    assert not crypto.verify_ecdsa_p256(pub_der, tampered_message, sig)


def test_signature_rejected_with_wrong_key():
    _, pub_der_signer = _gen_keypair()
    priv_attacker, _ = _gen_keypair()

    message = crypto.build_auth_message(b"n" * 32, "session-1", pub_der_signer)
    forged_sig = priv_attacker.sign(message, ec.ECDSA(SHA256()))
    assert not crypto.verify_ecdsa_p256(pub_der_signer, message, forged_sig)


def test_session_token_roundtrip(monkeypatch):
    monkeypatch.setattr(crypto.settings, "paseto_local_key_hex", "00" * 32)
    token = crypto.issue_session_token(device_id="dev-1", session_id="sess-1", ttl_s=60)
    payload = crypto.verify_session_token(token)
    assert payload["device_id"] == "dev-1"
    assert payload["session_id"] == "sess-1"


def test_expired_session_token_rejected(monkeypatch):
    monkeypatch.setattr(crypto.settings, "paseto_local_key_hex", "00" * 32)
    token = crypto.issue_session_token(device_id="dev-1", session_id="sess-1", ttl_s=-1)
    with pytest.raises(ValueError):
        crypto.verify_session_token(token)
