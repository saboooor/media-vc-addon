package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoSourceResolverTest {

    @Test
    fun `extracts YouTube video ID from various URL formats`() {
        val standard = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(standard))

        val short = "https://youtu.be/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(short))

        val embed = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(embed))

        val timestamp = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(timestamp))

        val shorts = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(shorts))

        val directId = "dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", VideoSourceResolver.extractYouTubeVideoId(directId))
        assertTrue(VideoSourceResolver.isYouTubeUrl(directId))
    }

    @Test
    fun `identifies youtube vs direct video urls`() {
        assertTrue(VideoSourceResolver.isYouTubeUrl("https://www.youtube.com/watch?v=test1234567"))
        assertTrue(VideoSourceResolver.isYouTubeUrl("https://youtu.be/test1234567"))
        assertFalse(VideoSourceResolver.isYouTubeUrl("https://example.com/video.mp4"))
        assertFalse(VideoSourceResolver.isYouTubeUrl("http://localhost:8080/stream.m3u8"))
    }

    @Test
    fun `resolves direct video urls unchanged`() {
        val directMp4 = "https://example.com/music_video.mp4"
        assertEquals(directMp4, VideoSourceResolver.resolveVideoUrl(directMp4))

        val directM3u8 = "https://example.com/live/index.m3u8"
        assertEquals(directM3u8, VideoSourceResolver.resolveVideoUrl(directM3u8))
    }

    @Test
    fun `resolves youtube music to album cover`() {
        val ytMusicUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
        val resolved = VideoSourceResolver.resolveVideoUrl(
            identifier = ytMusicUrl,
            isYouTubeMusic = true
        )
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg", resolved)

        val withArtwork = VideoSourceResolver.resolveVideoUrl(
            identifier = ytMusicUrl,
            artworkUrl = "https://lh3.googleusercontent.com/album_art.jpg",
            isYouTubeMusic = true
        )
        assertEquals("https://lh3.googleusercontent.com/album_art.jpg", withArtwork)
    }

    @Test
    fun `cleans artist name from - Topic suffix`() {
        assertEquals("Queen", "Queen - Topic".replace(Regex("(?i)\\s*-\\s*topic$"), "").trim())
        assertEquals("Daft Punk", "Daft Punk - topic".replace(Regex("(?i)\\s*-\\s*topic$"), "").trim())
        assertEquals("The Beatles", "The Beatles - TOPIC".replace(Regex("(?i)\\s*-\\s*topic$"), "").trim())
        assertEquals("Regular Artist", "Regular Artist".replace(Regex("(?i)\\s*-\\s*topic$"), "").trim())
    }
}

