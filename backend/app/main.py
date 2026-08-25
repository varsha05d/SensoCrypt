import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.auth import router as auth_router
from app.api.session import router as session_router
from app.api.signal import router as signal_router
from app.api.telemetry import router as telemetry_router
from app.db.models import Base
from app.db.session import engine

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Phase 2 skeleton: create tables directly. Swap for Alembic migrations
    # once the schema needs to evolve without dropping data.
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


app = FastAPI(title="SensoCrypt", lifespan=lifespan)
app.include_router(auth_router)
app.include_router(session_router)
app.include_router(signal_router)
app.include_router(telemetry_router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
