from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://sensocrypt:sensocrypt@localhost:5432/sensocrypt"

    @field_validator("database_url")
    @classmethod
    def _use_asyncpg_driver(cls, v: str) -> str:
        # Managed Postgres providers (e.g. Render) hand out a plain postgresql:// or
        # postgres:// connection string -- SQLAlchemy's async engine needs the asyncpg
        # driver spelled out explicitly.
        if v.startswith("postgres://"):
            v = "postgresql://" + v[len("postgres://") :]
        if v.startswith("postgresql://"):
            v = "postgresql+asyncpg://" + v[len("postgresql://") :]
        return v

    # §4.2 step 6 / §0.3: the two checks that reject a repackaged app running on a
    # rooted-but-otherwise-valid device. Both must be set correctly before enrollment
    # can be trusted -- see plan.md §0.3 for why.
    android_package: str = "com.sensocrypt"
    pinned_signing_cert_digests_hex: str = ""  # comma-separated hex SHA-256 digests

    # TTLs from plan.md §4.2 / §4.3
    attestation_challenge_ttl_s: int = 120
    auth_nonce_ttl_s: int = 60
    session_token_ttl_s: int = 300

    # PASETO v4.local symmetric key, 32 raw bytes, hex-encoded. Generate with:
    #   python -c "import secrets; print(secrets.token_hex(32))"
    paseto_local_key_hex: str = ""

    @property
    def pinned_signing_cert_digests(self) -> set[bytes]:
        if not self.pinned_signing_cert_digests_hex:
            return set()
        return {bytes.fromhex(d.strip()) for d in self.pinned_signing_cert_digests_hex.split(",") if d.strip()}


settings = Settings()
