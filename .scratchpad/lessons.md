# Lessons

## Spatial controls need signal-level invariants

Mode profiles should scale user controls rather than impose hidden minimum values. Test zero-value transparency, center independence from Side processing, bounded direct and Side gains, attack-versus-sustain behavior, and the impulse response tail so stronger spatial settings remain both audible and predictable.

For car stereo envelopment, a small decorrelated ambience contribution from sustained Mid content is more stable than widening the transient itself. Regression tests should cover inter-channel difference, broadband gain, profile changes, and late impulse peaks.

Low-frequency spaciousness should be controlled at both sources: remove sub-bass before any recirculating ambience path, and keep sustained punch gain materially below transient gain. Verify sub-bass transparency and post-burst decay rather than relying only on steady-state frequency tests.

## Inspect existing agent guidance before adding shared rules

Checking the repository root and `.github/` for existing instructions before creating `AGENTS.md` or Prompt files prevents accidental overwrites and exposes rule conflicts early.
