package su.plo.voice.discs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.plo.voice.discs.utils.extend.cleanArtistName
import su.plo.voice.discs.utils.extend.cleanTrackName
import su.plo.voice.discs.utils.extend.containsArtist
import su.plo.voice.discs.utils.extend.formatTrackDisplay

class TrackDisplayTest {

    @Test
    fun `cleanArtistName strips topic, official, and vevo`() {
        assertEquals("Queen", "Queen - Topic".cleanArtistName())
        assertEquals("Queen", "Queen Official".cleanArtistName())
        assertEquals("Queen", "Queen - Official Channel".cleanArtistName())
        assertEquals("Taylor Swift", "TaylorSwiftVEVO".cleanArtistName())
        assertEquals("Regular Artist", "Regular Artist".cleanArtistName())
    }

    @Test
    fun `containsArtist detects artist in title with word boundaries`() {
        assertTrue("Queen - Bohemian Rhapsody".containsArtist("Queen"))
        assertTrue("Bohemian Rhapsody - Queen".containsArtist("Queen"))
        assertTrue("Queen: Bohemian Rhapsody".containsArtist("Queen"))
        assertTrue("[Queen] Bohemian Rhapsody".containsArtist("Queen"))
        assertTrue("Bohemian Rhapsody (feat. Queen)".containsArtist("Queen"))
        assertTrue("Rick Astley - Never Gonna Give You Up (Official Music Video)".containsArtist("Rick Astley"))
        assertTrue("AC/DC - Back in Black".containsArtist("AC/DC"))
        assertTrue("Panic! At The Disco - High Hopes".containsArtist("Panic! At The Disco"))
        assertTrue("Taylor Swift - Blank Space".containsArtist("TaylorSwiftVEVO"))
        assertTrue("TwentyOnePilots - Stressed Out".containsArtist("Twenty One Pilots"))
        assertTrue("Кино - Пачка сигарет".containsArtist("Кино"))
    }

    @Test
    fun `containsArtist rejects artist as part of another word`() {
        assertFalse("Trust".containsArtist("Us"))
        assertFalse("The Other Side".containsArtist("He"))
        assertFalse("Bohemian Rhapsody".containsArtist("Queen"))
        assertFalse("".containsArtist("Queen"))
        assertFalse("Song".containsArtist(""))
    }

    @Test
    fun `formatTrackDisplay does not show artist if already in title`() {
        assertEquals("Queen - Bohemian Rhapsody", formatTrackDisplay("Queen - Bohemian Rhapsody", "Queen"))
        assertEquals("Rick Astley - Never Gonna Give You Up", formatTrackDisplay("Rick Astley - Never Gonna Give You Up", "Rick Astley"))
        assertEquals("Taylor Swift - Blank Space", formatTrackDisplay("Taylor Swift - Blank Space", "TaylorSwiftVEVO"))
    }

    @Test
    fun `formatTrackDisplay shows artist if not in title`() {
        assertEquals("Queen - Bohemian Rhapsody", formatTrackDisplay("Bohemian Rhapsody", "Queen"))
        assertEquals("The Beatles - Hey Jude", formatTrackDisplay("Hey Jude", "The Beatles - Topic"))
        assertEquals("Us - Trust", formatTrackDisplay("Trust", "Us"))
    }

    @Test
    fun `formatTrackDisplay handles blank artist or clean track name`() {
        assertEquals("Bohemian Rhapsody", formatTrackDisplay("Bohemian Rhapsody - Topic", ""))
        assertEquals("Bohemian Rhapsody", formatTrackDisplay("Bohemian Rhapsody - Topic", "   "))
    }
}

