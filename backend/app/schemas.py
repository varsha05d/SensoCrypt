from pydantic import BaseModel


class EnrollInitRequest(BaseModel):
    device_model: str
    os_version: str


class EnrollInitResponse(BaseModel):
    enroll_id: str
    att_challenge_b64: str


class EnrollFinishRequest(BaseModel):
    enroll_id: str
    cert_chain_b64: list[str]


class EnrollFinishResponse(BaseModel):
    device_id: str


class ChallengeRequest(BaseModel):
    device_id: str


class ChallengeResponse(BaseModel):
    session_id: str
    nonce_b64: str
    server_ts: int


class VerifyRequest(BaseModel):
    session_id: str
    sig_der_b64: str
    channel_binding_b64: str | None = None


class VerifyResponse(BaseModel):
    token: str
    expires_in: int


class KexRequest(BaseModel):
    session_id: str
    epk_c_b64: str


class KexResponse(BaseModel):
    epk_s_b64: str
