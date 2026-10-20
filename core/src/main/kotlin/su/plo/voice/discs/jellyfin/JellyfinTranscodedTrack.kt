package su.plo.voice.discs.jellyfin

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/** Progressive transcodes have no finalized MP3 seek table or stable byte offsets. */
internal class JellyfinTranscodedTrack(
    private val delegate: InternalAudioTrack,
    private val durationMs: Long,
    private val reload: (Long) -> InternalAudioTrack,
    private val startPositionMs: Long = 0,
    private val deferred: Boolean = false,
) : DelegatedAudioTrack(delegate.info) {
    val canRestart: Boolean get() = durationMs > 0
    fun restartAt(positionMs: Long): JellyfinTranscodedTrack =
        JellyfinTranscodedTrack(delegate, durationMs, reload,
            positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0)), true)
    override fun getPosition() = startPositionMs + super.getPosition()
    override fun isSeekable() = false
    // Ignore even zero-position assignments used when cloning speakers/backends: a zero-frame
    // Xing header can make LavaPlayer divide by zero for any seek, including a seek to zero.
    override fun setPosition(position: Long) = Unit
    override fun getDuration() = if (durationMs > 0) durationMs else super.getDuration()
    override fun getSourceManager() = delegate.sourceManager
    override fun process(executor: LocalAudioTrackExecutor) =
        processDelegate(if (deferred) reload(startPositionMs) else delegate, executor)
    override fun makeShallowClone(): AudioTrack =
        JellyfinTranscodedTrack(delegate.makeClone() as InternalAudioTrack, durationMs, reload, startPositionMs, deferred)
}
