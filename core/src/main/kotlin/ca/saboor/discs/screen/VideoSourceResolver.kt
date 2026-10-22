package ca.saboor.discs.screen

import ca.saboor.discs.audio.QualityYoutubeSourceManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager

object VideoSourceResolver {

    private val YOUTUBE_VIDEO_ID_REGEX = Pattern.compile(
        "(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})"
    )

    fun extractYouTubeVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.matches(Regex("^[\\w-]{11}$"))) {
            return trimmed
        }
        val matcher = YOUTUBE_VIDEO_ID_REGEX.matcher(trimmed)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun isYouTubeUrl(url: String): Boolean {
        return extractYouTubeVideoId(url) != null || url.contains("youtube.com") || url.contains("youtu.be")
    }

    fun getYouTubeThumbnailUrl(videoId: String): String {
        return "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
    }

    private val logger = org.slf4j.LoggerFactory.getLogger("VideoSourceResolver")

    private val VIDEO_ITAGS = setOf(
        18, 22, 37, 38, // muxed mp4
        133, 134, 135, 136, 137, 160, 298, 299, // video-only mp4
        242, 243, 244, 247, 248, 278, // video-only webm
        394, 395, 396, 397, 398, 399 // video-only AV1
    )

    /**
     * Resolves a media track identifier to a playable video stream URL or input source for FFmpeg.
     */
    fun resolveVideoUrl(
        identifier: String,
        audioPlayerManager: PlasmoAudioPlayerManager? = null,
        ytdlpPath: String = "yt-dlp",
        artworkUrl: String? = null,
        isYouTubeMusic: Boolean = false,
        highResolution: Boolean = false,
    ): String {
        val trimmed = identifier.trim()
        val videoId = extractYouTubeVideoId(trimmed)
        val isYt = videoId != null || isYouTubeUrl(trimmed)
        val targetId = videoId ?: extractYouTubeVideoId(trimmed)

        if (isYt) {
            // Option 1: Try yt-dlp if installed on server
            val ytdlpResolved = tryResolveWithYtDlp(trimmed, ytdlpPath, highResolution)
            if (ytdlpResolved != null) {
                logger.info("Resolved video stream via yt-dlp for '$trimmed'")
                return ytdlpResolved
            }

            // Option 2: Try resolving via Lavaplayer's YouTube source formats
            if (targetId != null && audioPlayerManager != null) {
                val lpResolved = tryResolveWithLavaPlayer(targetId, audioPlayerManager, highResolution)
                if (lpResolved != null) return lpResolved
            }

            // Fallback for YouTube when no video stream is available: show album cover / thumbnail
            logger.info("No direct video stream found for YouTube ID '$targetId'; using thumbnail/artwork.")
            if (!artworkUrl.isNullOrBlank()) return artworkUrl
            if (targetId != null) return getYouTubeThumbnailUrl(targetId)
        }

        // Direct URL, file path, or stream
        return trimmed
    }

    private fun tryResolveWithYtDlp(url: String, ytdlpPath: String, highResolution: Boolean): String? {
        return try {
            val process = ProcessBuilder(
                ytdlpPath,
                "-g",
                "-f",
                if (highResolution) "bestvideo[ext=mp4]/bestvideo/best" else "best[ext=mp4]/bestvideo[ext=mp4]+bestaudio/best",
                "--no-playlist",
                url
            ).redirectErrorStream(true).start()

            val exited = process.waitFor(8, TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val firstLine = reader.readLine()?.trim()
                if (!firstLine.isNullOrBlank() && (firstLine.startsWith("http://") || firstLine.startsWith("https://"))) {
                    return firstLine
                }
            } else {
                process.destroyForcibly()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryResolveWithLavaPlayer(videoId: String, audioPlayerManager: PlasmoAudioPlayerManager, highResolution: Boolean): String? {
        val ytSource = audioPlayerManager.getYoutubeSourceManager() ?: return null
        return try {
            val http = ytSource.`interface`

            http.use {
                for (client in ytSource.clients) {
                    if (!client.supportsFormatLoading()) continue
                    try {
                        http.context.setAttribute("yt-oauth-enabled-client", client.supportsOAuth())
                        val trackFormats = client.loadFormats(ytSource, http, videoId) ?: continue
                        val formats = trackFormats.formats
                        if (formats.isEmpty()) continue

                        // Look for a format with video
                        val videoFormats = formats.filter {
                            val mime = it.type?.mimeType ?: it.info?.mimeType ?: ""
                            mime.startsWith("video/") || it.itag in VIDEO_ITAGS
                        }

                        if (videoFormats.isEmpty()) continue

                        val selected = (if (highResolution) videoFormats.maxByOrNull { it.bitrate } else videoFormats.filter { (it.type?.mimeType ?: "").contains("mp4") }
                            .minByOrNull { kotlin.math.abs(it.bitrate - 1_500_000) }
                            ?: videoFormats.minByOrNull { kotlin.math.abs(it.bitrate - 1_500_000) }
                            ?: videoFormats.firstOrNull())
                            ?: continue

                        var finalUri = selected.url
                        if (trackFormats.playerScriptUrl != null && client.requirePlayerScript()) {
                            try {
                                finalUri = ytSource.cipherManager.resolveFormatUrl(http, trackFormats.playerScriptUrl, selected)
                            } catch (e: Exception) {
                                logger.debug("Cipher resolution failed for video format: ${e.message}")
                            }
                        }
                        if (finalUri != null) {
                            val transformed = client.transformPlaybackUri(selected.url ?: finalUri, finalUri)
                            logger.info("Resolved YouTube video stream for '$videoId' using client '${client.identifier}' (itag ${selected.itag})")
                            return transformed.toString()
                        }
                    } catch (e: Exception) {
                        logger.debug("Client '${client.identifier}' failed to load formats for video '$videoId': ${e.message}")
                    }
                }
            }
            null
        } catch (e: Exception) {
            logger.warn("Failed to resolve video stream from Lavaplayer for '$videoId': ${e.message}")
            null
        }
    }
}

