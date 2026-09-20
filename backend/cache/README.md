# Cached builds

Every successful `/build` stream is teed to `<slugified-prompt>.ndjson` here, and only
finalised once the stream reaches its `done` line — so a partial failure never poisons
the cache.

These files are the demo's insurance policy. Replay one with:

    POST /build/cached/{name}

or in-game with `/mc2p cached <name>`. They are committed deliberately: a cached build
is guaranteed to work with no network at all.
