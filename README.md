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

- Place a chest (single or double) directly above a jukebox and put music discs inside. Sneak-right-click the chest to open it.
- Playlist burning skips non-disc items, stacks, and discs that fail the configured burnable-tag requirement. One disc receives one track; extra tracks are left unburned when discs run out. Existing burned discs are overwritten.
- Right-click the chest to play all its burned discs in slot order, including any existing burned discs. Discs remain in the chest and are never consumed or moved into the jukebox.
- Tracks crossfade over three seconds by default. Open Crossfade settings from the jukebox dialog and use its -1s/+1s buttons to adjust it from 0–30 seconds, or Enable/Disable crossfade to toggle it. The dialog shows the current setting. Changes apply to upcoming transitions in that queue; an ongoing fade finishes at its original duration. Each new queue starts at three seconds. Live streams use normal transitions. Pause and volume affect both tracks during a fade; skipping or stopping cancels the transition. Network loading delays can shorten the overlap.
- Playback uses a snapshot of the discs when the queue starts. Chest edits take effect on the next queue. The queue plays once; click the chest again to replay it.
- Right-click the jukebox to open playback controls; choose Stop playback to stop. Breaking the chest or jukebox, unloading the jukebox chunk, or restarting the server stops the queue. Queues do not resume automatically.
- Normal chest playback needs only `pv.addon.discs.play`. Protection plugins can deny chest interactions to prevent burning and playback.

`/disc search <query>` – Search tracks on YouTube.

`/disc screen create [name]` – Start interactive screen creation. Click two diagonally opposite corner item frames on a wall to define the screen grid, then click a jukebox to link it.

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

- Form a rectangular wall grid of item frames (e.g. 2x2, 3x2, 4x3) and run `/disc screen create [name]`.
- Right-click the first corner item frame, then right-click the opposite corner item frame. The plugin automatically detects the grid dimensions, facing direction, and orders the tiles.
- Right-click a nearby jukebox to link the screen to it.
- Whenever a custom music disc or chest queue track plays in that jukebox, the music video streams to the item frames in real time with aspect ratio preservation and black letterboxing.
- Pausing, resuming, or seeking using the jukebox controls automatically keeps the music video in sync.
- Item frames belonging to screens are automatically fixed and protected from rotation or removal.

`/disc cancel [player]` – Cancel user's goat horn playback. You need `pv.addon.discs.cancel.other` permission to cancel for others.

`/disc hologram [gui|move|offset|reset]` – Adjust the position of the playback hologram for a jukebox. Can be opened interactively from the Jukebox controls dialog ("Move Hologram") or run as a command. Supports `/disc hologram move <up|down|north|south|east|west> [amount]`, `/disc hologram offset <x> <y> <z>`, and `/disc hologram reset`.

`/disc oauth [force|status]` – Authorize YouTube access via Google device OAuth2 to prevent "Sign in to confirm you're not a bot" or "This video requires login" errors. Check server console for the pairing code, visit https://www.google.com/device with a burner account, and authorize. Use `/disc oauth force` to re-authorize and refresh the stored token.

`/disc reload` – Reload runtime options from `discs.toml` without interrupting playback. Works from the console.

Reloadable options: debug logging, disc glint and burn/erase lore, mono audio, goat horn settings, jukebox distance and visualization, HTTP whitelist, screen settings (fps, distance, ffmpeg path), and the burnable-tag requirement. Playback settings apply to newly started tracks; item settings apply to future burns/erases. Registered recipe results keep their original glint and lore until restart.

Source line weight/default volume, HTTP proxy, YouTube settings, local source settings, and default recipe settings require a server restart. Reload leaves their active values unchanged and keeps the edited values in the config file for the next restart.

## Playback controls

Right-click a jukebox with a custom disc or an active chest queue to open a native Paper dialog, following Lumistones' multi-action dialog pattern. The click opens controls instead of ejecting the disc.

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
