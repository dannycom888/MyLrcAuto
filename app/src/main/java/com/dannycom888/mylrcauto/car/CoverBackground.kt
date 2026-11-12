package com.dannycom888.mylrcauto.car

import android.graphics.Bitmap
import com.dannycom888.mylrcauto.settings.BackgroundStyle
import kotlin.math.pow

/**
 * The big screen's backdrop: the current track's cover, blurred — or all black.
 *
 * Melisma draws its big-screen background from the cover and lets the user
 * change it; this is the same idea, narrowed to what the user asked for
 * (2026-11-11): two styles, COVER and BLACK, default BLACK. The background
 * is the app's own surface only — the host-drawn pane (top-left box) is
 * the car's UI and is never touched.
 *
 * Readability is the constraint, not an afterthought: the current lyric
 * line is a pale cyan ([CURRENT_LINE_ARGB]) that the user likes for its
 * glow, and it must stay readable over any cover, including a white one.
 * So the blurred cover is dimmed ([DIM_FACTOR]) and then sits under a
 * black scrim ([SCRIM_ALPHA]); the two together bring even a white cover
 * down to a luminance where the cyan line still has ≥ 3:1 contrast and
 * white text ≥ 4:1 (see [effectiveBackgroundLuminance] and the tests).
 * The glow styling itself ([com.dannycom888.mylrcauto.ui.LyricsView])
 * is untouched.
 *
 * Efficiency: the blur runs once per cover, on a tiny downscaled copy
 * ([BLUR_INPUT_MAX_PX] on the longest side), and the result is cached
 * by the caller keyed on the cover + blur radius — never per frame and
 * never on the 500 ms playhead tick.
 */
object CoverBackground {

    /** Longest side of the bitmap the blur works on. 48px is a colour wash, not a picture. */
    const val BLUR_INPUT_MAX_PX = 48

    /** The blurred cover's pixels are multiplied by this before drawing. */
    const val DIM_FACTOR = 0.6f

    /** Black overlay drawn over the blurred cover, as a fraction. */
    const val SCRIM_ALPHA = 0.5f

    /**
     * The current lyric line's colour — Theme.kt's SecondaryTeal, the
     * pale cyan the user calls the glow. Kept here so the contrast
     * guarantee is testable; if the theme colour ever changes, update
     * this and the tests together.
     */
    const val CURRENT_LINE_ARGB: Int = 0xFF9FDCE8.toInt()

    /** White, for the non-current lines and status text. */
    const val WHITE_ARGB: Int = 0xFFFFFFFF.toInt()

    /**
     * Which backdrop to draw. COVER only when the style asks for it *and*
     * a cover actually exists — a missing cover falls back to black, never
     * a blank or white screen.
     */
    fun selectBackground(style: BackgroundStyle, hasCover: Boolean): BackgroundStyle =
        if (style == BackgroundStyle.COVER && hasCover) BackgroundStyle.COVER else BackgroundStyle.BLACK

    /**
     * The cover, blurred and dimmed, ready to draw stretched over the
     * surface. Downscales first (a 384px cover becomes ≤ 48px), blurs
     * that, then dims the pixels. Returns null for a recycled, empty or
     * unusable bitmap — the caller draws the dark fallback instead.
     * Never throws: any failure is a null, never a crash on the car surface.
     */
    fun blurCover(cover: Bitmap, radiusPx: Int): Bitmap? {
        if (cover.isRecycled || cover.width <= 0 || cover.height <= 0) return null
        return runCatching {
            val longest = maxOf(cover.width, cover.height)
            val small = if (longest > BLUR_INPUT_MAX_PX) {
                val scale = BLUR_INPUT_MAX_PX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    cover,
                    maxOf(1, (cover.width * scale).toInt()),
                    maxOf(1, (cover.height * scale).toInt()),
                    true,
                )
            } else {
                cover
            }
            val w = small.width
            val h = small.height
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val blurred = blurPixels(pixels, w, h, radiusPx)
            for (i in blurred.indices) blurred[i] = dimPixel(blurred[i], DIM_FACTOR)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(blurred, 0, w, 0, 0, w, h)
            out
        }.getOrNull()
    }

    /**
     * Two-pass box blur (horizontal, then vertical) with edge replication.
     * Pure Kotlin over an IntArray, so it is unit-testable on the JVM.
     * Radius 0 returns an unchanged copy; a radius larger than the image
     * just averages the whole thing. Output is always opaque — a backdrop
     * has no use for transparency.
     */
    fun blurPixels(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (width <= 0 || height <= 0 || src.size < width * height) return src.copyOf()
        if (radius <= 0) return src.copyOf()
        val r = radius.coerceAtMost(maxOf(width, height))
        return boxBlurVertical(boxBlurHorizontal(src, width, height, r), width, height, r)
    }

    private fun boxBlurHorizontal(src: IntArray, width: Int, height: Int, r: Int): IntArray {
        val out = IntArray(src.size)
        val window = 2 * r + 1
        for (y in 0 until height) {
            val row = y * width
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (dx in -r..r) {
                val px = src[row + dx.coerceIn(0, width - 1)]
                sumR += (px shr 16) and 0xFF
                sumG += (px shr 8) and 0xFF
                sumB += px and 0xFF
            }
            for (x in 0 until width) {
                out[row + x] =
                    (0xFF shl 24) or ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)
                val outPx = src[row + (x - r).coerceIn(0, width - 1)]
                val inPx = src[row + (x + r + 1).coerceIn(0, width - 1)]
                sumR += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                sumG += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                sumB += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
        return out
    }

    private fun boxBlurVertical(src: IntArray, width: Int, height: Int, r: Int): IntArray {
        val out = IntArray(src.size)
        val window = 2 * r + 1
        for (x in 0 until width) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (dy in -r..r) {
                val px = src[x + dy.coerceIn(0, height - 1) * width]
                sumR += (px shr 16) and 0xFF
                sumG += (px shr 8) and 0xFF
                sumB += px and 0xFF
            }
            for (y in 0 until height) {
                out[y * width + x] =
                    (0xFF shl 24) or ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)
                val outPx = src[x + (y - r).coerceIn(0, height - 1) * width]
                val inPx = src[x + (y + r + 1).coerceIn(0, height - 1) * width]
                sumR += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                sumG += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                sumB += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
        return out
    }

    /** Multiplies a pixel's RGB by [factor], keeping its alpha. */
    fun dimPixel(pixel: Int, factor: Float): Int {
        val a = (pixel ushr 24) and 0xFF
        val r = (((pixel shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((pixel shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((pixel and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun relativeLuminance(pixel: Int): Float {
        fun linear(c: Int): Float {
            val s = c / 255f
            return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = linear((pixel shr 16) and 0xFF)
        val g = linear((pixel shr 8) and 0xFF)
        val b = linear(pixel and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG contrast ratio between two colours, 1 (same) to 21 (black on white). */
    fun contrastRatio(foreground: Int, background: Int): Float {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val (lighter, darker) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * The luminance a cover pixel ends up with on screen: dimmed by
     * [DIM_FACTOR], then under a black scrim of [SCRIM_ALPHA]. The
     * contrast guarantee is stated against this: even a white cover
     * pixel must land dark enough for the cyan current line (≥ 3:1)
     * and white text (≥ 4:1).
     */
    fun effectiveBackgroundLuminance(coverPixel: Int): Float {
        val dimmed = dimPixel(coverPixel, DIM_FACTOR)
        val keep = 1f - SCRIM_ALPHA
        val r = (((dimmed shr 16) and 0xFF) * keep).toInt()
        val g = (((dimmed shr 8) and 0xFF) * keep).toInt()
        val b = ((dimmed and 0xFF) * keep).toInt()
        val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return relativeLuminance(pixel)
    }
}
