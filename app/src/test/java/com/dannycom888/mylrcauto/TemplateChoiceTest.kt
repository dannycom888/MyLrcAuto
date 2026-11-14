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
 * on the JVM, so the screen maps it to a boolean before calling
 * [chooseTemplate] (null or non-empty → false, empty rect → true) and
 * the tests cover the boolean directly.
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
            ),
        )
    }

    @Test
    fun `unreported visible area does not block the surface template`() {
        // v1.0.20 regression pin. v1.0.19 gated the choice on the
        // visible area having been reported (null → FALLBACK) — and
        // deadlocked the big screen for the whole session: the area
        // is only ever reported once the surface template is on
        // screen, so waiting for the report before choosing it made
        // the surface unreachable and left the fallback (then a
        // loading pane) up forever. An unreported area maps to
        // visibleAreaEmpty = false here, and must not block: the
        // surface CONTENT still waits for the area before drawing
        // lyrics (black until then), so nothing shifts.
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_7,
                surfaceWidthDp = 1024f,
                visibleAreaEmpty = false,
                screenStarted = true,
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
            ),
        )
    }

    // ---- the v1.0.29 speculative branch -----------------------------------
    //
    // A host grants a surface in connection with a surface template
    // being on screen; while the fallback pane is up, nothing
    // provokes the offer. The 2026-11-13 19:07 run sat on the pane
    // for a whole song because the session's launch-time offer had
    // been missed. While the callback is registered and the ask
    // window is open, the surface template goes up WITHOUT a live
    // surface — presenting it is the ask.

    @Test
    fun `registered callback inside the ask window shows the surface template without a surface`() {
        assertEquals(
            TemplateChoice.ON_SURFACE,
            chooseTemplate(
                surfaceLive = false,
                apiLevel = CarAppApiLevels.LEVEL_8,
                surfaceWidthDp = null,
                visibleAreaEmpty = false,
                screenStarted = true,
                surfaceRequested = true,
            ),
        )
    }

    @Test
    fun `no surface and no open ask window still falls back`() {
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = false,
                apiLevel = CarAppApiLevels.LEVEL_8,
                surfaceWidthDp = null,
                visibleAreaEmpty = false,
                screenStarted = true,
                surfaceRequested = false,
            ),
        )
    }

    @Test
    fun `the ask never fires for a covered screen`() {
        // Covered = dashboard / split-screen slot: the three-line
        // card is the answer there, surface or no surface.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = false,
                apiLevel = CarAppApiLevels.LEVEL_8,
                surfaceWidthDp = null,
                visibleAreaEmpty = false,
                screenStarted = false,
                surfaceRequested = true,
            ),
        )
    }

    @Test
    fun `the ask never fires below api level 7`() {
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = false,
                apiLevel = CarAppApiLevels.LEVEL_5,
                surfaceWidthDp = null,
                visibleAreaEmpty = false,
                screenStarted = true,
                surfaceRequested = true,
            ),
        )
    }

    @Test
    fun `a live narrow surface still falls back even inside the ask window`() {
        // The live branch's own checks outrank the ask: a narrow
        // live surface is a split-screen slot, and the card is the
        // answer there.
        assertEquals(
            TemplateChoice.FALLBACK,
            chooseTemplate(
                surfaceLive = true,
                apiLevel = CarAppApiLevels.LEVEL_8,
                surfaceWidthDp = 400f,
                visibleAreaEmpty = false,
                screenStarted = true,
                surfaceRequested = true,
            ),
        )
    }
}
