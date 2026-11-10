package com.dannycom888.mylrcauto.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun parse(content: String): List<LyricLine> {
        var offsetMs = 0L
        val result = mutableListOf<LyricLine>()

        content.lineSequence().forEach lineLoop@{ raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            val offsetMatch = Regex("""(?i)^\[offset:([+-]?\d+)\]$""").matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                return@lineLoop
            }

            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@lineLoop
            val lyricText = line.substring(tags.last().range.last + 1).trim()

            tags.forEach tagLoop@{ tag ->
                val minutes = tag.groupValues[1].toLongOrNull() ?: return@tagLoop
                val seconds = tag.groupValues[2].toLongOrNull() ?: return@tagLoop
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    3 -> fraction.toLong()
                    else -> 0L
                }
                val time = (minutes * 60_000L + seconds * 1_000L + millis + offsetMs)
                    .coerceAtLeast(0L)
                result += LyricLine(time, lyricText)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else high = mid - 1
        }
        return answer
    }
}
