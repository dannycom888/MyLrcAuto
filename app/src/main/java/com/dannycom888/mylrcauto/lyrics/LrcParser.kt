package com.dannycom888.mylrcauto.lyrics

/**
 * One timed fragment of a lyric line: [text] sung starting at [startMs].
 *
 * Produced from an enhanced LRC's inline word tags (`<mm:ss.xx>`);
 * see [LrcParser]. A fragment with empty text is the line's end
 * marker — the final tag of an enhanced line carries no words, it
 * only says when the line stops being sung.
 */
data class WordTiming(val startMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    /**
     * Per-fragment timings from the line's inline word tags, or
     * empty for a plain line-timed LRC line. Concatenating the
     * fragments' texts yields [text] exactly, so [text] stays the
     * pure lyric words either way — the mirror session's subtitle,
     * the glance and every other reader of [text] see no tags.
     */
    val words: List<WordTiming> = emptyList(),
)

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    /**
     * An enhanced LRC's inline word tag, strict shape only:
     * `<mm:ss.xx>` with a mandatory fraction. Anything else that
     * merely contains `<` — a comparison, a heart, a stray bracket
     * in ordinary lyrics — is not a tag and stays in the text.
     */
    private val wordTag = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>""")

    /** Milliseconds for a tag's groups, or null when the tag is not a real time. */
    private fun tagTimeMs(minutes: String, seconds: String, fraction: String?): Long? {
        val mins = minutes.toLongOrNull() ?: return null
        val secs = seconds.toLongOrNull() ?: return null
        // A timestamp whose seconds run past 59 is not a timestamp:
        // [00:75.00] is a typo or another file's convention, and
        // reading it as 1:15 put the line over a minute late. Such a
        // tag is invalid and produces nothing (v1.0.33).
        if (secs >= 60) return null
        val millis = when (fraction?.length ?: 0) {
            1 -> fraction!!.toLong() * 100
            2 -> fraction!!.toLong() * 10
            3 -> fraction!!.toLong()
            else -> 0L
        }
        return mins * 60_000L + secs * 1_000L + millis
    }

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
            // The words follow the last bracketed tag, valid or not —
            // the same text boundary the plain parser always used.
            val lyricText = line.substring(tags.last().range.last + 1).trim()
            // Only valid tags (seconds < 60) yield a line; a line
            // whose tags are all invalid is skipped, exactly like a
            // line with no tags at all.
            val times = tags.mapNotNull { tag ->
                tagTimeMs(tag.groupValues[1], tag.groupValues[2], tag.groupValues[3])
            }
            if (times.isEmpty()) return@lineLoop

            val (text, words) = parseWords(lyricText, offsetMs)
            times.forEach { time ->
                result += LyricLine(
                    timeMs = (time + offsetMs).coerceAtLeast(0L),
                    text = text,
                    words = words,
                )
            }
        }
        return result.sortedBy { it.timeMs }
    }

    /**
     * Splits a line's text into word-timed fragments when it is an
     * enhanced line, or returns it untouched when it is not.
     *
     * A line counts as enhanced only when its text *starts* with a
     * word tag: a plain lyric that happens to contain a tag-shaped
     * string mid-line keeps it as literal text, exactly as v1.0.32
     * showed it. A fragment whose tag is invalid (seconds >= 60) is
     * dropped whole — tag and the words it would have timed — while
     * its neighbours survive. When every tag is invalid, the line
     * falls back to its text with the tag shapes stripped.
     */
    private fun parseWords(content: String, offsetMs: Long): Pair<String, List<WordTiming>> {
        val matches = wordTag.findAll(content).toList()
        if (matches.isEmpty() || matches.first().range.first != 0) {
            return content to emptyList()
        }
        val segments = mutableListOf<WordTiming>()
        matches.forEachIndexed { index, match ->
            val start = tagTimeMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                ?: return@forEachIndexed
            val end = matches.getOrNull(index + 1)?.range?.first ?: content.length
            segments += WordTiming(
                startMs = (start + offsetMs).coerceAtLeast(0L),
                text = content.substring(match.range.last + 1, end),
            )
        }
        if (segments.isEmpty()) {
            return wordTag.replace(content, "").trim() to emptyList()
        }
        return segments.joinToString("") { it.text } to segments
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

/**
 * The karaoke sweep: how much of a word-timed line is already sung.
 *
 * Pure arithmetic over the parsed fragments, so the sweep the driver
 * sees is unit-testable without a screen. A fragment counts as sung
 * once its start arrives; inside the fragment currently being sung,
 * characters light in proportion to the progress toward the next
 * fragment's start — so a fragment of several characters still
 * sweeps character by character, and a one-character fragment (the
 * shape the converter emits) lights exactly at its start. The final
 * fragment bounds the one before it; a trailing text fragment with
 * nothing after it lights whole at its start.
 */
object WordSweep {

    /** Number of leading characters of the line already sung at [positionMs]. */
    fun sungCharCount(words: List<WordTiming>, positionMs: Long): Int {
        var sung = 0
        for (index in words.indices) {
            val segment = words[index]
            if (positionMs < segment.startMs) break
            val nextStart = words.getOrNull(index + 1)?.startMs
            when {
                nextStart == null || nextStart <= segment.startMs ->
                    sung += segment.text.length
                positionMs >= nextStart ->
                    sung += segment.text.length
                else -> {
                    val progress =
                        (positionMs - segment.startMs).toDouble() /
                            (nextStart - segment.startMs).toDouble()
                    sung += (segment.text.length * progress).toInt()
                }
            }
        }
        return sung
    }
}
