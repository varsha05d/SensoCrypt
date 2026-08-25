# SensoCrypt

Hardware-bound liveness verification for video calls — helps someone tell a real caller from an AI-generated one in real time.

## Structure

- `backend/` — FastAPI service: enrollment/attestation, session auth, WebRTC signaling relay, and the liveness scoring engine (egomotion + illumination challenge-response).
- `android/` — Kotlin/Jetpack Compose client: hardware-attested key enrollment, WebRTC calling, and live in-call verification.
- `eval/` — offline analysis and negative-control scripts used to calibrate the liveness scoring thresholds.

## Running locally

```bash
docker compose up -d
```

Brings up the backend API and its Postgres database. See `backend/.env.example` for required environment variables.

## Deployment

`render.yaml` at the repo root defines a Render Blueprint (web service + free Postgres) for deploying the backend.
