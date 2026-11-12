package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.ui.scrollTargetIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll-following decision (v1.0.14).
 *
 * The big-screen list stopped scrolling because the car surface's 500ms
 * ticker went stale after the lyrics loaded — the playhead froze, so the
 * current line never advanced and this scroll never fired. The ticker
 * itself is a Compose runtime concern, but the decision it feeds is
 * pure: when the current line advances, the scroll target must advance
 * with it, so the current line stays on the third visible row. These
 * tests pin that.
 */
class LyricsScrollTest {

    @Test
    fun firstLinesStayAtTheTop() {
        // The current line lands on the third visible row, with two lines
        // of context above it — clamped to the top at the start of a song.
        assertEquals(0, scrollTargetIndex(currentIndex = 0, lineCount = 20))
        assertEquals(0, scrollTargetIndex(currentIndex = 1, lineCount = 20))
        assertEquals(0, scrollTargetIndex(currentIndex = 2, lineCount = 20))
    }

    @Test
    fun currentLineAdvancesMoveTheScrollTarget() {
        // The scroll offset updates as the song plays: one line further
        // means one row further down the list.
        assertEquals(3, scrollTargetIndex(currentIndex = 5, lineCount = 20))
        assertEquals(4, scrollTargetIndex(currentIndex = 6, lineCount = 20))
        assertEquals(8, scrollTargetIndex(currentIndex = 10, lineCount = 20))
    }

    @Test
    fun targetClampsToTheLastLineAtTheEndOfTheSong() {
        assertEquals(17, scrollTargetIndex(currentIndex = 19, lineCount = 20))
        assertEquals(19, scrollTargetIndex(currentIndex = 25, lineCount = 20))
    }

    @Test
    fun singleLineSongNeverScrolls() {
        assertEquals(0, scrollTargetIndex(currentIndex = 0, lineCount = 1))
    }

    @Test
    fun scrollTargetNeverGoesBackwardsAsTheSongPlays() {
        // A whole song's worth of line advances: the target only moves
        // forward, never backwards — the list follows, it does not jump.
        val targets = (0 until 20).map { scrollTargetIndex(it, lineCount = 20) }
        assertTrue(targets.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(17, targets.last())
    }
}
