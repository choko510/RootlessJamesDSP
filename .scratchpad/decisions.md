# Decisions

## Use bounded multiband width and all-pass diffusion for the car Spatializer

The Spatializer keeps the Mid signal at unity, limits widening in bass and upper treble, and uses short all-pass Side diffusion for envelopment. A direct 5–20 ms delayed Side copy was rejected because it creates a distinct comb-colored cue before it creates convincing depth.

## Use separate files for persistent working memory

Separate files keep active context, design rationale, reusable lessons, environment quirks, backlog, and completion history distinct. Agents can therefore load only the context relevant to the current task while keeping `current.md` concise.
