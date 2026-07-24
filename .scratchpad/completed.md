# Completed

- 2026-07-24: Phase 3 made Rootless PCM processing allocation-free and in-place, precomputed Car Audio runtime settings and fast math, gated inactive meters, added realtime tracing/priority and JNI validation, aligned native build optimization, and added deterministic trace/instrumentation benchmarks.
- 2026-07-24: Phase 2 reduced steady Rootless Native Heap Alloc by another median 10,590 KiB by lazily owning Reverb/Compander state and Crossfeed HRTF data, releasing Rootless convolver data on disable, and closing service-owned coroutine scopes; added effect allocation/release and processing regression coverage.
- 2026-07-24: Reduced Rootless playback native allocation by about 74 MiB by lazily owning the LiveProg VM, using encoding-specific PCM buffers, and making local-engine shutdown deterministic; added Float/Short memory benchmarks and lifecycle regression coverage.
- 2026-07-23: Tightened Spatializer bass by excluding sub-bass from the cabin ambience, narrowing punch to 90–320 Hz, and shifting gain from sustain to attack; added sub-bass and decay regression tests.
- 2026-07-23: Added a front-seat cabin ambience bed with Compact/Standard/Large profiles, transient preservation, UI selection, and gain/click/echo regression coverage.
- 2026-07-23: Added bounded 70–420 Hz direct-signal body and transient punch to the car Spatializer without increasing delayed or antiphase energy; added attack-versus-sustain regression coverage.
- 2026-07-23: Reworked the car Spatializer to use bounded multiband M/S width and short all-pass diffusion instead of a direct Haas delay; added center, width, transparency, and echo regression tests.
- 2026-07-23: Added persistent AI agent working memory, project-wide agent rules, and reusable Working Memory prompts.
