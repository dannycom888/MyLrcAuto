package com.dannycom888.mylrcauto

import androidx.car.app.versioning.CarAppApiLevels
import com.dannycom888.mylrcauto.car.SPLIT_SCREEN_WIDTH_DP
import com.dannycom888.mylrcauto.car.TemplateChoice
import com.dannycom888.mylrcauto.car.chooseTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The template choice (v1.0.10): which template shows the lyrics has to
 * react to more than "is there a surface" — a host that only shows the app
 * in a split-screen slot still reports the surface as live, and a host
 * that granted a surface without showing it reports an empty visible area.
 * Both must fall back to the three-line card, or the card never appears.
 *
 * The visible area arrives as an android.graphics.Rect, which cannot run
 * on the JVM, so the screen maps it to a boolean before calling
 * [chooseTemplate] (null → false, empty rect → true) and the tests cover
 * the boolean directly.
 */
class TemplateChoiceTest {

    @Test
    fun `wide live surface shows the surface template`() {
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
            ),
        )
    }

    @Test
    fun `narrow live surface falls back to the three-line card`() {
        // A dashboard slot is roughly a third of the screen (~300–400dp);
        // 400dp is well under the 600dp threshold.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 400f,
                visibleAreaEmpty = false,
            ),
        )
    }

    @Test
    fun `empty visible area falls back even on a wide surface`() {
        // The host granted a surface but is not actually showing it.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = true,
            ),
        )
    }

    @Test
    fun `no reported visible area with a wide surface shows the surface template`() {
        // Null means "not reported yet", treated as all visible.
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
            ),
        )
    }

    @Test
    fun `api below level 7 falls back`() {
        // MapWithContentTemplate needs level 7; a surface arrives from
        // level 5.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_5,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
            ),
        )
    }

    @Test
    fun `no surface falls back`() {
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = false,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = null,
                visibleAreaEmpty = false,
            ),
        )
    }

    @Test
    fun `surface exactly at the threshold counts as full screen`() {
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = SPLIT_SCREEN_WIDTH_DP,
                visibleAreaEmpty = false,
            ),
        )
    }
}
