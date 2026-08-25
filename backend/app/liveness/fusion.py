"""Fusion + trust state machine (plan.md §8).

fuse() is a plain average standing in for the plan's logistic regression -- that needs
labelled genuine/attack session data (§12) we don't have yet. The state machine (hysteresis,
dwell counts) is the real design from §8 and is unaffected by how the fused score is
computed; swapping in a trained fusion model later is a drop-in replacement for fuse() alone.
"""

from app.liveness.constants import DWELL_DEGRADE, DWELL_SUSPECT, DWELL_TRUST, P_DEGRADE, P_SUSPECT, P_TRUST

# fuse() takes the BEST of the two channels, not a weighted blend. They're independent,
# orthogonal evidence -- egomotion rules out replay/injection, illumination rules out
# screen-replays and many deepfakes (§0.1) -- so there's no security reason to require them
# to agree in the same instant. A weighted average let one channel's transient weak reading
# (e.g. motion happening to read near-zero from natural stillness in the same window
# illumination read strongly) drag down an otherwise-conclusive result. An attacker still
# has to defeat BOTH channels' underlying physical checks somewhere across the session (the
# state machine below still requires DWELL_TRUST consecutive good windows, not one lucky
# reading) -- this only changes how a single window's evidence is combined, not how much
# sustained evidence is required overall.


def fuse(s_a: float | None, s_illum: float | None) -> float:
    scores = [s for s in (s_a, s_illum) if s is not None]
    return max(scores) if scores else 0.0


class TrustFSM:
    """DEGRADED is the start state (no evidence yet is not the same as trusted).
    §8's transitions, simplified to a single dwell count per direction rather than the
    diagram's two slightly different thresholds for entering vs. re-entering TRUSTED."""

    def __init__(self):
        self.state = "DEGRADED"
        self._trust_streak = 0
        self._degrade_streak = 0
        self._suspect_streak = 0
        self._recover_streak = 0  # consecutive windows with p >= P_DEGRADE, used to leave SUSPECT gradually

    def update(self, p: float) -> str:
        self._trust_streak = self._trust_streak + 1 if p >= P_TRUST else 0
        self._degrade_streak = self._degrade_streak + 1 if p < P_DEGRADE else 0
        self._suspect_streak = self._suspect_streak + 1 if p < P_SUSPECT else 0
        self._recover_streak = self._recover_streak + 1 if p >= P_DEGRADE else 0

        if self.state == "TRUSTED":
            # Sticky for the rest of the session, by the same product decision as
            # worker.py's _handle_challenge() (once verified, stop re-checking -- no more
            # flashes). Without this, a verified person who just holds the phone still
            # produces weak/near-zero motion evidence with no illumination evidence to back
            # it up (challenges are paused too), which would otherwise drift the degrade
            # streak past DWELL_DEGRADE and silently drop back to DEGRADED -- re-arming the
            # flash and contradicting "verified once, done" for no real reason (nothing
            # about the feed actually got worse, the phone just stopped moving).
            pass
        elif self.state == "DEGRADED":
            if self._suspect_streak >= DWELL_SUSPECT:
                self.state = "SUSPECT"
            elif self._trust_streak >= DWELL_TRUST:
                self.state = "TRUSTED"
        elif self.state == "SUSPECT":
            # plan.md §8's real design has SUSPECT leave only via a passed re-auth
            # challenge, which isn't wired up yet -- without it, requiring a single
            # sustained burst all the way up to P_TRUST to escape is too punishing (one
            # bad reading, e.g. from a since-fixed scoring bug, gets "stuck" for a long
            # time even once evidence turns solidly positive again). This graduated
            # recovery -- moderate evidence steps back down to DEGRADED first, only a
            # strong sustained burst jumps straight to TRUSTED -- is a deliberate,
            # documented deviation for usability, not an oversight.
            if self._trust_streak >= DWELL_TRUST:
                self.state = "TRUSTED"
            elif self._recover_streak >= DWELL_DEGRADE:
                self.state = "DEGRADED"

        return self.state
