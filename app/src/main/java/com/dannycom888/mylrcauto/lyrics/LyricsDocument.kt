package com.dannycom888.mylrcauto.lyrics

/**
 * One parsed `.lrc` file, ready to render.
 *
 * Lines are sorted by [LyricLine.timeMs] (see [LrcParser.parse]).
 */
data class LyricsDocument(
    val lines: List<LyricLine>,
    /** File name the lyrics came from, for display and debugging. */
    val sourceName: String,
) {
    /** Index of the line playing at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int = LrcParser.currentIndex(lines, positionMs)

    /** True when the file has at least one timed line. */
    val hasTimings: Boolean get() = lines.isNotEmpty()
}
