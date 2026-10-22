package ca.saboor.discs

import ca.saboor.discs.audio.YoutubeSourceQuality
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.StreamFormat
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.TrackFormats
import su.plo.voice.lavaplayer.libs.org.apache.http.entity.ContentType

class YoutubeSourceQualityTest {
    private fun stream(bitrate: Long, defaultAudio: Boolean = true, drc: Boolean = false) = StreamFormat(
        ContentType.parse("audio/webm; codecs=opus"), 251, bitrate, 100_000, 2,
        "https://example.com/audio", null, null, null, defaultAudio, drc)
    private fun formats(vararg streams: StreamFormat) = TrackFormats(streams.toList(), "player.js")

    @Test fun `best available preserves the normal YouTube format choice`() {
        val streams = formats(stream(64_000), stream(128_000), stream(160_000))
        assertSame(streams.bestFormat, YoutubeSourceQuality.BEST.select(streams))
    }

    @Test fun `best chooses highest Opus bitrate regardless of response ordering`() {
        val low = stream(48_000)
        val medium = stream(64_000)
        val high = stream(160_000)
        assertSame(high, YoutubeSourceQuality.BEST.select(formats(low, medium, high)))
        assertSame(high, YoutubeSourceQuality.BEST.select(formats(high, medium, low)))
    }

    @Test fun `upstream codec preference can choose lower bitrate Opus over AAC`() {
        val opus = stream(64_000)
        val aac = StreamFormat(ContentType.parse("audio/mp4; codecs=mp4a.40.2"), 140, 128_000, 100_000, 2,
            "https://example.com/audio", null, null, null, true, false)
        assertSame(opus, YoutubeSourceQuality.BEST.select(formats(aac, opus)))
    }

    @Test fun `balanced and data saver choose streams offered by YouTube within the limit`() {
        val low = stream(48_000)
        val medium = stream(96_000)
        val high = stream(160_000)
        val streams = formats(low, medium, high)
        assertSame(low, YoutubeSourceQuality.LOW.select(streams))
        assertSame(medium, YoutubeSourceQuality.BALANCED.select(streams))
    }

    @Test fun `missing lower quality falls back to the lowest available audio`() {
        val lowest = stream(96_000)
        assertSame(lowest, YoutubeSourceQuality.LOW.select(formats(stream(160_000), lowest)))
    }

    @Test fun `quality selection preserves the default audio language and avoids DRC alternatives`() {
        val normal = stream(96_000)
        val alternativeLanguage = stream(48_000, defaultAudio = false)
        val compressed = stream(48_000, drc = true)
        assertSame(normal, YoutubeSourceQuality.LOW.select(formats(alternativeLanguage, compressed, normal)))
    }
}
