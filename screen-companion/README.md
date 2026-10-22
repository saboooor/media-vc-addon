# Voice Bridge Screens companion

Client-only Fabric mod that draws one continuous video surface across an existing vc-addon-discs screen. The server sends H.264 video access units (or JPEG artwork/fallback images), and the client uploads one texture and draws one full-screen quad. The companion bypasses frame models, individual map tiles, borders, labels, and decorations. The upper-left frame anchors the display; the remaining frames provide its layout and are hidden during video playback.

Video retains the configured screen FPS. Companion texture resolution is independent of map tiles: `[screen].companion_resolution` accepts `map`, `720p`, `1080p` (default), or `source` (bounded at 3840×2160). Video is fitted to the screen aspect ratio with black bars, without upscaling source content. Vanilla clients keep columns × 128 by rows × 128 map pixels. Texture uploads run before each rendered frame, independently of Minecraft's 20 Hz game tick; the latest decoded image is displayed without queuing old frames. Actual playback cadence depends on source FPS, decoding speed, network delivery, and client rendering FPS. Network fragments are reassembled before decoding, so they are never drawn as tiles. Players without the mod keep the vanilla map display. Audio stays on the voice bridge. Media credentials and URLs remain on the server; no client FFmpeg or extra port is needed.

## Install

1. Replace the server's discs plugin with the updated `vc-addon-discs/plugin/build/libs/vc-addon-discs-1.2.1.jar` and restart. Both server and companion must use protocol v4; older companions fall back to vanilla maps.
2. Install Fabric Loader 0.19.5 or later and Fabric API matching your Minecraft version.
3. Put `screen-companion/build/libs/vcb-screen-companion-0.1.0.jar` in the client's `mods` directory. The same JAR supports all six Minecraft versions below. Remove any separately installed companion JAR first.
4. Join and play a linked screen normally. Negotiation happens automatically within five seconds. Clients on servers without the updated plugin behave normally.

| Minecraft | Client Java | Optional single-version build output |
| --- | --- | --- |
| 1.21.11 | 21+ | `mc12111/build/libs/vcb-screen-companion-1.21.11-0.1.0.jar` |
| 26.1 | 25+ | `mc261/build/libs/vcb-screen-companion-26.1-0.1.0.jar` |
| 26.1.1 | 25+ | `mc2611/build/libs/vcb-screen-companion-26.1.1-0.1.0.jar` |
| 26.1.2 | 25+ | `mc2612/build/libs/vcb-screen-companion-26.1.2-0.1.0.jar` |
| 26.2 | 25+ | `mc262/build/libs/vcb-screen-companion-26.2-0.1.0.jar` |
| 26.3 | 25+ | `mc263/build/libs/vcb-screen-companion-26.3-0.1.0.jar` |

In the server's `discs.toml`, under `[screen]`, `companion_rgb = true` enables negotiation and `companion_quality = 85` sets JPEG quality (40–95). Set `companion_rgb = false` before restarting playback to disable RGB. Dithering and Oklab palette conversion affect vanilla fallback only, not RGB clients.

## Build and verify

Run `./gradlew build` here to build and test all six targets and create the universal JAR in `build/libs/`. Use `./gradlew universalJar` to package without running tests, or `./gradlew :mc12111:build` for one standalone target. Gradle downloads the matching Java toolchains.

The universal JAR contains six nested Fabric mods. Fabric Loader selects the implementation whose exact Minecraft and Java dependencies match; the outer mod requires an implementation so unsupported versions fail clearly. Each implementation has a distinct version to keep the alternatives separate during dependency resolution. The 1.21.11 implementation is remapped to intermediary names; 26.x implementations ship unobfuscated. Fabric API is still installed separately for your Minecraft version. This packaging supports the listed versions only, not arbitrary older or future releases.

Before distributing, verify in a real client: join a server without the plugin; join with one vanilla and one companion client; start, stop, pause, seek, switch tracks, leave/re-enter screen range, reconnect, and switch worlds. Test ordinary maps, multiple screens, and resource reloads. Check edge-to-edge alignment, all wall directions, floor/ceiling screens, rotated frames, and restoration after stopping. Compiling a mixin does not replace checking its injection during game startup.

## Protocol v4

All integers use network byte order, with no VarInt envelope inside plugin messages.

- Client → server `vcb:screen_hello`: exactly one byte, `4`.
- Server → client `vcb:screen_rgb`: four 32-bit integers (upper-left map ID, frame sequence, complete body length, chunk offset), then up to 32748 body bytes. Total payload is at most 32764 bytes.
- The reassembled body contains nine 32-bit integers: columns, rows, codec (0 JPEG, 1 H.264), pixel width, pixel height, stream ID, frame number, keyframe flag (0/1), and reserved (0). Then come columns × rows map IDs in row-major order and one JPEG or Annex B H.264 access unit. JPEG dimensions come from its header; its width/height fields are zero. H.264 streams use baseline, 8-bit YUV420, one reference frame, no B-frames, AUD delimiters and SPS/PPS repeated at every IDR.
- Server → client `vcb:screen_clear`: one 32-bit map ID; release the containing screen and restore vanilla rendering.

Chunks must arrive in sequence with contiguous offsets and matching frame sequence/length. Partial images are never uploaded. The client bounds reassembly and decoding to eight screens, 256 frame positions per screen, 4 MiB encoded access units, 8192 pixels per dimension, and 8,294,400 decoded pixels per image. JPEG dimensions are read and checked before decoding; they are independent of the map layout. Unsupported dimensions retain vanilla fallback. JPEG and H.264 decoding run on a background worker; texture upload and drawing run on the client thread. Disconnect invalidates pending work and releases textures.

Resolution changes apply to newly started playback. Higher resolutions increase decoding, encoding, memory, and bandwidth costs, including server-side processing for vanilla fallback. A JPEG exceeding the 4 MiB transport limit falls back to a map-sized RGB image. Updating the server and companion together is required; protocol v2/v3 companions are not negotiated.

## H.264 streaming

`[screen].companion_codec = "h264"` is the default. The server uses its existing FFmpeg executable with `libx264`; no extra client executable or port is needed. `[screen].companion_bitrate_kbps = 4000` sets the VBV maximum video bitrate (256–20000 kbit/s), excluding protocol overhead and short bursts. Set `companion_codec = "jpeg"` for independent-frame transport. `companion_quality` affects JPEG only. Codec settings apply to new playback.

The encoder keeps one pending raw frame and reuses three input buffers. Raw frames may be replaced before encoding; encoded reference frames are never coalesced. The client queues at most four access units / 8 MiB per screen. A sequence gap or overflow discards dependent frames until the next keyframe, normally within one second of encoded video. Only decoded images are coalesced for rendering. Access-unit framing introduces approximately one frame of lookahead. Late joins can wait for the next keyframe. Stop, seek, pause, FPS changes, and resolution changes release or restart the encoder as appropriate. Encoder failure or a three-second output stall switches playback to JPEG; artwork always uses JPEG.

The companion bundles [JCodec 0.2.5](https://github.com/jcodec/jcodec) under its BSD license (`META-INF/LICENSE-jcodec.txt`). Decoding is software-based and adds client CPU work; 720p or JPEG can work better on slower clients. Baseline decoding, colors, sequence-gap recovery and queue limits are tested across all six Java/Minecraft targets. Real-client startup, disconnect, world change, resource reload, and sustained mixed-viewer playback still need in-game verification.
