package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CoverBackground
import com.dannycom888.mylrcauto.settings.BackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blurred-cover backdrop, as pure logic — no Android framework needed:
 * the pixel blur, the dimming, the contrast guarantee, and the
 * cover-or-black selection. The Bitmap plumbing in
 * [CoverBackground.blurCover] (downscale → pixels → blur → dim) is thin
 * and delegates to these; it cannot run on the JVM without Robolectric,
 * so it is covered by the car test instead.
 */
class CoverBackgroundTest {

    // ---- blur ----

    @Test
    fun solidColourStaysSolid() {
        val pixels = IntArray(16) { 0xFFFF0000.toInt() }
        val out = CoverBackground.blurPixels(pixels, 4, 4, 12)
        assertEquals(16, out.size)
        assertTrue(out.all { it == 0xFFFF0000.toInt() })
    }

    @Test
    fun radiusZeroIsIdentity() {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val out = CoverBackground.blurPixels(pixels, 2, 2, 0)
        assertEquals(pixels.toList(), out.toList())
    }

    @Test
    fun blackAndWhiteHalvesMixAtTheBoundary() {
        // One row: three black, three white. Radius 1 must pull grey into
        // the pixels either side of the boundary and leave the ends alone.
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(black, black, black, white, white, white)
        val out = CoverBackground.blurPixels(pixels, 6, 1, 1)
        assertEquals(black, out[0])
        assertEquals(0xFF555555.toInt(), out[2])
        assertEquals(0xFFAAAAAA.toInt(), out[3])
        assertEquals(white, out[5])
    }

    @Test
    fun blurKeepsDimensionsAndOpaqueAlpha() {
        val pixels = IntArray(12) { 0xFF123456.toInt() }
        val out = CoverBackground.blurPixels(pixels, 4, 3, 25)
        assertEquals(12, out.size)
        assertTrue(out.all { ((it ushr 24) and 0xFF) == 0xFF })
    }

    @Test
    fun radiusLargerThanTheImageDoesNotCrash() {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val out = CoverBackground.blurPixels(pixels, 2, 2, 100)
        assertEquals(4, out.size)
        assertTrue(out.all { ((it ushr 24) and 0xFF) == 0xFF })
    }

    // ---- dimming and contrast ----

    @Test
    fun dimPixelScalesRgbAndKeepsAlpha() {
        assertEquals(0xFF7F7F7F.toInt(), CoverBackground.dimPixel(0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF000000.toInt(), CoverBackground.dimPixel(0xFF000000.toInt(), 0.5f))
        assertEquals(0x80FF0000.toInt(), CoverBackground.dimPixel(0x80FF0000.toInt(), 1f))
    }

    @Test
    fun contrastRatioSanity() {
        // Black on white is 21:1; the same colour is 1:1.
        assertEquals(21f, CoverBackground.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01f)
        assertEquals(1f, CoverBackground.contrastRatio(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()), 0.01f)
    }

    @Test
    fun whiteCoverStaysReadableAfterDimAndScrim() {
        // The worst case: a pure white cover. After dimming and the black
        // scrim it must still be dark enough for the cyan current line
        // (≥ 3:1, large bold text) and white text (≥ 4:1). Contrast is
        // stated against the effective background luminance, not a
        // hand-built pixel.
        val bgLuminance = CoverBackground.effectiveBackgroundLuminance(0xFFFFFFFF.toInt())
        assertTrue("white cover must land dark, was $bgLuminance", bgLuminance < 0.15f)
        val cyanContrast = (CoverBackground.relativeLuminance(CoverBackground.CURRENT_LINE_ARGB) + 0.05f) /
            (bgLuminance + 0.05f)
        assertTrue("cyan current line must stay readable, was $cyanContrast", cyanContrast >= 3.0f)
        val whiteContrast = (1.0f + 0.05f) / (bgLuminance + 0.05f)
        assertTrue("white text must stay readable, was $whiteContrast", whiteContrast >= 4.0f)
    }

    @Test
    fun saturatedCoverStaysReadableToo() {
        // A pure red cover is the brightest saturated case; it must also clear the bar.
        val bgLuminance = CoverBackground.effectiveBackgroundLuminance(0xFFFF0000.toInt())
        val cyanContrast = (CoverBackground.relativeLuminance(CoverBackground.CURRENT_LINE_ARGB) + 0.05f) /
            (bgLuminance + 0.05f)
        assertTrue("cyan over a red cover must stay readable, was $cyanContrast", cyanContrast >= 3.0f)
    }

    // ---- selection ----

    @Test
    fun coverStyleWithCoverShowsTheCover() {
        assertEquals(
            BackgroundStyle.COVER,
            CoverBackground.selectBackground(BackgroundStyle.COVER, hasCover = true),
        )
    }

    @Test
    fun coverStyleWithoutCoverFallsBackToBlack() {
        assertEquals(
            BackgroundStyle.BLACK,
            CoverBackground.selectBackground(BackgroundStyle.COVER, hasCover = false),
        )
    }

    @Test
    fun blackStyleNeverShowsTheCover() {
        assertEquals(
            BackgroundStyle.BLACK,
            CoverBackground.selectBackground(BackgroundStyle.BLACK, hasCover = true),
        )
        assertEquals(
            BackgroundStyle.BLACK,
            CoverBackground.selectBackground(BackgroundStyle.BLACK, hasCover = false),
        )
    }
}
