package su.plo.voice.discs.screen

import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.discs.audio.QualityYoutubeSourceManager
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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

    /**
     * Resolves a media track identifier to a playable video stream URL or input source for FFmpeg.
     */
    fun resolveVideoUrl(
        identifier: String,
        audioPlayerManager: PlasmoAudioPlayerManager? = null,
        ytdlpPath: String = "yt-dlp",
        artworkUrl: String? = null,
        isYouTubeMusic: Boolean = false,
    ): String {
        val trimmed = identifier.trim()
        val videoId = extractYouTubeVideoId(trimmed)
        val isYt = videoId != null || isYouTubeUrl(trimmed)
        val targetId = videoId ?: extractYouTubeVideoId(trimmed)

        // For YouTube Music tracks (no music video, only album cover), directly return album art
        if (isYouTubeMusic || trimmed.contains("music.youtube.com")) {
            if (!artworkUrl.isNullOrBlank()) return artworkUrl
            if (targetId != null) return getYouTubeThumbnailUrl(targetId)
        }

        if (isYt) {
            // Option 1: Try yt-dlp if installed on server
            val ytdlpResolved = tryResolveWithYtDlp(trimmed, ytdlpPath)
            if (ytdlpResolved != null) return ytdlpResolved

            // Option 2: Try resolving via Lavaplayer's YouTube source formats
            if (targetId != null && audioPlayerManager != null) {
                val lpResolved = tryResolveWithLavaPlayer(targetId, audioPlayerManager)
                if (lpResolved != null) return lpResolved
            }

            // Fallback for YouTube when no video stream is available: show album cover / thumbnail
            if (!artworkUrl.isNullOrBlank()) return artworkUrl
            if (targetId != null) return getYouTubeThumbnailUrl(targetId)
        }

        // Direct URL, file path, or stream
        return trimmed
    }

    private fun tryResolveWithYtDlp(url: String, ytdlpPath: String): String? {
        return try {
            val process = ProcessBuilder(
                ytdlpPath,
                "-g",
                "-f",
                "best[ext=mp4]/bestvideo[ext=mp4]+bestaudio/best",
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

    private fun tryResolveWithLavaPlayer(videoId: String, audioPlayerManager: PlasmoAudioPlayerManager): String? {
        return try {
            val ytSource = audioPlayerManager.getYoutubeSourceManager() ?: return null
            val http = ytSource.`interface`

            http.use {
                for (client in ytSource.clients) {
                    if (!client.supportsFormatLoading()) continue
                    try {
                        val trackFormats = client.loadFormats(ytSource, http, videoId) ?: continue
                        val formats = trackFormats.formats
                        if (formats.isEmpty()) continue

                        // Look for a format with video
                        val videoFormats = formats.filter {
                            val mime = it.type?.mimeType ?: it.info?.mimeType ?: ""
                            mime.startsWith("video/")
                        }

                        val selected = videoFormats.filter { (it.type?.mimeType ?: "").contains("mp4") }
                            .minByOrNull { kotlin.math.abs(it.bitrate - 1_500_000) }
                            ?: videoFormats.minByOrNull { kotlin.math.abs(it.bitrate - 1_500_000) }
                            ?: videoFormats.firstOrNull()
                            ?: formats.firstOrNull { it.url != null }
                            ?: continue

                        var finalUri = selected.url
                        if (trackFormats.playerScriptUrl != null) {
                            try {
                                finalUri = ytSource.cipherManager.resolveFormatUrl(http, trackFormats.playerScriptUrl, selected)
                            } catch (_: Exception) {
                                // Fallback to selected.url
                            }
                        }
                        if (finalUri != null) {
                            val transformed = client.transformPlaybackUri(selected.url, finalUri)
                            return transformed.toString()
                        }
                    } catch (_: Exception) {
                        // Continue to next client
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

