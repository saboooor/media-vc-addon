package ca.saboor.discs

import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

class PlaybackControlsTest {
    private class Decoder(val seekable: Boolean = true) {
        var filterFactory: su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory? = null
        var paused = false
        var volume = 100
        var position = 30_000L
        val track = proxy(AudioTrack::class.java) { name, args ->
            when (name) {
                "isSeekable" -> seekable
                "getDuration" -> 90_000L
                "getPosition" -> position
                "setPosition" -> { position = args!![0] as Long; null }
                else -> null
            }
        }
        val player = proxy(AudioPlayer::class.java) { name, args ->
            when (name) {
                "setFilterFactory" -> { filterFactory = args!![0] as? su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory; null }
                "getPlayingTrack" -> track
                "isPaused" -> paused
                "setPaused" -> { paused = args!![0] as Boolean; null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Int; null }
                else -> null
            }
        }
        companion object {
            private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                    when (method.name) {
                        "equals" -> instance === args!![0]
                        "hashCode" -> System.identityHashCode(instance)
                        else -> call(method.name, args)
                    }
                })
        }
    }

    @Test fun `restart during the final second resumes there instead of wrapping to the beginning`() {
        val old = Decoder()
        val controls = PlaybackControls()
        controls.attach(old.player)
        controls.frameSent(old.player, 89_500)
        val restarted = controls.copyForRestart()
        val fresh = Decoder()
        restarted.attach(fresh.player)
        assertEquals(89_500L, fresh.position)
    }

    @Test fun `restart preserves transmitted position pause user filters and volume in fresh controls`() {
        val decoder = Decoder()
        val controls = PlaybackControls(initialVolume = 65)
        controls.attach(decoder.player)
        controls.frameSent(decoder.player, 12_340)
        val effects = ca.saboor.discs.audio.AudioFilterConfig(tempo = 1.25, pitchSemitones = 2.0)
        controls.applyFilterConfig(effects)
        controls.autoMixAnalysis = true
        controls.tempoAnalysis = true
        controls.setVisualizing(true, "lights")
        controls.setMixTempoFactor(0.95)
        controls.setGain(0.25)
        controls.togglePause()
        controls.fail("Old decoder failure")
        val restarted = controls.copyForRestart()
        assertEquals(12_340L, restarted.initialPositionMs)
        assertEquals(65, restarted.volume)
        assertEquals(effects, restarted.filterConfig)
        assertTrue(restarted.paused)
        assertTrue(restarted.autoMixAnalysis)
        assertTrue(restarted.tempoAnalysis)
        assertTrue(restarted.visualizing)
        assertNull(restarted.failure)
        assertNull(restarted.transmittedPositionMs)
        assertNull(restarted.transitionPartner)
        assertEquals(0L, restarted.sentAudioMs)
        assertEquals(1.0, restarted.currentGain)
        assertEquals(1.0, restarted.mixTempoFactor)
        val fresh = Decoder()
        restarted.attach(fresh.player)
        assertEquals(12_340L, fresh.position)
        assertTrue(fresh.paused)
        assertEquals(65, fresh.volume)
        assertTrue(decoder.paused)
    }

    @Test fun `restart of non-seekable audio reconnects live without inventing a seek position`() {
        val decoder = Decoder(seekable = false)
        val controls = PlaybackControls()
        controls.attach(decoder.player)
        controls.frameSent(decoder.player, 42_000)
        assertEquals(0L, controls.copyForRestart().initialPositionMs)
    }

    @Test fun `restart while loading retains the requested starting position`() {
        val controls = PlaybackControls(initialPositionMs = 27_000)
        assertEquals(27_000L, controls.copyForRestart().initialPositionMs)
    }

    @Test fun `mix clock advances only for attached transmitted frames and is independent of seeks`() {
        val controls = PlaybackControls()
        val decoder = Decoder()
        controls.attach(decoder.player)
        assertEquals(0L, controls.sentAudioMs)
        controls.frameSent(decoder.player, 30_000)
        assertEquals(20L, controls.sentAudioMs)
        controls.seekTo(60_000)
        assertEquals(20L, controls.sentAudioMs)
        controls.frameSent(decoder.player, 60_000)
        assertEquals(40L, controls.sentAudioMs)
        controls.detach(decoder.player)
        controls.frameSent(decoder.player, 60_020)
        assertEquals(40L, controls.sentAudioMs)
    }

    @Test fun `video clock waits for transmitted audio after a seek`() {
        val controls = PlaybackControls()
        val decoder = Decoder()
        controls.attach(decoder.player)
        assertNull(controls.transmittedPositionMs)
        controls.frameSent(decoder.player, 30_000)
        assertEquals(30_000L, controls.transmittedPositionMs)
        controls.seekTo(60_000)
        assertEquals(60_000L, controls.position)
        assertNull(controls.transmittedPositionMs)
        controls.frameSent(decoder.player, 60_000)
        assertEquals(60_000L, controls.transmittedPositionMs)
    }

    @Test fun `pause and volume apply to both voice backends and late attachments`() {
        val controls = PlaybackControls()
        val pv = Decoder()
        controls.attach(pv.player)
        controls.togglePause()
        controls.changeVolume(-30)
        val svc = Decoder()
        controls.attach(svc.player)
        assertTrue(pv.paused)
        assertTrue(svc.paused)
        assertEquals(70, pv.volume)
        assertEquals(70, svc.volume)
        controls.togglePause()
        assertFalse(pv.paused)
        assertFalse(svc.paused)
    }

    @Test fun `seeking synchronizes both decoders and clamps to playable track bounds`() {
        val controls = PlaybackControls()
        val pv = Decoder()
        val svc = Decoder()
        controls.attach(pv.player)
        controls.attach(svc.player)
        controls.seekTo(45_000)
        assertEquals(45_000L, pv.position)
        assertEquals(pv.position, svc.position)
        controls.seekTo(-15_000)
        assertEquals(0L, pv.position)
        controls.seekTo(Long.MAX_VALUE)
        assertEquals(89_999L, pv.position)
        assertEquals(pv.position, svc.position)
    }

    @Test fun `live streams cannot seek and detached decoders receive no updates`() {
        val controls = PlaybackControls()
        val stream = Decoder(seekable = false)
        controls.attach(stream.player)
        controls.seekTo(0)
        assertEquals(30_000L, stream.position)
        controls.detach(stream.player)
        controls.togglePause()
        assertFalse(stream.paused)
    }

    @Test fun `a late backend joins the current playback position`() {
        val controls = PlaybackControls()
        val pv = Decoder()
        controls.attach(pv.player)
        controls.seekTo(60_000)
        val svc = Decoder()
        controls.attach(svc.player)
        assertEquals(60_000L, svc.position)
    }

    @Test fun `fade gain scales volume without changing the listener volume setting`() {
        val controls = PlaybackControls(initialVolume = 80, initialGain = 0.0)
        val decoder = Decoder()
        controls.attach(decoder.player)
        assertEquals(0, decoder.volume)
        controls.setGain(0.5)
        assertEquals(40, decoder.volume)
        assertEquals(80, controls.volume)
        controls.changeVolume(-20)
        assertEquals(30, decoder.volume)
        val late = Decoder()
        controls.attach(late.player)
        assertEquals(30, late.volume)
        controls.setGain(1.0)
        assertEquals(60, decoder.volume)
        controls.setGain(-1.0)
        assertEquals(0, decoder.volume)
    }

    @Test fun `pause and volume changes control both sides of a crossfade`() {
        val outgoing = PlaybackControls()
        val incoming = PlaybackControls(initialGain = 0.25)
        val oldDecoder = Decoder()
        val newDecoder = Decoder()
        outgoing.attach(oldDecoder.player)
        incoming.attach(newDecoder.player)
        incoming.transitionPartner = outgoing
        incoming.togglePause()
        assertTrue(oldDecoder.paused)
        assertTrue(newDecoder.paused)
        incoming.changeVolume(-20)
        assertEquals(80, oldDecoder.volume)
        assertEquals(20, newDecoder.volume)
        incoming.togglePause()
        assertFalse(oldDecoder.paused)
        assertFalse(newDecoder.paused)
    }

    @Test fun `video seek callback gets the same clamped position as both audio backends`() {
        val controls = PlaybackControls()
        val pv = Decoder()
        val svc = Decoder()
        controls.attach(pv.player)
        controls.attach(svc.player)
        val videoPositions = mutableListOf<Long>()
        controls.onSeek = { target ->
            assertEquals(target, pv.position)
            assertEquals(target, svc.position)
            videoPositions += target
        }
        controls.seekTo(-300_000)
        controls.seekTo(60_000)
        controls.seekTo(300_000)
        assertEquals(listOf(0L, 60_000L, 89_999L), videoPositions)
    }

    @Test fun `unseekable tracks do not seek the video`() {
        val controls = PlaybackControls()
        controls.attach(Decoder(seekable = false).player)
        var called = false
        controls.onSeek = { called = true }
        controls.seekTo(15_000)
        assertFalse(called)
    }

    @Test fun `visualizer requests do not attach a decoder lookahead filter`() {
        val controls = PlaybackControls()
        val pv = Decoder()
        val svc = Decoder()
        controls.attach(pv.player)
        controls.attach(svc.player)
        controls.setVisualizing(true)
        assertTrue(controls.visualizing)
        assertNull(pv.filterFactory)
        assertNull(svc.filterFactory)
        assertTrue(controls.spectrum.levels.all { it == 0f })
        controls.setVisualizing(false)
        assertFalse(controls.visualizing)
    }

    @Test fun `linked bass lamps and visualizers independently request the shared audio tap`() {
        val controls = PlaybackControls()
        val decoder = Decoder()
        controls.attach(decoder.player)
        controls.setVisualizing(true, "bass_lamps")
        assertTrue(controls.visualizing)
        assertNull(decoder.filterFactory)
        controls.setVisualizing(true)
        controls.setVisualizing(false)
        assertTrue(controls.visualizing)
        assertNull(decoder.filterFactory)
        controls.setVisualizing(false, "bass_lamps")
        assertFalse(controls.visualizing)
        assertNull(decoder.filterFactory)
    }

    @Test fun `volume stays within zero to one hundred percent`() {
        val controls = PlaybackControls()
        val decoder = Decoder()
        controls.attach(decoder.player)
        controls.changeVolume(50)
        assertEquals(100, decoder.volume)
        controls.changeVolume(-200)
        assertEquals(0, decoder.volume)
    }

    @Test fun `initial position is restored on attachment for seekable tracks`() {
        val controls = PlaybackControls(initialPositionMs = 45_000L)
        assertEquals(45_000L, controls.position)
        val decoder = Decoder()
        decoder.position = 0L
        controls.attach(decoder.player)
        assertEquals(45_000L, decoder.position)
        assertEquals(45_000L, controls.position)
    }

    @Test fun `initial position near end of duration wraps to beginning`() {
        val controls = PlaybackControls(initialPositionMs = 89_500L)
        val decoder = Decoder() // duration is 90_000L
        decoder.position = 0L
        controls.attach(decoder.player)
        assertEquals(0L, decoder.position)
    }
}


