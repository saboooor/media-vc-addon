<img src="https://i.imgur.com/4o67Wn1.png" width="256"/>

<div>
    <a href="https://modrinth.com/mod/plasmo-voice">Plasmo Voice</a>
    <span> | </span>
    <a href="https://modrinth.com/plugin/vc-addon-discs">Modrinth</a>
    <span> | </span>
    <a href="https://github.com/plasmoapp/vc-addon-discs/">GitHub</a>
    <span> | </span>
    <a href="https://discord.com/invite/uueEqzwCJJ">Discord</a>
     <span> | </span>
    <a href="https://www.patreon.com/plasmomc">Patreon</a>
</div>

# vc-addon-discs

Paper only [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice) addon. Play audio from Youtube and other sources in Minecraft using music discs. Inspired by [SVC](https://github.com/henkelmax/simple-voice-chat)'s [AudioPlayer](https://github.com/henkelmax/audio-player) & [CustomDiscs](https://github.com/Navoei/CustomDiscs).

The addon can stream audio from various sources instead of saving audio files on the server. It even supports Youtube and Twitch live streams. Thanks to the brilliant [LavaPlayer](https://github.com/lavalink-devs/lavaplayer) library.

## Installation

1. Install [Plasmo Voice](https://modrinth.com/mod/plasmo-voice), [PacketEvents](https://modrinth.com/plugin/packetevents) and [vc-addon-lavaplayer-lib](https://modrinth.com/mod/vc-addon-lavaplayer-lib). You also need to install Plasmo Voice on your client.
2. Download this plugin from Modrinth and drop it into the `~/plugins` folder.
3. Restart the server

For Minecraft 26.3, use PacketEvents 2.14.0 or newer. PacketEvents is a separate server plugin; rebuilding this addon does not update it.

On Minecraft 26.3, burned horns have their vanilla instrument component removed to avoid a custom-instrument encoding bug in PacketEvents 2.14.0. Custom audio and cooldowns still work, but the vanilla horn-use animation is unavailable. Erasing a horn restores its original instrument. Reburning an existing custom horn applies this workaround too.

## Commands

`/disc burn <url> [name]` – Create a disc with custom audio from the URL. You need to hold a music disc in your hand.

`/disc erase` – Return a custom disc to a normal one.

`/disc playlist <playlisturl> [shuffle|reverse]` – Load a playlist, then right-click a chest directly above an empty jukebox within two minutes. Each eligible unstacked music disc is overwritten with the next playlist track, in chest slot order, and the chest queue starts playing. Requires both `pv.addon.discs.playlist` and `pv.addon.discs.burn`, plus `pv.addon.discs.play` to start playback.

Use `shuffle` to select random songs from the full playlist without repeating entries, or `reverse` to burn from the last track toward the first. Without an option, tracks follow the original playlist order. The chest's available music discs determine how many tracks are burned.

Chest queues:

- Place a chest (single or double) directly above a jukebox and put music discs inside. Open the chest normally to manage its contents.
- Playlist burning skips non-disc items, stacks, and discs that fail the configured burnable-tag requirement. One disc receives one track; extra tracks are left unburned when discs run out. Existing burned discs are overwritten.
- Opening, closing, clicking, or dragging items in the chest does not start playback. Use **Play chest** in jukebox controls to start it. Playlist burning and hopper transfers into the chest can still start playback. Right-clicking the jukebox with a disc stores one disc in the chest and starts playback when idle. A full chest leaves the disc in your hand. Placing a chest above an occupied jukebox transfers its disc into the chest. Discs stay in the chest during playback; ordinary unburned discs are stored but skipped by the custom-audio queue.
- Tracks crossfade over three seconds by default. Open Crossfade settings from the jukebox dialog and use its -1s/+1s buttons to adjust it from 0–30 seconds, or Enable/Disable crossfade to toggle it. The dialog shows the current setting. Changes apply to upcoming transitions in that queue; an ongoing fade finishes at its original duration. New jukeboxes default to three seconds; subsequent queues reuse the jukebox’s chosen crossfade setting. Live streams use normal transitions. Pause and volume affect both tracks during a fade; skipping or stopping cancels the transition. Network loading delays can shorten the overlap.
- The active queue follows chest contents in slot order, including hopper transfers, updating about every quarter second. Added discs join the queue, removed discs leave it, and moving discs changes their order. The current song keeps playing; removing its disc lets it finish before continuing from that position. Refresh an open jukebox dialog to see updated queue buttons. The queue plays once by default. Enable Loop in the jukebox controls to repeat the queue, and Shuffle to visit each disc once in random order per pass. Loop also repeats a standalone custom disc. Use Play chest in the controls to restart a stopped queue.
- If a song cannot be resolved or its audio stream fails/stalls, playback reports the error, removes the failed disc’s custom audio, and skips to the next queued disc. Audio lookup and startup time out after 30 seconds rather than loading indefinitely. Ordinary pauses and manual stops do not erase discs.
- Right-click the jukebox to open playback controls; choose Stop playback to stop. Breaking the chest or jukebox, unloading the jukebox chunk, or restarting the server stops the queue. Queues do not resume automatically.
- Player-triggered playback needs `pv.addon.discs.play` (or the `vc` alias); hopper additions run without a player. Protection plugins can deny the inventory action or jukebox interaction.

`/disc search <query>` – Search tracks on YouTube.

`/disc screen create [name]` – Start interactive screen creation. Click two diagonally opposite corner item frames on a wall to define the screen grid (click the same frame twice for 1x1), then click a jukebox to link it.

`/disc screen list` – View all registered item frame screens and their linked jukeboxes.

`/disc screen link <name>` – Link an existing screen to the jukebox you are looking at.

`/disc screen unlink <name>` – Unlink a screen from its jukebox.

`/disc screen play <name> <url>` – Manually stream a video URL to a screen using server-side FFmpeg.

`/disc screen stop [name]` – Stop video playback on a screen.

`/disc screen clear [name]` – Clear an item frame screen to black.

`/disc screen delete <name>` – Delete a screen configuration.

`/disc screen cancel` – Cancel an active screen selection.

`/disc screen finish` – Finish screen selection without linking a jukebox.

`/disc screen install-ffmpeg` – Download and install a static portable FFmpeg binary directly into `plugins/vc-addon-discs/bin/ffmpeg` for containerized environments (like Pterodactyl or Docker) without system packages or root access.

Music video screens:

Optional full-color Fabric client: see [`screen-companion`](../screen-companion/README.md). Players without it keep normal item-frame colors.

- Form a rectangular wall grid of item frames (e.g. 1x1, 2x2, 3x2, 4x3) and run `/disc screen create [name]`.
- Right-click the first corner item frame, then right-click the opposite corner item frame. For a 1x1 screen, click the same frame twice, pausing briefly between clicks. The plugin automatically detects the grid dimensions, facing direction, and orders the tiles. Item frames and glow item frames can be placed directly on a jukebox, including during playback.
- Right-click a nearby jukebox to link the screen to it.
- Whenever a custom music disc or chest queue track plays in that jukebox, the music video streams to the item frames in real time with aspect ratio preservation and black letterboxing.
- Pausing, resuming, or seeking using the jukebox controls automatically keeps the music video in sync.
- Tagged PQ/HDR10 and HLG video is automatically tone-mapped to SDR before map-palette conversion or companion JPEG encoding. Video decoding retains 16-bit RGB precision through the conversion; ordinary SDR colors keep their original transfer curve. No extra FFmpeg filters or client update are needed.
- Screens skip unchanged map tiles and send only the bounding rectangle of changed palette pixels in each tile. New and returning nearby viewers receive full tiles first. Fast-moving scenes may still update an entire tile.
- Screen colors use a 6-bit-per-channel lookup matched against the server's opaque map palette in [Oklab](https://bottosson.github.io/posts/oklab/). The shared table uses 256 KiB and is computed once on first use; per-pixel rendering remains a table lookup. Minecraft's fixed map palette still limits available colors.
- Stable ordered dithering improves apparent gradients within Minecraft's map palette. In `[screen]` in `discs.toml`, `dithering = true` and `dither_strength = 12` are the defaults. Strength accepts 0–32; use 0 or `dithering = false` for the original sharp palette matching. Higher strengths add texture and can increase changed-region traffic. Settings apply to newly started playback.
- Item frames belonging to screens are automatically fixed and protected from rotation or removal.

`/disc cancel [player]` – Cancel user's goat horn playback. You need `pv.addon.discs.cancel.other` permission to cancel for others.

`/disc effect [preset|toggle|clear|gui]` – Configure audio filters and DSP effects on a jukebox. Look at a jukebox within 8 blocks and run `/disc effect gui` (or click "Audio effects" in the jukebox controls) to open the interactive effects menu. Supports equalizer presets (`bass`, `boost`, `maxed`, `party`, `pop`, `radio`, `soft`, `treb`, `flat`) and real-time DSP effects: 8D audio (`8d`), `echo`, `underwater`, `tremolo`, `vibrato`, `nightcore` (1.25x speed/pitch), and `vaporwave` (0.8x speed/pitch). Run `/disc effect clear` to reset all effects back to flat.

`/disc hologram [edit|done|gui|move|offset|reset]` – Hold a stick and look at a jukebox, then run `/disc hologram` to position its playback hologram directly in the world. `/disc hologram gui` opens the hologram settings, with a Move in world action and position reset. Directional `move`, exact `offset <x> <y> <z>`, and `reset` remain available.

`/disc oauth [force|status]` – Authorize YouTube access via Google device OAuth2 to prevent "Sign in to confirm you're not a bot" or "This video requires login" errors. Check server console for the pairing code, visit https://www.google.com/device with a burner account, and authorize. Use `/disc oauth force` to re-authorize and refresh the stored token.

`/disc reload` – Reload runtime options from `discs.toml` without interrupting playback. Works from the console.

Reloadable options: debug logging, disc glint and burn/erase lore, mono audio, goat horn settings, jukebox distance and visualization, HTTP whitelist, screen settings (fps, distance, ffmpeg path), and the burnable-tag requirement. Playback settings apply to newly started tracks; item settings apply to future burns/erases. Registered recipe results keep their original glint and lore until restart.

Source line weight/default volume, HTTP proxy, YouTube settings, local source settings, and default recipe settings require a server restart. Reload leaves their active values unchanged and keeps the edited values in the config file for the next restart.

## Playback controls

Right-click a jukebox with a custom disc or an active chest queue to open a native Paper dialog, following Lumistones' multi-action dialog pattern. The click opens controls instead of ejecting the disc. Sneak-right-click only opens controls with both hands empty, allowing blocks to be placed while holding an item. A disc aimed at a jukebox with a chest above it goes into the chest.

- Use a two-column button menu to pause/resume, select queue tracks, seek backward/forward 15 seconds, and adjust volume from 0–100%.
- Click a queue track to jump to it, or stop playback entirely. Play/Pause, Refresh, and Stop playback share the first row, with Crossfade settings immediately after them.
- Eject the disc explicitly, or play a stopped disc again.
- View the complete chest queue as clickable track buttons with disc icons, numbered tracks, and the current track highlighted. Click a track to jump to it immediately (clicking the current track restarts it). Playback continues from the selected track in slot order.
- A floating text display above the jukebox updates progress automatically four times per second, with percentage and elapsed/total time. Live streams show a LIVE indicator and elapsed time. Displays are removed when playback stops, the chunk unloads, or the plugin disables. Text displays require Minecraft 1.19.4 or newer.
- Buttons use Minecraft item icons, following Lumistones' sprite labels.
- Crossfade settings open in their own dialog, with a button to return to playback.
- YouTube playback always selects the best available audio source. Other audio sources are unchanged.
- Close or Escape dismisses the dialog without stopping playback. Use Refresh to update the queue display; progress updates automatically above the jukebox.

Seeking is available only for seekable tracks. Controls affect both Plasmo Voice and Simple Voice Chat playback. Control menus expire after two minutes, require you to stay within eight blocks, and stop working when their track or disc changes. Servers predating Paper's dialog API use clickable chat controls instead.

## Permissions

Commands are only available to OP by default:

`pv.addon.discs.burn` – Burn command

`pv.addon.discs.playlist` – Bulk-burn a playlist into chest discs (also requires burn permission)

`pv.addon.discs.erase` – Erase command

`pv.addon.discs.search` – Search command

`pv.addon.discs.screen` – Manage item frame music video screens (create, delete, link, clear, play)

`pv.addon.discs.reload` – Reload runtime configuration (also accepts `vc.addon.discs.reload`)

`pv.addon.discs.cancel` – Command to cancel goat horn playback

`pv.addon.discs.cancel.other` – Command to cancel goat horn playback for the other player

`pv.addon.discs.burn.burnable_check_bypass` – If enabled in a config, you can only burn special discs. This permission allows you to bypass this check

Available to everyone by default:

`pv.addon.discs.play` – Use custom music discs

`pv.addon.discs.control` – Open and use jukebox playback controls (also requires play permission)

# Supported sources

Addon can load audio from:

- YouTube videos and live streams (could require additional setup, see [Common issues](#common-issues))
- SoundCloud
- Bandcamp
- Vimeo
- Twitch live streams
- Direct HTTP URLs

## Supported file formats

If you want to stream audio from direct URLs

- MP3
- FLAC
- WAV
- Matroska/WebM (AAC, Opus or Vorbis codecs)
- MP4/M4A (AAC codec)
- OGG streams (Opus, Vorbis and FLAC codecs)
- AAC streams
- Stream playlists (M3U and PLS)

# API
![Version](https://img.shields.io/badge/dynamic/xml?color=186EF0&label=release&query=/metadata/versioning/versions/version[not(contains(text(),'%2B'))][last()]&url=https://repo.plasmoverse.com/releases/su/plo/voice/addon/discs-paper/maven-metadata.xml)
![Version](https://img.shields.io/badge/dynamic/xml?color=186EF0&label=snapshot&query=/metadata/versioning/versions/version[not(contains(text(),'%2B'))][last()]&url=https://repo.plasmoverse.com/snapshots/su/plo/voice/addon/discs-paper/maven-metadata.xml)

Bukkit events list: https://github.com/plasmoapp/vc-addon-discs/tree/v1/core/src/main/kotlin/su/plo/voice/discs/api.

### Kotlin DSL
```kotlin
repositories {
    maven("https://repo.plasmoverse.com/releases")
}

dependencies {
    compileOnly("su.plo.voice.addon:discs-paper:$VERSION")
}
```

# Common issues

## Problematic YouTube player script
1) Make sure you're using the latest versions of `vc-addon-discs` and `vc-addon-lavaplayer-lib`.
2) Open `plugins/vc-addon-discs/discs.toml`, uncomment and configure `youtube_source.remote_cipher`:
    ```toml
    [youtube_source.remote_cipher]
    url = "https://cipher.kikkia.dev/"
    password = ""
    ```
   *This uses a public instance of [yt-cipher](https://github.com/kikkia/yt-cipher). To host your own, follow their tutorial.*
3) Restart the server and see if it works.
4) If this doesn't work, [try oauth2](#sign-in-to-confirm-youre-not-a-bot--please-sign-in-using-youtube).

## "Sign in to confirm you're not a bot" / "Please sign in" using YouTube
1) Make sure you're using the latest versions of `vc-addon-discs` and `vc-addon-lavaplayer-lib`.
2) Set `youtube_source.use_oauth2` to `true` in `plugins/vc-addon-discs/discs.toml`.
3) Restart the server (`/vreload` will not work).
4) When `vc-addon-discs` initializes, you'll see a message in your console like this (code will be different): https://i.imgur.com/r1o5v8v.png. Follow the instructions in the console message.
5) After successful authorization, you'll see a message in your console: https://i.imgur.com/BD9jCRe.png. You don't need to save token manually, it will be saved when the server is stopped.
6) Done! YouTube tracks should now work as expected.

## Read timed out
This usually means that your server can't reach the server hosting the track.
Check your server's internet connection and contact your hosting provider if the issue persists.

If you have HTTP proxy, you can set it using `http_proxy` config option in `plugins/vc-addon-discs/discs.toml`.
This feature is only available starting from 1.1.x.

## Diagnosing YouTube source quality

YouTube playback always uses the source library's best-available audio selection.

For detailed diagnostics, enable `debug = true` in `discs.toml`, run `/disc reload`, and start or reload a track. Look for `YouTube formats:` and `YouTube selected:` in the server log. These show offered formats and the selection without logging signed stream URLs or credentials. These are source stream bitrates, not the final voice transport bitrate.

### Seeking linked video

Open **Seek** in the playback dialog to jump backward or forward by 15 seconds, 30 seconds, 1 minute, or 5 minutes. The dialog stays open for repeated adjustments and has a Back button. Audio and the linked video receive the same position, clamped to the track's bounds. Static artwork is unchanged; live/unseekable tracks do not offer seeking. Video restarts its FFmpeg stream at the selected position, so network video can take time to catch up.

### Block-display visualizer

Open **Visualizer settings** in the jukebox controls and choose **Enable visualizer** to show sixteen colored frequency bars above the jukebox. The bars scale vertically with client interpolation, independently of map screens, at up to twenty updates per second. Choose **Disable visualizer** to remove them. The setting carries across queued tracks and is included in saved jukebox settings. The settings dialog moves the bars north/south/east/west/up/down in quarter-block steps and adjusts width and height independently in 0.25× steps (0.25×–4×). Position offsets are limited to ±16 blocks. Reset restores the default position and size. Changes appear live and are retained in saved jukebox settings.

Audio analysis uses one voice backend's PCM samples without altering the audio. Analysis stops when nobody is within 32 blocks, and paused playback lets bars settle. Enabling analysis can require decoding audio that would otherwise pass through as Opus. Bars are removed on playback end, chunk unload, or plugin shutdown. Requires Minecraft 1.19.4 or newer. CPU and packet costs should be profiled with your expected number of simultaneous jukeboxes.

#### Multiple visualizers and speaker drivers

The Visualizer settings dialog supports up to eight independent visualizers per jukebox. Add **Frequency bars** (16 entities) or **Speaker driver** (three square layers with a fixed surround and a diaphragm that pushes outward on bass beats). New visualizers start enabled. Select a visualizer by its numbered button before moving, resizing, enabling/disabling, changing its type, or removing it. Each instance can face north, south, east, or west. Position offsets are world directions; facing rotates the visualizer around its own base. Settings are retained in saved jukebox settings. All instances share the same audio analysis; additional instances add entity-update traffic.

#### Linked lights

Place a solid light-emitting block (such as a redstone lamp, copper bulb, shroomlight, froglight, glowstone, redstone block, or beacon), open the jukebox controls, choose **Lights**, then choose a response (**Bass**, **Mids**, **Highs**, **Deep bass**, **Low mids**, **Vocal range**, **Sparkle**, or **Full spectrum**), then right-click the lamp within two minutes. The lamp must be within 32 blocks of that jukebox. Multiple lamps can link to one jukebox; each lamp has one source. To unlink, choose **Unlink light** and click the lamp. Linking respects cancelled block interactions and requires the playback control/play permissions.

These are ordinary player-placed blocks with actual world lighting. Blocks with a native lit state use it; always-lit blocks temporarily become black concrete while off. Redstone blocks supply power only while on. Beacons stay in place: a black-concrete shutter in the empty space directly above them blocks the beam while off and retracts while on. Leave that space empty when linking a beacon. Beacons still need a valid base and a clear beam path. Shutters are removed when their link is released, the chunk unloads, or the plugin stops. Their original block data is saved and restored when unlinked, unloaded, moved by a piston, broken, or on plugin shutdown. The selected frequency band turns each light on; pausing, stopping, unloading, and plugin shutdown reset them while respecting normal redstone power. Breaking a temporarily darkened block restores its original material before drops are calculated. Breaking the lamp or jukebox removes its link. Links and band selections are saved in `bass_lamp_links.json` across restarts. Existing links default to bass. Choose another band and click an already linked lamp to change its response. Lamps use the shared audio analysis independently of the visualizer settings menu.

Configure the shared light release in `discs.toml`:

```toml
light_falloff = 0.2
```

This is the fraction of the previous flash retained per lighting update (normally 50 ms).
The default `0.2` preserves the crisp response; try `0.8` for longer flashes. `0` drops the
previous pulse immediately, while `1` holds a triggered peak until playback resets.
Values are clamped to `0..1`; non-finite values use the default. Run `/disc reload` to apply
changes to all existing linked lights and beacon shutters. Frequency-band selection and
immediate reset on pause/stop are unchanged. This setting is shared, not per light.

### Carrying jukebox settings

Mining a jukebox saves its volume, loop/shuffle toggles, stereo channel assignment, crossfade duration/toggle, hologram offset, visualizer instances (type, facing, size, position and enable state), and physical links on the dropped jukebox item. Its lore reads **Saved jukebox settings**. Place that item to restore the setup. Ordinary disc drops are unchanged; playback and queue contents do not travel with the jukebox.

Creative-mode breaks also drop a jukebox carrying its saved settings. Placed jukeboxes also save settings on chunk unload and plugin shutdown and restore them when loaded. Breaking clears the old location's settings. Lamp links reconnect only to available lamps in the same world within 32 blocks, and screen links reconnect only when the original screen exists and has not been linked elsewhere. Lamps and screen frames themselves stay where they were built. Cancelled breaks and placements do not transfer settings.

### Moving holograms in the world

Hold a **stick**, look at a jukebox or its chest within six blocks, and run `/disc hologram` (or `/disc hologram edit`). A preview appears even when no music is playing.

- **Right-click** to grab or place the hologram. Look around or walk to move it.
- **Scroll** while grabbed to move it closer or farther away; **sneak** for quarter-block grid snapping.
- **Sneak-right-click** or `/disc hologram done` finishes and saves. Switching items, leaving the area, disconnecting, or five minutes of inactivity also saves and ends editing.

The jukebox controls offer **Move hologram in world**, and visualizer settings offer **Move / resize in world** for the selected visualizer. Hold a stick before choosing either action. Directional movement and size buttons have been replaced by these tools.

Holograms and visualizers share the same stick controls and allow one editor per jukebox. Hologram dragging stays within 24 blocks of its default position. Existing playback/control permissions apply.

### Editing visualizers in the world

Hold a **stick**, look at a jukebox (or its chest) within six blocks, and run `/disc visualizer edit`. The selected visualizer glows, and visualizers remain visible as previews even when music is stopped or they are disabled. Editing requires the existing playback and control permissions. One player can edit a jukebox at a time.

- **Right-click** to grab/release the selected visualizer; look around or walk to move it. Scroll while grabbed to bring it closer or farther away. Sneak while moving for quarter-block steps.
- **Left-click** to select a visualizer you are looking at. Sneak-left-click cycles through them.
- **Scroll** while released to resize both dimensions. **Sneak-scroll** changes width independently.
- **Swap-hand key (F by default)** rotates the selected visualizer 90 degrees.
- `/disc visualizer add bars|speaker`, `remove`, `next`, `type`, `toggle`, `reset`, and `rotate` manage the selected visualizer without opening a menu.
- **Sneak-right-click** or `/disc visualizer done` finishes and keeps your changes. Selecting another item, leaving the area/world, losing permission, disconnecting, or five minutes of inactivity also ends editing. Previewing a disabled visualizer does not enable it; use `toggle` to change its playback visibility.

The existing position and size limits still apply, and edits travel with saved jukebox settings.


### Physical stereo speakers

Use **Link left note block** and **Link right note block** in the jukebox controls to select two distinct note blocks within 32 blocks in the same world. Linking either channel selects **Note block L / Note block R** stereo mode. In this mode, only the two note blocks emit audio; the jukebox remains silent. Both links are saved with jukebox settings. Both speakers must be linked and available before playback can start. Removing either active speaker stops playback. Changes apply on the next song or after restarting playback.

Use **Link note block** in the jukebox controls, then right-click a placed note block within 32 blocks in the same world. The link is saved with jukebox settings. **Unlink note block** restores automatic adjacent detection. Links take priority over adjacent blocks; an unavailable linked speaker falls back to normal jukebox playback. You can also place a note block directly beside the jukebox (north, east, south, or west). The note block automatically plays the original left channel and the jukebox plays the original right channel. Use the Stereo control to swap them or turn the split off. Channel assignment and speaker placement are read at the start of each song; changes take effect on the next song or after restarting playback. Without a note block, normal jukebox playback is used. If several note blocks touch the jukebox, the first in north/east/south/west order is selected. Mono recordings play from both speakers. Removing either active speaker stops playback. Both Plasmo Voice and Simple Voice Chat use the same channel assignment and shared pause, seek, volume, and crossfade controls. Stereo speakers share one decoder and one 20 ms send clock: each audio frame is split into left and right, then both voice backends receive the same encoded frame pair. Buffering stalls both channels together instead of allowing them to drift independently.

### Jellyfin media library

Each player can connect their own Jellyfin server/account, browse its media, and burn a selected item onto a held disc:

1. Run `/disc jellyfin` or `/disc jellyfin connect [server-url]`. Include any reverse-proxy base path, for example `https://media.example/jellyfin`.
2. Follow the private chat prompts for URL, username, and password. Use `<empty>` for a passwordless account, or `cancel` to exit. Prompts expire after two minutes. Do not put your password in a command. The plugin cancels/redacts these chat events; server owners should account for other chat/logging plugins when enabling credential input.
3. Hold one burnable music disc in your main hand and select a song in the menu. Use Previous/Next to page through music, or Search to filter songs/artists. Existing burn permissions and burnable-disc checks still apply.

`/disc jellyfin browse` reopens the menu; `/disc jellyfin search <text>` opens filtered results. `/disc jellyfin cancel` cancels pending input. `/disc jellyfin disconnect` removes the saved token and requests logout from Jellyfin. Reconnecting to the same URL/account restores playback of its existing discs. Connecting a different URL/account will not give old discs access to the new library.

Requires `pv.addon.discs.jellyfin` (or `vc.addon.discs.jellyfin`, operator by default) and the existing burn permission. Normal play permissions still apply to using discs. Jellyfin must be reachable from the Minecraft server, and the Jellyfin user must have access to the selected media. The URL may point to a local/self-hosted server; HTTPS is recommended for remote servers. Use the final URL because authenticated requests do not follow redirects.

Passwords are exchanged for Jellyfin user tokens and are not saved. Connection tokens and selected-media grants are stored server-side in `plugins/vc-addon-discs/jellyfin.json`; keep that file private. Disc items contain only random `jellyfin:` references, never URLs, passwords, or tokens. A burned disc can be shared and played by other players using its owner's saved connection, including while the owner is offline and after server restarts. Unselected library items cannot be retrieved through the disc reference. Tokens expire if revoked by Jellyfin; reconnect when needed.

Jellyfin songs support chest queues, loop/shuffle, crossfading, pause, seek, volume, and physical stereo through both voice backends. Connection failures preserve the disc reference and stop its queue rather than erasing the song or repeatedly retrying a broken login. The browser includes music, audiobooks, movies, episodes, music videos, and home videos. Audio items stream their original supported files; video items request a stereo MP3 audio stream from Jellyfin and require audio transcoding permission. Video items also play on linked item-frame screens using server-side FFmpeg. The original video streams through an authenticated local proxy; tokens are never passed to FFmpeg or placed in URLs. The Jellyfin user must have permission to play the original video. Progressive video audio transcodes do not support seeking; original audio files remain seekable. Jellyfin stream reads allow up to 60 seconds for transcoding startup.


### Skip crossfade and AutoMix

The queue now keeps the current song playing while a skipped-to song loads, then blends the two with an equal-power fade. Skip and selecting another queue track use the configured crossfade duration; a zero-second duration still switches immediately. Skipping the last song fades it to silence when loop is off. Rapid skips retain at most two playing songs, and stopping cancels the blend and any preparation. Pause and buffering freeze the fade because its clock counts transmitted audio rather than wall time or the seek position.

Open the jukebox's **Mixing** and enable **AutoMix** for adaptive transitions. AutoMix is off by default and is saved with the jukebox. It analyzes a bounded window of the current song and up to eight seconds of the next intro, detects confident rhythmic matches, chooses beat-aligned entry/blend timing for similar tempos, trims detected intro silence, and attenuates a louder incoming track during the overlap. Uncertain or mismatched rhythms use a short equal-power fade. The incoming track returns gradually to the chosen listening volume after the blend. AutoMix uses a two-second blend for manual skips and overrides the fixed crossfade duration while enabled.

This is a lightweight audio-analysis mixer using Java Sonic DSP. For confident close-tempo matches, it time-stretches the incoming track by at most ±8% while preserving its pitch, then eases back to the chosen tempo over four seconds after the overlap. Uncertain matches do not change tempo. It does not use Apple's algorithms. Live or non-seekable sources use fade-only transitions; track-loading delays can still leave a gap if the outgoing song ends before the next source can buffer. Analysis runs only with AutoMix enabled, keeps a sixteen-second energy window, and prepares only one next track per queue. Shuffle, loop, live chest edits, both voice backends, stereo speakers, and existing audio effects remain supported. An outgoing track finishing its fade no longer stops the incoming track's linked screen.

#### Independent tempo and pitch

In **Audio effects**, use Tempo ±5%, Pitch ±1 semitone, or Reset tempo & pitch. `/disc effect tempo <0.5-2.0>` changes duration while preserving pitch; `/disc effect pitch <-12 to 12>` transposes by semitones while preserving duration. These settings are saved with the jukebox. Existing Nightcore/Vaporwave effects still change playback rate and pitch together, and Clear effects resets all three.

AutoMix's temporary tempo multiplier is separate from saved effects. It holds the matched tempo during the overlap and smoothly releases it afterward using the transmitted-audio clock, so pause/buffering freeze recovery. Stereo channels pass through the same processor. The media-time clock accounts for tempo/rate changes for queue timing and linked screens. The DSP is bundled Java code and requires no separate native audio installation; large manual changes can sound less natural than the small changes used by AutoMix.

Subtitles for Jellyfin videos appear as a fixed TextDisplay over the lower part of the item-frame screen (Paper 1.19.4+). Open **Screen → Subtitles** to turn captions on/off, use automatic selection, or choose a language/SDH/forced track. Track changes do not restart playback. Text-based subtitles are converted by Jellyfin to SRT; image subtitles such as PGS/DVD require a text alternative.

### Spotify and Apple Music playlists

Use `/disc playlist <playlist-share-link> [shuffle|reverse] [overwrite]`, then right-click a chest above a jukebox with blank discs as usual. Spotify playlist URLs (including regional links and `spotify:playlist:ID`) and public Apple Music catalog playlist URLs are supported. Imports preserve order and duplicates and skip podcasts, local files, and unavailable catalog entries. Optional API imports follow all pages up to 1,000 songs. Spotify and Apple Music commands initially load only song metadata. Right-clicking the chest matches only as many songs as there are eligible blank discs, after applying shuffle or reverse. Already burned discs are preserved by default. Add `overwrite` to include them, for example `/disc playlist <link> shuffle overwrite`; changes to the selected blank slots during matching require a retry. Each selected song is searched on YouTube and the first result supplies the playable disc URL; matches are best effort and may be a different recording. YouTube playback must already work on the server. No subscription audio or previews are streamed from either catalog. A failed match aborts the import before any discs are changed.

Public playlist imports work by default without API tokens or a Lavalink server. They read the metadata on Spotify’s public embed and Apple Music’s public webpage. These unofficial webpage formats can change. Spotify’s embed exposes at most 100 entries; the command warns when this limit is reached and imports only that preview. Apple imports the songs exposed by its public page and warns if the page advertises more songs than it supplies. Private or region-blocked playlists cannot be read anonymously.

For full API pagination, optionally configure these in `discs.toml` and run `/disc reload`:

```toml
[playlist_sources]
spotify_access_token = ""
apple_music_developer_token = ""
```

Alternatively set `SPOTIFY_ACCESS_TOKEN` and/or `APPLE_MUSIC_DEVELOPER_TOKEN` in the server environment before starting it; nonempty environment values take precedence. Keep credentials server-side. The optional Spotify API mode requires a **user OAuth access token**, with playlist read permissions for the selected playlist; client-credentials tokens do not grant user playlist access. API app mode and playlist ownership/access restrictions still apply. Access tokens expire: replace the value and reload when necessary; automatic OAuth login and refresh are not included. See [Spotify authorization](https://developer.spotify.com/documentation/web-api/tutorials/code-flow) and [playlist item access](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items). The optional Apple Music API mode requires a developer JWT from a MusicKit-enabled developer account; use a country-specific public catalog playlist link such as `https://music.apple.com/ca/playlist/name/pl.ID`. Private library playlists are not supported. See [Apple Music developer tokens](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens). Expired tokens, inaccessible playlists, and API rate limits produce a setup/access error instead of silently importing an incomplete page.

### Manage holograms

Open jukebox controls → **Manage holograms** to toggle the main playback hologram and queue hologram independently. The queue display sits above the playback display and shows up to five tracks in chest order, marking the current song and counting remaining entries. Shuffle may play tracks in a different order. Queue displays appear only while a chest queue is playing. Use **Move main hologram** or **Move queue hologram** to position them independently. Each also has its own reset button. Visibility choices are saved per jukebox across restarts and carried with saved jukebox items.

Playback pauses when its chunk unloads. Within the same server session, reloading the chunk resumes the inserted disc or current chest-queue song at its saved position, retaining pause state even with looping disabled. If a queued disc moved to another chest slot, it is located again before resuming; a removed or replaced disc is not resumed. Non-seekable streams reconnect without a saved seek position. Resume snapshots are kept in memory and do not survive a server restart or plugin reload.

When AutoMix is enabled for a chest queue, the playback hologram and controls show its current analysis status. A sparkle means a confident beat-compatible transition is ready; an uncertain beat, incompatible tempos, unavailable intro analysis, or failed preload is explained explicitly. AutoMix still uses a smooth fade when it cannot beat-match. Enable it in **Mixing → Enable AutoMix**; it is off by default. Enabling it after a normal preload reruns that preload with intro analysis. A next-song load/decoder failure or metadata load timeout erases that disc’s burn metadata and selects the following song while current playback continues. An absent or uncertain beat estimate alone does not erase discs.

For an uncertain next-song intro, AutoMix continues scanning eight-second sections up to 60 seconds into the song, with a 20-second wall-time budget. A confident later beat can supply a cue past the slow intro when tempos match; otherwise the transition retains the original intro and uses a smooth fade. Later cues leave enough audio for the incoming fade.

The current song also keeps listening past uncertain sections: after eight seconds without a reliable beat, live analysis starts a fresh window. Later rhythm can enable a beat match without seeking or interrupting the current song. Confident estimates retain the normal rolling window and tempo stabilization.

Confident AutoMix matches now use a 16-beat overlap (bounded to 16 seconds and shorter for short tracks), beat-aligned incoming cues, and a smooth equal-power volume curve. During the middle 30% of a matched overlap, a smoothed low-shelf cut hands bass from the outgoing song to the incoming song. User EQ settings remain separate and the incoming bass returns fully after the transition. Uncertain matches retain short fades without bass cuts. This is beat-based mixing, not harmonic-key or musical-phrase detection.

AutoMix compares all analyzed eight-second sections in the next song’s first minute, even when its intro already has a beat. It ranks compatible sections by beat confidence, required tempo adjustment, and energy similarity, with a preference for earlier cues. A later cue is selected only for a meaningful improvement and must leave enough time for the overlap. If nothing matches, the original intro remains the fallback.

Seeking within the current song preserves an already announced AutoMix transition and next-song cue. Live beat analysis still clears stale samples after seeking, but its temporary uncertainty does not discard the committed plan. Changing the song pair or speed/tempo, or disabling AutoMix, can reset the plan.

Song playback conservatively trims digital silence from the beginning and ending of seekable, finite tracks. Detection uses a separate unmodified decoder, examines at most 15 seconds at each edge with an eight-second total scan budget, and caches up to 256 track results. Only all-zero decoded PCM frames count as completely silent; quiet music and internal pauses remain intact. Incomplete tail scans leave the original ending unchanged, and live streams are not scanned. Timeline positions retain original song timestamps; the playable ending and seek bounds reflect detected trimming.

Beat-matched transitions now gradually move both songs toward a shared tempo during the first 35% of the overlap, retaining their matched beat-rate ratio. The faster song slows and the slower song speeds up, with each automatic adjustment bounded to ±8% and pitch preserved. The shared tempo holds through the bass handover; after the overlap, the incoming song eases back to its configured tempo over four seconds. Uncertain matches do not apply this tempo ramp.

Crossfade envelopes bring the incoming track up early and hold both tracks at equal-power blend levels through the middle before fading out the outgoing track. Beat-matched transitions hold that overlap across the middle 40% (including the bass handover); ordinary fades hold it across the middle 20%. Existing per-track loudness attenuation still applies.

AutoMix may mix out before the ending during the last third of a song, bounded to its last 45 seconds. It requires strong beat estimates, a close tempo match, and similar energy for the selected next-song section. It schedules four beats ahead, leaves room for the full blend, and must finish at least four seconds before the original transition point. The ready status shows **mix out at** for an earlier exit; that decision remains committed.

Low-pass and high-pass filters are available in **Audio effects** as cutoff-cycle buttons, or with `/disc effect lowpass <20-20000|off>` and `/disc effect highpass <20-20000|off>` (cutoffs in Hz). Low-pass removes treble; high-pass removes bass. Both can be combined and are saved with jukebox settings. Beat-matched AutoMix transitions also sweep an incoming high-pass open and an outgoing low-pass closed. Temporary sweeps reset after the transition and preserve the manually selected cutoffs. Unmatched fades do not apply automatic sweeps.

## Handheld jukeboxes

Craft a portable jukebox with seven copper nuggets, one amethyst shard, and one jukebox:

```text
Copper Nugget | Jukebox       | Copper Nugget
Copper Nugget | Amethyst Shard | Copper Nugget
Copper Nugget | Copper Nugget | Copper Nugget
```

The crafted item is an amethyst shard named **Handheld Jukebox**. Right-click it to open the same playback dialog style as a placed jukebox. Choose **Open Queue** to manage its own track inventory.
The queue is a plain **18-slot chest inventory** containing real burned music discs. Put discs
in, take them out, shift-click, use hotbar keys, or drag them just as in a chest. Adding a
disc moves it out of your inventory; taking it back returns the same item with its original
material and metadata. There are no queue buttons or placeholder discs. Playback follows
slot order. Removing the currently playing disc stops playback; moving it to another slot
preserves the selection. Close the chest and right-click the device again to return to controls.

The playback dialog provides play/pause, skip, stop, seeking, loop/shuffle, volume, and the
same audio-effects and equalizer menus as a placed jukebox. Volume, queue modes, and effects
are saved on the device. World links belong to placed jukeboxes.

Keep the device **anywhere in your player inventory**, including the offhand: audio follows
the holder. Dropping it, putting it in an external container, dying, or disconnecting stops
playback. The device cannot be moved or dropped while its queue is open; close the queue
first. Closing the interface leaves music playing. Goat horns keep their separate playback
state and duration rules. Nearby listeners use the installed Plasmo Voice or Simple Voice
Chat integration and the configured horn listening distance.

Older queues only stored copied track references. On first opening the new chest, those
entries become burned Cat discs because the original disc materials were not recorded.
Entries exceeding 18 are returned to your inventory, or dropped at your position if full.
Newly stored discs always preserve their original items.

Queues, volume, and selected track are saved on each item and travel with it; playback
does not automatically resume after reconnecting or restarting. Separately crafted devices
receive independent identities on first use. Separate stacked unused devices before using
them. Crafting is available only on Minecraft versions with copper nuggets and amethyst shards.

Provider sign-in, OAuth, CAPTCHA/bot-verification, and rate-limit errors preserve the disc's burn metadata, including failures during next-song preloading. A chest queue stops on an authentication failure rather than repeatedly retrying or erasing its discs; retry playback after fixing the provider connection. Other unavailable-media failures retain the existing skip/erase behavior.
