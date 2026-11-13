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
 * v1.0.11 adds the screen's own lifecycle: a covered screen (not STARTED)
 * is in the dashboard / split-screen slot even when the host kept the
 * surface live and wide, so it must fall back to the card too — the width
 * and visible-area checks cannot catch a host that never takes the
 * surface away. If the host keeps the screen STARTED in split-screen,
 * this input cannot distinguish it; that is for the car to tell.
 *
 * The visible area arrives as an android.graphics.Rect, which cannot run
 * on the JVM, so the screen maps it to booleans before calling
 * [chooseTemplate] (null → reported false, empty rect → empty true) and
 * the tests cover the booleans directly.
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
                screenStarted = true,
                visibleAreaReported = true,
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
                screenStarted = true,
                visibleAreaReported = true,
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
                screenStarted = true,
                visibleAreaReported = true,
            ),
        )
    }

    @Test
    fun `no reported visible area with a wide surface returns FALLBACK`() {
        // v1.0.19: a template choice must wait for surface facts.
        // Null means "not reported yet" — v1.0.18 treated it as all
        // visible and picked the surface template while the surface
        // was not ready, which threw during the template build on
        // first open and flashed the error message. Until the area
        // is reported, the fallback is the answer.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
                screenStarted = true,
                visibleAreaReported = false,
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
                screenStarted = true,
                visibleAreaReported = true,
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
                screenStarted = true,
                visibleAreaReported = true,
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
                screenStarted = true,
                visibleAreaReported = true,
            ),
        )
    }

    @Test
    fun `covered screen falls back even with a live wide surface`() {
        // v1.0.11: the host kept the surface live and wide in
        // split-screen, so width and visible area cannot catch it — but
        // a covered screen is not the full-screen foreground app, and
        // the slot shows the three-line card, not the surface layout.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
                screenStarted = false,
                visibleAreaReported = true,
            ),
        )
    }

    @Test
    fun `started screen with a live wide surface shows the surface template`() {
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
                screenStarted = true,
                visibleAreaReported = true,
            ),
        )
    }
}
