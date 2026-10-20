# Runtime performance

The optimization pass keeps the existing playback controls, map fallback, companion protocol, and settings formats.

| Area | Change |
| --- | --- |
| Bass lamps | Cache links by jukebox and chunk, calculate bass and audience candidates once per source, and schedule world updates per chunk. Idle lamps only need periodic validation. Restoring unchanged links does not save again. |
| Spectrum analysis | Reuse FFT working buffers and precompute the window, twiddle factors, and frequency-band edges. Published level arrays remain immutable to readers. |
| Audio | Ordinary jukeboxes, goat horns, and physical stereo use one playback job and player per track. Both voice backends receive the same encoded frames. Pause, seek, failure, and cancellation share that lifecycle. |
| Screen transport | Unchanged companion JPEGs go only to new/returning sessions or during a two-second refresh. Existing map tile deltas and full tiles for new viewers remain in use. |
| Visibility | Capture player positions on event/entity threads and query immutable snapshots. Screen and visualizer audience results are cached for 250 ms. Movement, teleports, world changes, vehicle movement, respawns, joins, and quits update the snapshots. |
| Persistence | One background writer coalesces saves for 250 ms, serializes immutable snapshots, and replaces files atomically where supported. Shutdown flushes pending writes. |
| Displays | Cache visualizer settings and reflection methods, avoid unchanged hologram text/glow updates, and avoid rebuilding bass lists per display. |
| Video processing | Skip tone mapping with no viewers, resolve HDR lookup tables once per frame, and compare raw RGB tiles before palette conversion. |

RGB tile caching uses 49,152 additional bytes per 128×128 map tile after its first rendered frame. Audience updates may appear after a short cache interval. An abrupt process termination can lose settings still inside the save debounce interval; normal plugin shutdown flushes them.

## Local FFT measurement

A local JVM microbenchmark compared a Java reference of the previous FFT loop with the compiled optimized Kotlin analyzer. Both consumed 2,400 mono samples at 48 kHz per update, using a 1 kHz sine wave. After 1,000 warm-up updates, three rounds ran 2,000 updates each. The tested frequency-band outputs matched exactly.

| Measurement | Previous algorithm reference | Optimized analyzer |
| --- | ---: | ---: |
| Median time per update | 266.20 µs | 58.02 µs |
| Thread allocation per update | 16,496 bytes | 80 bytes |

This is approximately 4.6× faster for that workload. It is a microbenchmark, not a measurement of whole-server TPS or packet throughput. Live Paper/Folia profiling with representative player, jukebox, lamp, and screen counts remains necessary to quantify end-to-end impact.

## Verification

Regression coverage includes shared voice-backend frames and cancellation, position snapshot queries and teleports, FFT sample-rate changes and immutable published levels, unchanged RGB tiles, companion arrivals/reconnects/refresh, JPEG cache invalidation, and coalesced/failed/shutdown settings writes.

Run `./gradlew :core:test :plugin:shadowJar` to test and package the plugin.
