package com.dannycom888.mylrcauto.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dannycom888.mylrcauto.settings.LyricsFontChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Chinese lyrics font rule, under the Settings font choice
 * (歌词字体): Bauhaus — a line with any CJK character draws in the
 * bundled Bauhaus font, bold; Original — the same line draws in the
 * default font, bold only for the line being sung (the behaviour
 * before v1.0.15). A line with no CJK character keeps the default
 * font, unchanged (bold only for the line being sung), whichever
 * choice is picked. The dashboard card's subtitle is drawn by the
 * car host, so its font is out of scope — this rule covers the app's
 * own surfaces only (the car big-screen lyrics and the phone lyrics
 * view, both [LyricsView]).
 */
class LyricsFontTest {

    @Test
    fun chineseLineResolvesToBundledFontAndBold() {
        val current = lyricsFontSpec("一万个舍不得", isCurrent = true)
        assertSame(ChineseLyricsFontFamily, current.fontFamily)
        assertEquals(FontWeight.Bold, current.fontWeight)

        // Non-current Chinese lines are bold too — the user asked for
        // Chinese lyrics in this font and bold, not just the one line
        // being sung.
        val other = lyricsFontSpec("毕竟我们深爱过", isCurrent = false)
        assertSame(ChineseLyricsFontFamily, other.fontFamily)
        assertEquals(FontWeight.Bold, other.fontWeight)
    }

    @Test
    fun englishLineKeepsDefaultFontUnchanged() {
        val current = lyricsFontSpec("Lemon Tree", isCurrent = true)
        assertSame(FontFamily.Default, current.fontFamily)
        assertEquals(FontWeight.Bold, current.fontWeight)

        val other = lyricsFontSpec("I'm sitting here in a boring room", isCurrent = false)
        assertSame(FontFamily.Default, other.fontFamily)
        assertEquals(FontWeight.Normal, other.fontWeight)
    }

    @Test
    fun originalChoiceKeepsChineseLinesOnDefaultFont() {
        // Original = the pre-v1.0.15 behaviour: Chinese lines use the
        // default font, bold only for the line being sung.
        val current = lyricsFontSpec(
            "一万个舍不得",
            isCurrent = true,
            fontChoice = LyricsFontChoice.ORIGINAL,
        )
        assertSame(FontFamily.Default, current.fontFamily)
        assertEquals(FontWeight.Bold, current.fontWeight)

        val other = lyricsFontSpec(
            "毕竟我们深爱过",
            isCurrent = false,
            fontChoice = LyricsFontChoice.ORIGINAL,
        )
        assertSame(FontFamily.Default, other.fontFamily)
        assertEquals(FontWeight.Normal, other.fontWeight)
    }

    @Test
    fun bauhausChoiceIsTheDefault() {
        // No choice passed: the default parameter is Bauhaus, so the
        // v1.0.15 behaviour is what every existing call site gets.
        val spec = lyricsFontSpec("一万个舍不得", isCurrent = false)
        assertSame(ChineseLyricsFontFamily, spec.fontFamily)
        assertEquals(FontWeight.Bold, spec.fontWeight)
    }

    @Test
    fun englishLineIgnoresTheChoice() {
        // English keeps the default font under either choice.
        val bauhaus = lyricsFontSpec(
            "Lemon Tree",
            isCurrent = false,
            fontChoice = LyricsFontChoice.BAUHAUS,
        )
        val original = lyricsFontSpec(
            "Lemon Tree",
            isCurrent = false,
            fontChoice = LyricsFontChoice.ORIGINAL,
        )
        assertSame(FontFamily.Default, bauhaus.fontFamily)
        assertSame(FontFamily.Default, original.fontFamily)
        assertEquals(FontWeight.Normal, bauhaus.fontWeight)
        assertEquals(FontWeight.Normal, original.fontWeight)
    }

    @Test
    fun mixedLineWithAnyCjkUsesBundledFont() {
        // One CJK character is enough: the whole line draws in the
        // bundled font, so a Chinese line with an English word in it
        // does not flip fonts mid-line.
        val spec = lyricsFontSpec("我爱你 (I love you)", isCurrent = false)
        assertSame(ChineseLyricsFontFamily, spec.fontFamily)
        assertEquals(FontWeight.Bold, spec.fontWeight)
    }

    @Test
    fun cjkDetectionCoversCommonLyricsCharacters() {
        assertTrue(isCjkLine("你好"))
        assertTrue(isCjkLine("中文歌词"))
        assertTrue(isCjkLine("爱"))
        assertTrue(isCjkLine("，")) // CJK punctuation
        assertFalse(isCjkLine("Hello"))
        assertFalse(isCjkLine("123"))
        assertFalse(isCjkLine("..."))
        assertFalse(isCjkLine(""))
    }
}
