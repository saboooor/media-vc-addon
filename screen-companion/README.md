# Voice Bridge Screens companion

Client-only Fabric mod that draws one continuous video surface across an existing vc-addon-discs screen. The server encodes the entire display as one JPEG, and the client uploads one texture and draws one full-screen quad. The companion bypasses frame models, individual map tiles, borders, labels, and decorations. The upper-left frame anchors the display; the remaining frames provide its layout and are hidden during video playback.

Video retains the configured screen FPS and resolution (columns × 128 by rows × 128). Network fragments are reassembled before decoding, so they are never drawn as tiles. Players without the mod keep the vanilla map display. Audio stays on the voice bridge. Media credentials and URLs remain on the server; no client FFmpeg or extra port is needed.

## Install

1. Replace the server's discs plugin with the updated `vc-addon-discs/plugin/build/libs/vc-addon-discs-1.2.1.jar` and restart. Both server and companion must use protocol v2; older companions fall back to vanilla maps.
2. Install Fabric Loader 0.19.5 or later and Fabric API matching your Minecraft version.
3. Put the matching companion JAR from the table below in the client's `mods` directory. Install only one version.
4. Join and play a linked screen normally. Negotiation happens automatically within five seconds. Clients on servers without the updated plugin behave normally.

| Minecraft | Client Java | Build output |
| --- | --- | --- |
| 1.21.11 | 21+ | `mc12111/build/libs/vcb-screen-companion-1.21.11-0.1.0.jar` |
| 26.1 | 25+ | `mc261/build/libs/vcb-screen-companion-26.1-0.1.0.jar` |
| 26.1.1 | 25+ | `mc2611/build/libs/vcb-screen-companion-26.1.1-0.1.0.jar` |
| 26.1.2 | 25+ | `mc2612/build/libs/vcb-screen-companion-26.1.2-0.1.0.jar` |
| 26.2 | 25+ | `mc262/build/libs/vcb-screen-companion-26.2-0.1.0.jar` |
| 26.3 | 25+ | `mc263/build/libs/vcb-screen-companion-26.3-0.1.0.jar` |

In the server's `discs.toml`, under `[screen]`, `companion_rgb = true` enables negotiation and `companion_quality = 85` sets JPEG quality (40–95). Set `companion_rgb = false` before restarting playback to disable RGB. Dithering and Oklab palette conversion affect vanilla fallback only, not RGB clients.

## Build and verify

Run `./gradlew build` here to build all six targets, or `./gradlew :mc12111:build` for one target. Gradle downloads the matching Java toolchains. Version builds are separate because 1.21.11 is remapped and 26.x ships unobfuscated.

Before distributing, verify in a real client: join a server without the plugin; join with one vanilla and one companion client; start, stop, pause, seek, switch tracks, leave/re-enter screen range, reconnect, and switch worlds. Test ordinary maps, multiple screens, and resource reloads. Check edge-to-edge alignment, all wall directions, floor/ceiling screens, rotated frames, and restoration after stopping. Compiling a mixin does not replace checking its injection during game startup.

## Protocol v2

All integers use network byte order, with no VarInt envelope inside plugin messages.

- Client → server `vcb:screen_hello`: exactly one byte, `2`.
- Server → client `vcb:screen_rgb`: four 32-bit integers (upper-left map ID, frame sequence, complete body length, chunk offset), then up to 32748 body bytes. Total payload is at most 32764 bytes.
- The reassembled body contains 32-bit columns and rows, then columns × rows map IDs in row-major order, followed by one JPEG for the entire screen.
- Server → client `vcb:screen_clear`: one 32-bit map ID; release the containing screen and restore vanilla rendering.

Chunks must arrive in sequence with contiguous offsets and matching frame sequence/length. Partial images are never uploaded. The client bounds reassembly and decoding to eight screens, 256 frame positions per screen, 4 MiB JPEGs, and 8192 pixels per dimension. Unsupported dimensions retain vanilla fallback. JPEG decoding runs on a background worker; texture upload and drawing run on the client thread. Disconnect invalidates pending work and releases textures.
