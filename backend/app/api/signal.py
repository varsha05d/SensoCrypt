"""WebRTC signaling relay (plan.md §11 Phase 6, §R2): forwards SDP offer/answer, ICE
candidates, and liveness verdicts between exactly two peers in the same call. No media or
verdict content is inspected or persisted here -- it's a pure relay; each peer signs its own
verdict on its own end.
"""

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

router = APIRouter()

_rooms: dict[str, list[WebSocket]] = {}


@router.websocket("/ws/signal/{call_id}")
async def signal_ws(ws: WebSocket, call_id: str):
    await ws.accept()

    peers = _rooms.setdefault(call_id, [])
    if len(peers) >= 2:
        await ws.close(code=4409)  # call already has two peers
        return
    peers.append(ws)

    # Tell both sides once the room actually has two peers -- otherwise whichever peer
    # joins first can send its SDP offer into an empty room before the second peer ever
    # connects, and that offer is just lost (nothing replays it to a late joiner). Both
    # peers run identical client code, so without an explicit role assignment BOTH would
    # try to become the offerer simultaneously -- a classic WebRTC "glare" bug where each
    # side's setRemoteDescription(offer) collides with its own already-sent local offer,
    # corrupting negotiation (verdict messages kept flowing fine since those are plain
    # relay, independent of whether SDP/ICE ever completed -- only video stayed black).
    # Whoever joined first (peers[0]) is the offerer; the second peer only answers.
    if len(peers) == 2:
        await peers[0].send_text('{"type":"ready","role":"offerer"}')
        await peers[1].send_text('{"type":"ready","role":"answerer"}')

    try:
        while True:
            msg = await ws.receive_text()
            for peer in peers:
                if peer is not ws:
                    await peer.send_text(msg)
    except WebSocketDisconnect:
        pass
    finally:
        if ws in peers:
            peers.remove(ws)
        if not peers:
            _rooms.pop(call_id, None)
