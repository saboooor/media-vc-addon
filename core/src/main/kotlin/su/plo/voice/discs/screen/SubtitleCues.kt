package su.plo.voice.discs.screen

/** Plain SRT/WebVTT captions. Lookup is stateless so backwards seeks work too. */
class SubtitleCues private constructor(private val cues: List<Cue>) {
    data class Cue(val start: Long, val end: Long, val text: String)
    fun textAt(positionMs: Long): String = cues.asSequence()
        .takeWhile { it.start <= positionMs }.filter { positionMs < it.end }
        .map { it.text }.distinct().joinToString("\n").take(1000)

    companion object {
        val EMPTY = SubtitleCues(emptyList())
        private val timing = Regex("(?:(\\d+):)?(\\d{2}):(\\d{2})[,.](\\d{3})")
        private fun millis(value: String): Long? {
            val m = timing.matchEntire(value.trim()) ?: return null
            val (h, min, sec, ms) = m.destructured
            if (min.toInt() > 59 || sec.toInt() > 59) return null
            return (h.toLongOrNull() ?: 0) * 3600000 + min.toLong() * 60000 + sec.toLong() * 1000 + ms.toLong()
        }
        fun parse(content: String): SubtitleCues {
            val cues = content.removePrefix("\uFEFF").replace("\r\n", "\n").split(Regex("\n[ \\t]*\n")).mapNotNull { block ->
                val lines = block.lines()
                val i = lines.indexOfFirst { " --> " in it }
                if (i < 0) return@mapNotNull null
                val times = lines[i].split(" --> ", limit = 2)
                val start = millis(times[0]) ?: return@mapNotNull null
                val end = millis(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
                val text = lines.drop(i + 1).joinToString("\n").replace(Regex("<[^>]*>|\\{[^}]*}"), "")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
                if (end <= start || text.isBlank()) null else Cue(start, end, text.take(1000))
            }.sortedBy { it.start }
            return SubtitleCues(cues)
        }
    }
}
