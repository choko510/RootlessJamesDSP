# Decisions

## Keep disabled DSP state out of the base engine allocation

Reverb and Compander own their large fixed working states through nullable pointers. Compander keeps only its small configuration in `JamesDSPLib` so disable can release the working state without changing re-enable behavior. Crossfeed expands HRTF blobs and only the selected convolution mode on demand, then releases them on disable. The Rootless JNI wrapper uses an explicit convolver release path, while the legacy library disable API retains its prior re-enable semantics.

## Keep heavyweight resources scoped to the feature that needs them

The EEL VM keeps roughly 64 MiB of native memory, so LiveProg allocates and registers it only while a script is loaded and releases the VM, code, and pointers under the DSP lock on disable or failure. Rootless PCM buffers follow the selected encoding, and PCM16 conversion scratch is prepared only for the Short path.

## Close local engines before delayed native release

Local-engine close atomically invalidates the handle, cancels preference synchronization through the base close, and rejects later JNI work. Existing native calls retain a 100 ms grace period, after which a daemon timer frees the captured handle and terminates itself.

## Keep the realtime path snapshot-based and allocation-free

Car Audio settings are materialized into immutable runtime snapshots on the control thread. The audio thread reuses one encoding-specific PCM array, skips Car Audio entirely when all features are disabled, and uses one prepared Float scratch only for Short conversion; lookup tables keep hot-path math bounded without changing public settings or JNI contracts.

## Keep trace metrics valid when an optional section is absent

Rootless tracing always records the Car Audio section when enabled, but the standard Car-disabled macrobenchmark omits that section from `TraceSectionMetric` because the AndroidX formatter cannot serialize a zero-occurrence metric. The raw Perfetto section remains available for enabled scenarios.

## Use bounded multiband width and all-pass diffusion for the car Spatializer

The Spatializer limits widening in bass and upper treble and uses short all-pass Side diffusion for envelopment. Center position remains independent of Side processing, while a bounded 90–320 Hz direct-signal stage applies only a small sustained lift and a larger, faster attack-only lift for impact. Cascaded high-pass stages keep sub-bass out of the recirculating Mid ambience; a direct 5–20 ms delayed Side copy remains rejected because it creates a distinct comb-colored cue before convincing depth.

## Use a quiet decorrelated Mid bed with cabin-size profiles

For a two-channel front-seat signal, derive ambience from sustained Mid content and feed separate short cascaded all-pass paths per channel. Gate attacks toward the direct signal, keep the ambience mix low, and expose Compact/sedan, Wagon/small SUV, and Large SUV/minivan profiles so depth scales with cabin size without a strong isolated echo.

## Use separate files for persistent working memory

Separate files keep active context, design rationale, reusable lessons, environment quirks, backlog, and completion history distinct. Agents can therefore load only the context relevant to the current task while keeping `current.md` concise.
