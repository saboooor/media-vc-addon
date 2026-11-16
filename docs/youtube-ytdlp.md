# YouTube through yt-dlp

Set `youtube_source.backend = "yt-dlp"` to replace the Java YouTube source for audio, metadata, searches and playlists. Screen video uses the same yt-dlp executable and config. This backend does not use `.youtube-token`, `/disc oauth`, the Java `po_token` setting or the Java remote cipher. Existing YouTube discs keep working with their saved URLs/IDs. Other providers keep their existing backend.

The recommended external setup is yt-dlp with an automatic PO-token provider. yt-dlp recommends `mweb` with a provider; manually pasted tokens are no longer recommended because tokens can be bound to individual videos. This improves compatibility, but does not guarantee that YouTube will accept a server's IP or session. See the [PO-token guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) and [provider instructions](https://github.com/Brainicism/bgutil-ytdlp-pot-provider).

## Linux setup

Run these as the account that owns the Minecraft server, using paths appropriate for that server:

```sh
python3 -m venv /srv/minecraft/youtube-tools
/srv/minecraft/youtube-tools/bin/python -m pip install -U 'yt-dlp[default]' bgutil-ytdlp-pot-provider
```

Also install a supported JavaScript runtime on the server. Deno is enabled by default; Node requires `--js-runtimes node` in the config below. Follow yt-dlp's [JavaScript runtime and EJS setup guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS). The `default` Python dependency group installs the matching EJS scripts.

Run the provider on the same host (Docker must already be installed):

```sh
docker run --name bgutil-provider -d --init --restart unless-stopped \
  -p 127.0.0.1:4416:4416 brainicism/bgutil-ytdlp-pot-provider
```

Keep this port bound to loopback. The provider has no authentication. If Minecraft itself is containerized, `127.0.0.1` inside that container is different: put both services on a private container network and use the provider's service name in `base_url`, without publishing the provider publicly. The provider repository also documents running it directly with Node/Deno instead of Docker.

Create `/srv/minecraft/youtube-tools/yt-dlp.conf`:

```text
--extractor-args "youtube:player_client=mweb"
--extractor-args "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416"
```

If needed, add `--js-runtimes deno:/absolute/path/to/deno` or `--js-runtimes node:/absolute/path/to/node`. The Java server's service environment must be able to run that runtime. This is an **extraction config**: avoid download/postprocessing, output-template, exec, or JSON-output overrides. The plugin supplies its own JSON, format and timeout options.

Merge these settings into the existing `[youtube_source]` table in `plugins/vc-addon-discs/discs.toml`:

```toml
[youtube_source]
backend = "yt-dlp"
ytdlp_path = "/srv/minecraft/youtube-tools/bin/yt-dlp"
ytdlp_config = "/srv/minecraft/youtube-tools/yt-dlp.conf"
```

Install the rebuilt server plugin and restart Minecraft. Source registration requires a restart. `screen.ytdlp_path` applies to the legacy screen resolver when using the `lavaplayer` backend; the external backend uses `youtube_source.ytdlp_path` for both audio and video. FFmpeg is still required for screen playback.

## Check it independently

Run under the same OS account, container and network as Minecraft, replacing `VIDEO_ID` with a public video:

```sh
/srv/minecraft/youtube-tools/bin/yt-dlp --ignore-config \
  --config-locations /srv/minecraft/youtube-tools/yt-dlp.conf \
  --simulate --no-playlist -f bestaudio -- 'https://www.youtube.com/watch?v=VIDEO_ID'
```

This verifies extraction; to verify media access too, test one small download with yt-dlp. Then try a disc in-game and check for `YouTube uses yt-dlp for audio and screen video` at startup. The addon reports a short error category; diagnose the underlying yt-dlp failure independently rather than posting raw credential-bearing logs. Signed stream URLs and extractor stderr are not included in plugin errors.

## Behavior and maintenance

- Stream URLs are cached for at most five minutes, shortened when the URL advertises an earlier expiry. Concurrent requests for the same stream share extraction. Failures are not cached.
- Each extraction has a 25-second deadline, limited output and at most two concurrent processes. Busy extraction fails promptly instead of starting unbounded processes. Streams are resolved again when an expired cached track or clone starts playback.
- Searches return up to 10 results; playlists import up to 500 entries. Unavailable/private entries without usable video IDs are skipped. `ytmsearch:` uses normal YouTube search with this backend.
- The plugin passes required media HTTP headers from yt-dlp to both audio and video playback. It does not download a permanent music library or copy cookies into its own config.
- Global yt-dlp config files are ignored. Use `ytdlp_config` explicitly for provider, proxy or optional cookies settings. Java `http_proxy` configures audio media requests but does not configure yt-dlp or the screen video proxy. A proxy setup must allow both extraction and the Java/FFmpeg media requests to reach URLs from the same permitted network/session; IP-bound stream URLs may fail otherwise.
- Start without cookies for public content. Account-restricted content may require a private cookie file configured in yt-dlp; OAuth is not supported by yt-dlp. Follow its [cookie instructions](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies). Protect the file and avoid using your primary account.
- Upgrade yt-dlp, its EJS dependencies and the provider plugin together using the pip command above; keep the provider server compatible with its plugin. Updates require no Java library replacement.
- To revert, set `backend = "lavaplayer"` and restart. YouTube blocks/rate limits still preserve burned discs; automatic Java reauthorization is not attempted by the external backend.

Local fixture tests cover extraction, argument handling, timeouts, caching, error redaction and actual audio decoding. Live YouTube behavior depends on the deployment's provider, runtime, account/session and network and must be checked there.
