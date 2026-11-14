package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.settings.LyricsFontChoice
import com.dannycom888.mylrcauto.settings.Settings
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
    fun defaultSizeUsesTheTighterLineHeights() {
        // 17 sp is the default base size: current line 22 sp, line
        // heights 28 / 22 sp. v1.0.19 tightened the spacing (+11 / +5,
        // down from +13 / +7) so more lines fit; the sizes themselves
        // did not change.
        assertEquals(22, LyricsTextSize.currentSizeSp(17))
        assertEquals(28, LyricsTextSize.currentLineHeightSp(17))
        assertEquals(22, LyricsTextSize.lineHeightSp(17))
    }

    @Test
    fun diagnosticsHiddenByDefault() {
        // The diagnostics blocks are display-only and hidden until
        // asked for (v1.0.30): a fresh install's Settings stays
        // short, while every diagnostic keeps recording underneath
        // — so the data class default and the store's fallback
        // agree on false.
        assertEquals(false, Settings().diagnosticsVisible)
        assertEquals(false, SettingsStore.DEFAULT_DIAGNOSTICS_VISIBLE)
    }

    @Test
    fun lyricsFontDefaultsToBauhaus() {
        // The font choice defaults to Bauhaus, so adding the setting
        // changes nothing for an existing install: Chinese lyrics
        // stay in the bundled font, bold, exactly as in v1.0.15,
        // until the user picks 原来的.
        assertEquals(LyricsFontChoice.BAUHAUS, Settings().lyricsFont)
        assertEquals(LyricsFontChoice.BAUHAUS, SettingsStore.DEFAULT_LYRICS_FONT)
    }

    @Test
    fun sizeDerivationScalesWithBase() {
        assertEquals(19, LyricsTextSize.currentSizeSp(14))
        assertEquals(25, LyricsTextSize.currentLineHeightSp(14))
        assertEquals(19, LyricsTextSize.lineHeightSp(14))
        assertEquals(37, LyricsTextSize.currentSizeSp(32))
        assertEquals(43, LyricsTextSize.currentLineHeightSp(32))
        assertEquals(37, LyricsTextSize.lineHeightSp(32))
    }
}
