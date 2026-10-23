package ca.saboor.discs.jellyfin

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.MediaContainerHints
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAacTrackConsumer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaContainerProbe
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaOpusTrackConsumer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaTrackConsumer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaVorbisTrackConsumer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaCuePoint
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaElement
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaElementType
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileReader
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.DataFormatTools
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.ExceptionTools
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/**
 * Matroska/WebM container probe that supports progressive FFmpeg streams where the Segment or
 * Cluster EBML data size is unknown (`0x01FFFFFFFFFFFFFF`), which overflows LavaPlayer's 32-bit
 * `MatroskaElement.dataSize` to `-1`.
 */
internal class ProgressiveMatroskaContainerProbe : MediaContainerProbe {
    override fun getName(): String = "matroska/webm"

    override fun matchesHints(hints: MediaContainerHints): Boolean = false

    override fun probe(reference: AudioReference, inputStream: SeekableInputStream): MediaContainerDetectionResult? {
        if (!MediaContainerDetection.checkNextBytes(inputStream, EBML_TAG)) {
            return null
        }
        val file = ProgressiveMatroskaStreamingFile(inputStream)
        file.readFile()
        if (file.trackList.none { it.type == MatroskaFileTrack.Type.AUDIO && isSupportedAudioCodec(it.codecId) }) {
            return MediaContainerDetectionResult.unsupportedFormat(this, "No supported audio tracks present in the file.")
        }
        val title = DataFormatTools.defaultOnNull(file.title, MediaContainerDetection.UNKNOWN_TITLE)
        val artist = DataFormatTools.defaultOnNull(file.artist, MediaContainerDetection.UNKNOWN_ARTIST)
        return MediaContainerDetectionResult.supportedFormat(
            this,
            null,
            AudioTrackInfo(title, artist, file.duration.toLong(), reference.identifier, false, reference.identifier, null, file.isrc)
        )
    }

    override fun createTrack(parameters: String?, trackInfo: AudioTrackInfo, inputStream: SeekableInputStream): AudioTrack =
        ProgressiveMatroskaAudioTrack(trackInfo, inputStream)

    companion object {
        private val EBML_TAG = intArrayOf(0x1A, 0x45, 0xDF, 0xA3)

        fun isAacCodec(codecId: String?): Boolean =
            codecId == MatroskaContainerProbe.AAC_CODEC || codecId?.startsWith("A_AAC/") == true

        fun isSupportedAudioCodec(codecId: String?): Boolean =
            codecId == MatroskaContainerProbe.OPUS_CODEC ||
                codecId == MatroskaContainerProbe.VORBIS_CODEC ||
                isAacCodec(codecId)
    }
}

private class ProgressiveMatroskaAudioTrack(
    trackInfo: AudioTrackInfo,
    private val inputStream: SeekableInputStream,
) : BaseAudioTrack(trackInfo) {
    override fun process(localExecutor: LocalAudioTrackExecutor) {
        val file = ProgressiveMatroskaStreamingFile(inputStream).apply {
            readFile()
            accurateDuration.set(duration.toLong())
        }
        val trackConsumer = loadAudioTrack(file, localExecutor.processingContext)
        try {
            localExecutor.executeProcessingLoop(
                { file.provideFrames(trackConsumer) },
                { position -> file.seekToTimecode(trackConsumer.track.index, position) }
            )
        } finally {
            ExceptionTools.closeWithWarnings(trackConsumer)
        }
    }

    private fun loadAudioTrack(file: ProgressiveMatroskaStreamingFile, context: AudioProcessingContext): MatroskaTrackConsumer {
        var trackConsumer: MatroskaTrackConsumer? = null
        var success = false
        try {
            trackConsumer = selectAudioTrack(file.trackList, context)
                ?: throw IllegalStateException("No supported audio tracks in the file.")
            trackConsumer.initialise()
            success = true
            return trackConsumer
        } finally {
            if (!success && trackConsumer != null) {
                ExceptionTools.closeWithWarnings(trackConsumer)
            }
        }
    }

    private fun selectAudioTrack(tracks: List<MatroskaFileTrack>, context: AudioProcessingContext): MatroskaTrackConsumer? {
        var consumer: MatroskaTrackConsumer? = null
        for (track in tracks) {
            if (track.type == MatroskaFileTrack.Type.AUDIO) {
                when {
                    track.codecId == MatroskaContainerProbe.OPUS_CODEC -> return MatroskaOpusTrackConsumer(context, track)
                    track.codecId == MatroskaContainerProbe.VORBIS_CODEC -> consumer = MatroskaVorbisTrackConsumer(context, track)
                    ProgressiveMatroskaContainerProbe.isAacCodec(track.codecId) -> consumer = MatroskaAacTrackConsumer(context, track)
                }
            }
        }
        return consumer
    }
}

private class ProgressiveMatroskaElement(level: Int) : MatroskaElement(level) {
    var longDataSize: Long = 0
        private set
    var unknownSize: Boolean = false
        private set

    fun update(id: Long, type: MatroskaElementType, position: Long, headerSize: Int, dataSize: Long, unknownSize: Boolean) {
        this.id = id
        this.type = type
        this.position = position
        this.headerSize = headerSize
        this.longDataSize = dataSize
        this.unknownSize = unknownSize
        this.dataSize = if (unknownSize || dataSize > Int.MAX_VALUE) Int.MAX_VALUE else dataSize.toInt()
    }

    override fun getRemaining(currentPosition: Long): Long =
        if (unknownSize) Long.MAX_VALUE - currentPosition
        else (position + headerSize + longDataSize) - currentPosition

    override fun frozen(): MatroskaElement =
        ProgressiveMatroskaElement(level).also {
            it.update(id, type, position, headerSize, longDataSize, unknownSize)
        }
}

private class ProgressiveMatroskaFileReader(
    private val stream: SeekableInputStream,
) : MatroskaFileReader(stream) {
    private var levels = arrayOfNulls<ProgressiveMatroskaElement>(16)
    private var pendingElement: ProgressiveMatroskaElement? = null

    override fun readNextElement(parent: MatroskaElement?): MatroskaElement? {
        val targetLevel = if (parent == null) 0 else parent.level + 1
        val pending = pendingElement
        if (pending != null && pending.level == targetLevel) {
            pendingElement = null
            return pending
        }

        val position = stream.position
        val remaining = if (parent != null) parent.getRemaining(position) else stream.contentLength - position
        if (remaining == 0L) return null
        if (remaining < 0L) throw IllegalStateException("Current position is beyond this element")

        val firstIdByte = try {
            stream.read()
        } catch (_: EOFException) {
            -1
        }
        if (firstIdByte == -1) return null

        val idLength = Integer.numberOfLeadingZeros(firstIdByte) - 23
        val id = readVint(firstIdByte, idLength)
        val firstSizeByte = dataInput.readUnsignedByte()
        val sizeLength = Integer.numberOfLeadingZeros(firstSizeByte) - 23
        val dataSize = readVint(firstSizeByte, sizeLength)
        val unknownSize = dataSize == (1L shl (7 * sizeLength)) - 1L
        val dataPosition = stream.position
        val type = MatroskaElementType.find(id)
        val headerSize = (dataPosition - position).toInt()

        if ((parent as? ProgressiveMatroskaElement)?.unknownSize == true &&
            parent.`is`(MatroskaElementType.Cluster) &&
            isSegmentLevelElement(type)
        ) {
            pendingElement = ProgressiveMatroskaElement(parent.level).also {
                it.update(id, type, position, headerSize, dataSize, unknownSize)
            }
            return null
        }

        if (targetLevel >= levels.size) {
            levels = levels.copyOf(maxOf(levels.size * 2, targetLevel + 1))
        }
        val element = levels[targetLevel] ?: ProgressiveMatroskaElement(targetLevel).also { levels[targetLevel] = it }
        element.update(id, type, position, headerSize, dataSize, unknownSize)
        return element
    }

    override fun skip(element: MatroskaElement) {
        if ((element as? ProgressiveMatroskaElement)?.unknownSize == true) return
        val remaining = element.getRemaining(stream.position)
        if (remaining > 0) {
            try {
                stream.skipFully(remaining)
            } catch (_: EOFException) {
                // Progressive stream closed cleanly at EOF.
            }
        }
    }

    private fun readVint(firstByte: Int, codeLength: Int): Long {
        if (codeLength !in 1..8) {
            throw IllegalStateException("More than 8 bytes for EBML integer ($codeLength), probably invalid data")
        }
        var code = (firstByte.toLong() and (0xFFL ushr codeLength)) shl ((codeLength - 1) shl 3)
        for (i in 2..codeLength) {
            code = code or (dataInput.readUnsignedByte().toLong() shl ((codeLength - i) shl 3))
        }
        return code
    }

    private fun isSegmentLevelElement(type: MatroskaElementType): Boolean =
        type == MatroskaElementType.Cluster ||
            type == MatroskaElementType.Cues ||
            type == MatroskaElementType.Tags ||
            type == MatroskaElementType.Info ||
            type == MatroskaElementType.Tracks ||
            type == MatroskaElementType.SeekHead
}

private class ProgressiveMatroskaStreamingFile(inputStream: SeekableInputStream) {
    private val reader = ProgressiveMatroskaFileReader(inputStream)

    var title: String? = null
        private set
    var artist: String? = null
        private set
    var isrc: String? = null
        private set
    var timecodeScale: Long = 1_000_000L
        private set
    var duration: Double = 0.0
        private set

    val trackList = ArrayList<MatroskaFileTrack>()
    private var segmentElement: MatroskaElement? = null
    private var firstClusterElement: MatroskaElement? = null
    private var minimumTimecode: Long = 0
    private var seeking: Boolean = false
    private var cueElementPosition: Long? = null
    private var cuePoints: List<MatroskaCuePoint>? = null

    fun readFile() {
        val ebmlElement = reader.readNextElement(null)
            ?: throw IllegalStateException("Unable to scan for EBML elements")
        if (!ebmlElement.`is`(MatroskaElementType.Ebml)) {
            throw IllegalStateException("EBML Header not the first element in the file")
        }
        parseEbmlElement(ebmlElement)

        val segment = reader.readNextElement(null)?.frozen()
            ?: throw IllegalStateException("Missing Segment element in Matroska file")
        if (!segment.`is`(MatroskaElementType.Segment)) {
            throw IllegalStateException("Segment not the second element in the file: was ${segment.type.name} (${segment.id}) instead")
        }
        segmentElement = segment
        parseSegmentElement(segment)
    }

    private fun parseEbmlElement(ebmlElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(ebmlElement) ?: break
            if (child.`is`(MatroskaElementType.DocType)) {
                val docType = reader.asString(child)
                if (docType != "matroska" && docType != "webm") {
                    throw IllegalStateException("Error: DocType is not matroska, \"$docType\"")
                }
            }
            reader.skip(child)
        }
    }

    private fun parseSegmentElement(segment: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(segment) ?: break
            when {
                child.`is`(MatroskaElementType.Info) -> parseSegmentInfo(child)
                child.`is`(MatroskaElementType.Tags) -> runCatching { parseTags(child) }
                child.`is`(MatroskaElementType.Tracks) -> parseTracks(child)
                child.`is`(MatroskaElementType.Cluster) -> {
                    firstClusterElement = child.frozen()
                    break
                }
                child.`is`(MatroskaElementType.SeekHead) -> runCatching { parseSeekInfoForCuePosition(child) }
                child.`is`(MatroskaElementType.Cues) -> runCatching { cuePoints = parseCues(child) }
            }
            reader.skip(child)
        }
    }

    private fun parseSeekInfoForCuePosition(seekHeadElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(seekHeadElement) ?: break
            if (child.`is`(MatroskaElementType.Seek)) {
                parseSeekElement(child)
            }
            reader.skip(child)
        }
    }

    private fun parseSeekElement(seekElement: MatroskaElement) {
        var isCueElement = false
        while (true) {
            val child = reader.readNextElement(seekElement) ?: break
            if (child.`is`(MatroskaElementType.SeekId)) {
                isCueElement = ByteBuffer.wrap(reader.asBytes(child)) == ByteBuffer.wrap(MatroskaElementType.Cues.bytes)
            } else if (child.`is`(MatroskaElementType.SeekPosition) && isCueElement) {
                cueElementPosition = reader.asLong(child)
            }
            reader.skip(child)
        }
    }

    private fun parseCues(cuesElement: MatroskaElement): List<MatroskaCuePoint>? {
        val parsed = ArrayList<MatroskaCuePoint>()
        while (true) {
            val child = reader.readNextElement(cuesElement) ?: break
            if (child.`is`(MatroskaElementType.CuePoint)) {
                parseCuePoint(child)?.let { parsed.add(it) }
            }
            reader.skip(child)
        }
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun parseCuePoint(cuePointElement: MatroskaElement): MatroskaCuePoint? {
        var cueTime: Long? = null
        var positions: LongArray? = null
        while (true) {
            val child = reader.readNextElement(cuePointElement) ?: break
            if (child.`is`(MatroskaElementType.CueTime)) {
                cueTime = reader.asLong(child)
            } else if (child.`is`(MatroskaElementType.CueTrackPositions)) {
                positions = parseCueTrackPositions(child)
            }
            reader.skip(child)
        }
        return if (cueTime != null && positions != null) MatroskaCuePoint(cueTime, positions) else null
    }

    private fun parseCueTrackPositions(positionsElement: MatroskaElement): LongArray {
        var currentTrackId: Long? = null
        val positions = LongArray(trackList.size + 1) { -1L }
        while (true) {
            val child = reader.readNextElement(positionsElement) ?: break
            if (child.`is`(MatroskaElementType.CueTrack)) {
                currentTrackId = reader.asLong(child)
            } else if (child.`is`(MatroskaElementType.CueClusterPosition) && currentTrackId != null) {
                val idx = currentTrackId.toInt()
                if (idx in positions.indices) {
                    positions[idx] = reader.asLong(child)
                }
            }
            reader.skip(child)
        }
        return positions
    }

    fun seekToTimecode(trackId: Int, timecode: Long) {
        val segment = segmentElement ?: return
        val firstCluster = firstClusterElement ?: return
        minimumTimecode = timecode
        seeking = true

        val cuePos = cueElementPosition
        if (cuePoints == null && cuePos != null) {
            reader.seek(segment.dataPosition + cuePos)
            val cuesElement = reader.readNextElement(segment)
            if (cuesElement != null && cuesElement.`is`(MatroskaElementType.Cues)) {
                cuePoints = parseCues(cuesElement)
            }
        }

        val cues = cuePoints
        if (cues != null) {
            val cuePoint = lastCueNotAfterTimecode(cues, timecode)
            if (cuePoint != null && trackId in cuePoint.trackClusterOffsets.indices && cuePoint.trackClusterOffsets[trackId] >= 0) {
                reader.seek(segment.dataPosition + cuePoint.trackClusterOffsets[trackId])
                return
            }
        }

        reader.seek(firstCluster.position)
    }

    private fun lastCueNotAfterTimecode(cues: List<MatroskaCuePoint>, timecode: Long): MatroskaCuePoint? {
        var index = 0
        while (index < cues.size) {
            if (cues[index].timecode > timecode) break
            index++
        }
        return if (index > 0) cues[index - 1] else null
    }

    fun provideFrames(consumer: MatroskaTrackConsumer) {
        val segment = segmentElement ?: return
        val firstCluster = firstClusterElement ?: return
        val position = reader.position
        var child: MatroskaElement? = if (position == firstCluster.dataPosition) firstCluster else reader.readNextElement(segment)

        while (child != null) {
            if (child.`is`(MatroskaElementType.Cluster)) {
                parseNextCluster(child, consumer)
            }
            reader.skip(child)
            if (segment.getRemaining(reader.position) <= 0) {
                break
            }
            child = reader.readNextElement(segment)
        }
    }

    private fun parseNextCluster(clusterElement: MatroskaElement, consumer: MatroskaTrackConsumer) {
        var clusterTimecode = 0L
        while (true) {
            val child = reader.readNextElement(clusterElement) ?: break
            when {
                child.`is`(MatroskaElementType.Timecode) -> clusterTimecode = reader.asLong(child)
                child.`is`(MatroskaElementType.SimpleBlock) -> parseClusterSimpleBlock(child, consumer, clusterTimecode)
                child.`is`(MatroskaElementType.BlockGroup) -> parseClusterBlockGroup(child, consumer, clusterTimecode)
            }
            reader.skip(child)
        }
    }

    private fun parseClusterSimpleBlock(simpleBlock: MatroskaElement, consumer: MatroskaTrackConsumer, clusterTimecode: Long) {
        val block = reader.readBlockHeader(simpleBlock, consumer.track.index)
        if (block != null) {
            processFrameInBlock(block, consumer, clusterTimecode)
        }
    }

    private fun parseClusterBlockGroup(blockGroup: MatroskaElement, consumer: MatroskaTrackConsumer, clusterTimecode: Long) {
        while (true) {
            val child = reader.readNextElement(blockGroup) ?: break
            if (child.`is`(MatroskaElementType.Block)) {
                val block = reader.readBlockHeader(child, consumer.track.index)
                if (block != null) {
                    processFrameInBlock(block, consumer, clusterTimecode)
                }
            }
            reader.skip(child)
        }
    }

    private fun processFrameInBlock(
        block: su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaBlock,
        consumer: MatroskaTrackConsumer,
        clusterTimecode: Long,
    ) {
        val timecode = clusterTimecode + block.timecode
        if (timecode >= minimumTimecode) {
            val frameCount = block.frameCount
            if (seeking) {
                consumer.seekPerformed(minimumTimecode, timecode)
                seeking = false
            }
            for (i in 0 until frameCount) {
                consumer.consume(block.getNextFrameBuffer(reader, i))
            }
        }
    }

    private fun parseSegmentInfo(infoElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(infoElement) ?: break
            when {
                child.`is`(MatroskaElementType.Duration) -> duration = reader.asDouble(child)
                child.`is`(MatroskaElementType.TimecodeScale) -> timecodeScale = reader.asLong(child)
                child.`is`(MatroskaElementType.Title) && title == null -> title = reader.asString(child, StandardCharsets.UTF_8)
            }
            reader.skip(child)
        }
    }

    private fun parseTracks(tracksElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(tracksElement) ?: break
            if (child.`is`(MatroskaElementType.TrackEntry)) {
                trackList.add(MatroskaFileTrack.parse(child, reader))
            }
            reader.skip(child)
        }
    }

    private fun parseTags(tagsElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(tagsElement) ?: break
            if (child.`is`(MatroskaElementType.Tag)) {
                parseTag(child)
            }
            reader.skip(child)
        }
    }

    private fun parseTag(tagElement: MatroskaElement) {
        while (true) {
            val child = reader.readNextElement(tagElement) ?: break
            if (child.`is`(MatroskaElementType.SimpleTag)) {
                parseSimpleTag(child)
            }
            reader.skip(child)
        }
    }

    private fun parseSimpleTag(simpleTagElement: MatroskaElement) {
        var tagName: String? = null
        while (true) {
            val child = reader.readNextElement(simpleTagElement) ?: break
            if (child.`is`(MatroskaElementType.TagName)) {
                tagName = reader.asString(child)
            } else if (child.`is`(MatroskaElementType.TagString)) {
                if ("title".equals(tagName, ignoreCase = true) && title == null) {
                    title = reader.asString(child, StandardCharsets.UTF_8)
                } else if ("artist".equals(tagName, ignoreCase = true)) {
                    artist = reader.asString(child, StandardCharsets.UTF_8)
                } else if ("isrc".equals(tagName, ignoreCase = true)) {
                    isrc = reader.asString(child, StandardCharsets.UTF_8)
                }
            }
            reader.skip(child)
        }
    }
}
