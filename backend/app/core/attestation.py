"""Android Key Attestation chain verification.

This is the load-bearing check the whole project's security argument rests on
(plan.md §0.3): verified_boot_state + deviceLocked reject rooted devices, and
attestationApplicationId (package name + signing cert digest) rejects a
repackaged/third-party app on an otherwise-genuine device. Skipping either
collapses the "hardware-bound" claim to decoration.

ASN.1 schema follows Android's documented KeyDescription structure:
https://source.android.com/docs/security/features/keystore/attestation#schema
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import httpx
from asn1crypto import core
from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.core.config import settings as app_settings
from app.core.google_roots import GOOGLE_HARDWARE_ATTESTATION_ROOTS_PEM

logger = logging.getLogger(__name__)

KEY_DESCRIPTION_OID = x509.ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
REVOCATION_STATUS_URL = "https://android.googleapis.com/attestation/status"

KM_PURPOSE_SIGN = 2
KM_ORIGIN_GENERATED = 0


class AttestationError(Exception):
    pass


# --- ASN.1 structures (asn1crypto) -----------------------------------------


class VerifiedBootState(core.Enumerated):
    _map = {0: "verified", 1: "self_signed", 2: "unverified", 3: "failed"}


class SecurityLevel(core.Enumerated):
    _map = {0: "software", 1: "trusted_environment", 2: "strong_box"}


class RootOfTrust(core.Sequence):
    _fields = [
        ("verified_boot_key", core.OctetString),
        ("device_locked", core.Boolean),
        ("verified_boot_state", VerifiedBootState),
        ("verified_boot_hash", core.OctetString),
    ]


class IntegerSet(core.SetOf):
    _child_spec = core.Integer


class AuthorizationList(core.Sequence):
    _fields = [
        ("purpose", IntegerSet, {"tag_type": "explicit", "tag": 1, "optional": True}),
        ("algorithm", core.Integer, {"tag_type": "explicit", "tag": 2, "optional": True}),
        ("key_size", core.Integer, {"tag_type": "explicit", "tag": 3, "optional": True}),
        ("digest", IntegerSet, {"tag_type": "explicit", "tag": 5, "optional": True}),
        ("padding", IntegerSet, {"tag_type": "explicit", "tag": 6, "optional": True}),
        ("ec_curve", core.Integer, {"tag_type": "explicit", "tag": 10, "optional": True}),
        ("rsa_public_exponent", core.Integer, {"tag_type": "explicit", "tag": 200, "optional": True}),
        ("mgf_digest", IntegerSet, {"tag_type": "explicit", "tag": 203, "optional": True}),
        ("rollback_resistance", core.Null, {"tag_type": "explicit", "tag": 303, "optional": True}),
        ("early_boot_only", core.Null, {"tag_type": "explicit", "tag": 305, "optional": True}),
        ("active_date_time", core.Integer, {"tag_type": "explicit", "tag": 400, "optional": True}),
        ("origination_expire_date_time", core.Integer, {"tag_type": "explicit", "tag": 401, "optional": True}),
        ("usage_expire_date_time", core.Integer, {"tag_type": "explicit", "tag": 402, "optional": True}),
        ("usage_count_limit", core.Integer, {"tag_type": "explicit", "tag": 405, "optional": True}),
        ("no_auth_required", core.Null, {"tag_type": "explicit", "tag": 503, "optional": True}),
        ("user_auth_type", core.Integer, {"tag_type": "explicit", "tag": 504, "optional": True}),
        ("auth_timeout", core.Integer, {"tag_type": "explicit", "tag": 505, "optional": True}),
        ("allow_while_on_body", core.Null, {"tag_type": "explicit", "tag": 506, "optional": True}),
        ("trusted_user_presence_required", core.Null, {"tag_type": "explicit", "tag": 507, "optional": True}),
        ("trusted_confirmation_required", core.Null, {"tag_type": "explicit", "tag": 508, "optional": True}),
        ("unlocked_device_required", core.Null, {"tag_type": "explicit", "tag": 509, "optional": True}),
        ("creation_date_time", core.Integer, {"tag_type": "explicit", "tag": 701, "optional": True}),
        ("origin", core.Integer, {"tag_type": "explicit", "tag": 702, "optional": True}),
        ("root_of_trust", RootOfTrust, {"tag_type": "explicit", "tag": 704, "optional": True}),
        ("os_version", core.Integer, {"tag_type": "explicit", "tag": 705, "optional": True}),
        ("os_patch_level", core.Integer, {"tag_type": "explicit", "tag": 706, "optional": True}),
        ("attestation_application_id", core.OctetString, {"tag_type": "explicit", "tag": 709, "optional": True}),
        ("attestation_id_brand", core.OctetString, {"tag_type": "explicit", "tag": 710, "optional": True}),
        ("attestation_id_device", core.OctetString, {"tag_type": "explicit", "tag": 711, "optional": True}),
        ("attestation_id_product", core.OctetString, {"tag_type": "explicit", "tag": 712, "optional": True}),
        ("attestation_id_serial", core.OctetString, {"tag_type": "explicit", "tag": 713, "optional": True}),
        ("attestation_id_imei", core.OctetString, {"tag_type": "explicit", "tag": 714, "optional": True}),
        ("attestation_id_meid", core.OctetString, {"tag_type": "explicit", "tag": 715, "optional": True}),
        ("attestation_id_manufacturer", core.OctetString, {"tag_type": "explicit", "tag": 716, "optional": True}),
        ("attestation_id_model", core.OctetString, {"tag_type": "explicit", "tag": 717, "optional": True}),
        ("vendor_patch_level", core.Integer, {"tag_type": "explicit", "tag": 718, "optional": True}),
        ("boot_patch_level", core.Integer, {"tag_type": "explicit", "tag": 719, "optional": True}),
        ("device_unique_attestation", core.Null, {"tag_type": "explicit", "tag": 720, "optional": True}),
    ]


class KeyDescription(core.Sequence):
    _fields = [
        ("attestation_version", core.Integer),
        ("attestation_security_level", SecurityLevel),
        ("keymaster_version", core.Integer),
        ("keymaster_security_level", SecurityLevel),
        ("attestation_challenge", core.OctetString),
        ("unique_id", core.OctetString),
        ("software_enforced", AuthorizationList),
        ("hardware_enforced", AuthorizationList),
    ]


class AttestationPackageInfo(core.Sequence):
    _fields = [
        ("package_name", core.OctetString),
        ("version", core.Integer),
    ]


class AttestationPackageInfoSetOf(core.SetOf):
    _child_spec = AttestationPackageInfo


class OctetStringSetOf(core.SetOf):
    _child_spec = core.OctetString


class AttestationApplicationId(core.Sequence):
    _fields = [
        ("package_info_records", AttestationPackageInfoSetOf),
        ("signature_digests", OctetStringSetOf),
    ]


# --- result type -------------------------------------------------------------


@dataclass
class AttestationInfo:
    public_key_der: bytes
    security_level: str  # "trusted_environment" | "strong_box"
    package_name: str
    signature_digests: list[bytes] = field(default_factory=list)
    os_version: int | None = None
    os_patch_level: int | None = None
    verified_boot_state: str = ""
    device_locked: bool = False


# --- chain / root checks ------------------------------------------------------


def _load_pinned_roots() -> list[x509.Certificate]:
    return [x509.load_pem_x509_certificate(pem.encode()) for pem in GOOGLE_HARDWARE_ATTESTATION_ROOTS_PEM]


def _verify_signatures_up_the_chain(certs: list[x509.Certificate]) -> None:
    for i in range(len(certs) - 1):
        child, issuer = certs[i], certs[i + 1]
        try:
            child.verify_directly_issued_by(issuer)
        except (InvalidSignature, ValueError, TypeError) as exc:
            raise AttestationError(f"chain signature invalid at position {i}: {exc}") from exc


def _assert_root_is_pinned(root: x509.Certificate) -> None:
    root_der = root.public_bytes(Encoding.DER)
    pinned_der = {r.public_bytes(Encoding.DER) for r in _load_pinned_roots()}
    if root_der not in pinned_der:
        raise AttestationError("attestation chain does not terminate at a pinned Google root")


def _assert_not_revoked(certs: list[x509.Certificate]) -> None:
    """Best-effort: a network failure here logs a warning rather than blocking
    enrollment, since hard-failing enrollment on Google's endpoint being briefly
    unreachable is a worse trade-off for a prototype than a rare missed revocation.
    Flip to fail-closed for anything beyond a prototype."""
    try:
        resp = httpx.get(REVOCATION_STATUS_URL, timeout=5.0)
        resp.raise_for_status()
        entries = resp.json().get("entries", {})
    except Exception as exc:  # noqa: BLE001
        logger.warning("could not fetch attestation revocation list: %s", exc)
        return

    for cert in certs[:-1]:  # root itself is never revoked via this list
        serial_hex = format(cert.serial_number, "x")
        if serial_hex in entries:
            raise AttestationError(f"attestation cert {serial_hex} is revoked: {entries[serial_hex]}")


def _get_key_description(leaf: x509.Certificate) -> KeyDescription:
    try:
        ext = leaf.extensions.get_extension_for_oid(KEY_DESCRIPTION_OID)
    except x509.ExtensionNotFound as exc:
        raise AttestationError("leaf certificate has no KeyDescription extension") from exc
    return KeyDescription.load(ext.value.value)


def _parse_application_id(software_enforced: AuthorizationList) -> AttestationApplicationId | None:
    raw = software_enforced["attestation_application_id"]
    if raw.native is None:
        return None
    return AttestationApplicationId.load(raw.native)


# --- main entry point ---------------------------------------------------------


def verify_chain(chain_der: list[bytes], expected_challenge: bytes) -> AttestationInfo:
    """Verify an Android Key Attestation certificate chain per plan.md §4.2.

    chain_der[0] is the leaf (the app's freshly generated key); chain_der[-1] is
    the root, matching the order returned by KeyStore.getCertificateChain().
    """
    if len(chain_der) < 2:
        raise AttestationError("attestation chain too short")

    certs = [x509.load_der_x509_certificate(d) for d in chain_der]
    leaf = certs[0]

    # 1. chain terminates at a pinned Google Hardware Attestation root
    _verify_signatures_up_the_chain(certs)
    _assert_root_is_pinned(certs[-1])

    # 2. no cert in the chain is revoked
    _assert_not_revoked(certs)

    kd = _get_key_description(leaf)

    # 3. attestationChallenge freshness -- ties this cert to the enrollment we issued
    if kd["attestation_challenge"].native != expected_challenge:
        raise AttestationError("stale or replayed attestation challenge")

    # 4. hardware-backed, not a software-only key
    security_level = kd["attestation_security_level"].native
    if security_level not in ("trusted_environment", "strong_box"):
        raise AttestationError(f"software-only key (security level={security_level})")

    # 5. verifiedBootState == Verified AND deviceLocked == true -- rejects rooted
    #    / unlocked-bootloader devices. This is the check that makes step 6 below
    #    trustworthy at all (plan.md §0.3).
    hw = kd["hardware_enforced"]
    rot = hw["root_of_trust"]
    if rot.native is None:
        raise AttestationError("no root-of-trust record in hardware-enforced list")
    if rot.native["verified_boot_state"] != "verified" or not rot.native["device_locked"]:
        raise AttestationError("bootloader unlocked / device not verified-boot Verified")

    # 6. attestationApplicationId: package name + release signing cert digest.
    #    Software-enforced list, therefore only meaningful because step 5 already
    #    established the OS itself is intact.
    app_id = _parse_application_id(kd["software_enforced"])
    if app_id is None:
        raise AttestationError("no attestationApplicationId in software-enforced list")

    package_names = {rec["package_name"].native.decode() for rec in app_id["package_info_records"]}
    if app_settings.android_package not in package_names:
        raise AttestationError(f"unexpected package(s) {package_names}")

    signature_digests = [d.native for d in app_id["signature_digests"]]
    pinned = app_settings.pinned_signing_cert_digests
    if pinned and not (set(signature_digests) & pinned):
        raise AttestationError("app not signed with a pinned release signing cert")

    # 7. purpose == SIGN, origin == GENERATED, key requires user authentication
    purposes = {p.native for p in hw["purpose"]} if hw["purpose"].native is not None else set()
    if purposes and KM_PURPOSE_SIGN not in purposes:
        raise AttestationError(f"key purpose {purposes} does not include SIGN")
    origin = hw["origin"].native
    if origin is not None and origin != KM_ORIGIN_GENERATED:
        raise AttestationError(f"key origin={origin} is not GENERATED in secure hardware")
    if hw["no_auth_required"].native is not None:
        raise AttestationError("key not gated on user authentication (noAuthRequired present)")

    return AttestationInfo(
        public_key_der=leaf.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo),
        security_level=security_level,
        package_name=app_settings.android_package,
        signature_digests=signature_digests,
        os_version=hw["os_version"].native,
        os_patch_level=hw["os_patch_level"].native,
        verified_boot_state=rot.native["verified_boot_state"],
        device_locked=rot.native["device_locked"],
    )
