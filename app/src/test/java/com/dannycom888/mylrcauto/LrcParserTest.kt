package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesBasicLines() {
        val lines = LrcParser.parse("[00:01.00]第一行\n[00:05.50]第二行")
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(5_500L, lines[1].timeMs)
    }

    @Test
    fun oneLineWithMultipleTagsProducesMultipleLines() {
        val lines = LrcParser.parse("[00:01.00][00:03.00]重复行")
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(3_000L, lines[1].timeMs)
        assertEquals("重复行", lines[0].text)
    }

    @Test
    fun offsetShiftsTimestamps() {
        val lines = LrcParser.parse("[offset:+500]\n[00:01.00]第一行")
        assertEquals(1_500L, lines[0].timeMs)
    }

    @Test
    fun handlesOneTwoAndThreeDecimalFractions() {
        val lines = LrcParser.parse(
            "[00:01.5]一\n[00:02.25]二\n[00:03.125]三",
        )
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals(2_250L, lines[1].timeMs)
        assertEquals(3_125L, lines[2].timeMs)
    }

    @Test
    fun sortsOutOfOrderLines() {
        val lines = LrcParser.parse("[00:05.00]后\n[00:01.00]先")
        assertEquals("先", lines[0].text)
        assertEquals("后", lines[1].text)
    }

    @Test
    fun ignoresLinesWithoutTags() {
        val lines = LrcParser.parse("[ti:标题]\n[00:01.00]第一行\n没有标签")
        assertEquals(1, lines.size)
        assertEquals("第一行", lines[0].text)
    }

    @Test
    fun currentIndexFindsPlayingLine() {
        val lines = LrcParser.parse(
            "[00:01.00]一\n[00:05.00]二\n[00:10.00]三",
        )
        assertEquals(-1, LrcParser.currentIndex(lines, 500L))
        assertEquals(0, LrcParser.currentIndex(lines, 1_000L))
        assertEquals(0, LrcParser.currentIndex(lines, 4_999L))
        assertEquals(1, LrcParser.currentIndex(lines, 5_000L))
        assertEquals(2, LrcParser.currentIndex(lines, 60_000L))
    }
}
