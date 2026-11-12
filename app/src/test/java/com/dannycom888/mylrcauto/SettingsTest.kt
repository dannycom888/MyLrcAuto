package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.settings.BackgroundStyle
import com.dannycom888.mylrcauto.settings.SettingsStore
import com.dannycom888.mylrcauto.ui.LyricsTextSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two new settings, as pure logic — no Android framework needed:
 * the font-size range/clamp in [SettingsStore], and the size derivation
 * in [LyricsTextSize] that [com.dannycom888.mylrcauto.ui.LyricsView] draws
 * with. Persistence itself (SharedPreferences read/write) is the same
 * store the language choice already uses.
 */
class SettingsTest {

    @Test
    fun fontSizeRangeIs14To32Default17() {
        assertEquals(14, SettingsStore.MIN_LYRICS_FONT_SIZE_SP)
        assertEquals(32, SettingsStore.MAX_LYRICS_FONT_SIZE_SP)
        assertEquals(17, SettingsStore.DEFAULT_LYRICS_FONT_SIZE_SP)
    }

    @Test
    fun fontSizeCoercionClampsOutOfRange() {
        assertEquals(14, SettingsStore.coerceLyricsFontSizeSp(-5))
        assertEquals(14, SettingsStore.coerceLyricsFontSizeSp(0))
        assertEquals(14, SettingsStore.coerceLyricsFontSizeSp(14))
        assertEquals(17, SettingsStore.coerceLyricsFontSizeSp(17))
        assertEquals(32, SettingsStore.coerceLyricsFontSizeSp(32))
        assertEquals(32, SettingsStore.coerceLyricsFontSizeSp(100))
    }

    @Test
    fun defaultSizeKeepsTheOriginalHardcodedSizes() {
        // 17 sp was the hardcoded base size: current line 22 sp, line
        // heights 30 / 24 sp. The default must draw exactly as before.
        assertEquals(22, LyricsTextSize.currentSizeSp(17))
        assertEquals(30, LyricsTextSize.currentLineHeightSp(17))
        assertEquals(24, LyricsTextSize.lineHeightSp(17))
    }

    @Test
    fun sizeDerivationScalesWithBase() {
        assertEquals(19, LyricsTextSize.currentSizeSp(14))
        assertEquals(27, LyricsTextSize.currentLineHeightSp(14))
        assertEquals(21, LyricsTextSize.lineHeightSp(14))
        assertEquals(37, LyricsTextSize.currentSizeSp(32))
        assertEquals(45, LyricsTextSize.currentLineHeightSp(32))
        assertEquals(39, LyricsTextSize.lineHeightSp(32))
    }

    @Test
    fun blurRangeIs0To25Default12() {
        assertEquals(0, SettingsStore.MIN_COVER_BLUR_PX)
        assertEquals(25, SettingsStore.MAX_COVER_BLUR_PX)
        assertEquals(12, SettingsStore.DEFAULT_COVER_BLUR_PX)
    }

    @Test
    fun blurCoercionClampsOutOfRange() {
        assertEquals(0, SettingsStore.coerceCoverBlurPx(-5))
        assertEquals(0, SettingsStore.coerceCoverBlurPx(0))
        assertEquals(12, SettingsStore.coerceCoverBlurPx(12))
        assertEquals(25, SettingsStore.coerceCoverBlurPx(25))
        assertEquals(25, SettingsStore.coerceCoverBlurPx(100))
    }

    @Test
    fun backgroundStyleDefaultsToBlackAndUnknownNamesFallBack() {
        // The user likes all-black, so a fresh install — and any stored
        // name an older build does not know — is BLACK, never a crash.
        assertEquals(BackgroundStyle.BLACK, BackgroundStyle.fromName(null))
        assertEquals(BackgroundStyle.BLACK, BackgroundStyle.fromName("NOPE"))
        assertEquals(BackgroundStyle.COVER, BackgroundStyle.fromName("COVER"))
        assertEquals(BackgroundStyle.BLACK, BackgroundStyle.fromName("BLACK"))
    }
}
