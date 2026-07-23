# Decisions

## Use bounded multiband width and all-pass diffusion for the car Spatializer

The Spatializer limits widening in bass and upper treble and uses short all-pass Side diffusion for envelopment. Center position remains independent of Side processing, while a bounded 90–320 Hz direct-signal stage applies only a small sustained lift and a larger, faster attack-only lift for impact. Cascaded high-pass stages keep sub-bass out of the recirculating Mid ambience; a direct 5–20 ms delayed Side copy remains rejected because it creates a distinct comb-colored cue before convincing depth.

## Use a quiet decorrelated Mid bed with cabin-size profiles

For a two-channel front-seat signal, derive ambience from sustained Mid content and feed separate short cascaded all-pass paths per channel. Gate attacks toward the direct signal, keep the ambience mix low, and expose Compact/sedan, Wagon/small SUV, and Large SUV/minivan profiles so depth scales with cabin size without a strong isolated echo.

## Use separate files for persistent working memory

Separate files keep active context, design rationale, reusable lessons, environment quirks, backlog, and completion history distinct. Agents can therefore load only the context relevant to the current task while keeping `current.md` concise.
