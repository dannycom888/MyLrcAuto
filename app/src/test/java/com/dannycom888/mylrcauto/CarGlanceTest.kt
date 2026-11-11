package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.lyrics.LyricLine
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.LyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The template card shows exactly three rows — previous, current, next —
 * with the line being sung in the middle, so [CarGlance.Now] has to carry
 * all three. Blank lines are skipped on both sides.
 */
class CarGlanceTest {

    private val document = LyricsDocument(
        lines = listOf(
            LyricLine(0L, "one"),
            LyricLine(5_000L, "two"),
            LyricLine(10_000L, ""),
            LyricLine(15_000L, "four"),
            LyricLine(20_000L, "five"),
        ),
        sourceName = "test.lrc",
    )

    private fun glanceAt(positionMs: Long): CarGlance.Now {
        val glance = CarGlance.of(
            state = LyricsState.Loaded(document),
            hasTrack = true,
            permissionGranted = true,
            positionMs = positionMs,
        )
        return glance as CarGlance.Now
    }

    @Test
    fun middleLineHasPreviousCurrentAndNextSkippingBlanks() {
        val now = glanceAt(6_000L)
        assertEquals("one", now.previous)
        assertEquals("two", now.line)
        assertEquals("four", now.next)
    }

    @Test
    fun firstLineHasNoPrevious() {
        val now = glanceAt(0L)
        assertNull(now.previous)
        assertEquals("one", now.line)
        assertEquals("two", now.next)
    }

    @Test
    fun lastLineHasNoNext() {
        val now = glanceAt(21_000L)
        assertEquals("four", now.previous)
        assertEquals("five", now.line)
        assertNull(now.next)
    }

    @Test
    fun beforeFirstLineIsInstrumentalWithNextOnly() {
        val doc = LyricsDocument(
            lines = listOf(LyricLine(5_000L, "one"), LyricLine(10_000L, "two")),
            sourceName = "test.lrc",
        )
        val glance = CarGlance.of(
            state = LyricsState.Loaded(doc),
            hasTrack = true,
            permissionGranted = true,
            positionMs = 0L,
        ) as CarGlance.Now
        assertNull(glance.previous)
        assertNull(glance.line)
        assertEquals("one", glance.next)
        assertTrue(glance.instrumental)
    }

    @Test
    fun blankCurrentLineSkipsBothWays() {
        val now = glanceAt(11_000L)
        assertEquals("two", now.previous)
        assertNull(now.line)
        assertEquals("four", now.next)
        assertTrue(now.instrumental)
    }
}
