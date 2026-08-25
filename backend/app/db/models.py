"""SQLAlchemy models for the tables Phase 2 needs (plan.md §17.2).

liveness_windows and audit_log are added in later phases once there's a
liveness engine and a trust state machine to write records from.
"""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, LargeBinary, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Device(Base):
    __tablename__ = "devices"

    device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    public_key_der: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    security_level: Mapped[str] = mapped_column(String, nullable=False)  # 'trusted_environment' | 'strong_box'
    package_name: Mapped[str] = mapped_column(String, nullable=False)
    signing_digest: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    verified_boot: Mapped[bool] = mapped_column(Boolean, nullable=False)
    os_version: Mapped[str | None] = mapped_column(String, nullable=True)
    model: Mapped[str | None] = mapped_column(String, nullable=True)
    enrolled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Session(Base):
    __tablename__ = "sessions"

    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("devices.device_id"), nullable=False)
    call_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    state: Mapped[str] = mapped_column(String, nullable=False, default="INIT")  # INIT|AUTHED|KEYED|ACTIVE|CLOSED
    channel_binding: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    dtls_fp: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    last_seq: Mapped[int] = mapped_column(default=0)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    end_reason: Mapped[str | None] = mapped_column(String, nullable=True)
