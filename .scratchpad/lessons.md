# Lessons

## Embedded native arrays are paid for even when their feature is disabled

Large fixed arrays inside `JamesDSPLib` contribute directly to every engine allocation. Move optional effect state behind feature-owned pointers, keep only lightweight rehydration data resident, and protect processing plus destruction with the same DSP mutex. For `dumpsys meminfo`, compare Heap Alloc rather than Heap Size and use stabilized multi-sample medians.

## Preserve failure information before releasing its owner

Compiler diagnostics returned by the EEL VM must be copied before freeing the VM. Error-path logging also exposed that a `va_list` cannot be reused after `vsnprintf`; use `va_copy` for the sizing pass so diagnostics cannot corrupt later JNI strings.

## Macrobenchmarks need the application package explicitly

An Android macrobenchmark's instrumentation context belongs to the benchmark APK, not necessarily the measured application. Keep the measured package in variant-specific BuildConfig and place Rootless-only scenarios in the Rootless source set.

## Zero-occurrence trace metrics are not formatter-safe

`TraceSectionMetric` can capture an optional section with zero occurrences, but the AndroidX result formatter may fail while writing the JSON. Keep optional metrics out of the standard disabled scenario and inspect the trace directly for absence checks.

## Reverb output is not deterministic across fresh native handles

The native Reverb path seeds internal state from a static/random source, so cross-handle bit-for-bit PCM comparisons are invalid. In-place regression coverage should use deterministic effects for exact comparisons and finite/output-bound checks for stochastic effects.

## Spatial controls need signal-level invariants

Mode profiles should scale user controls rather than impose hidden minimum values. Test zero-value transparency, center independence from Side processing, bounded direct and Side gains, attack-versus-sustain behavior, and the impulse response tail so stronger spatial settings remain both audible and predictable.

For car stereo envelopment, a small decorrelated ambience contribution from sustained Mid content is more stable than widening the transient itself. Regression tests should cover inter-channel difference, broadband gain, profile changes, and late impulse peaks.

Low-frequency spaciousness should be controlled at both sources: remove sub-bass before any recirculating ambience path, and keep sustained punch gain materially below transient gain. Verify sub-bass transparency and post-burst decay rather than relying only on steady-state frequency tests.

## Inspect existing agent guidance before adding shared rules

Checking the repository root and `.github/` for existing instructions before creating `AGENTS.md` or Prompt files prevents accidental overwrites and exposes rule conflicts early.
