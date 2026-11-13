package com.dannycom888.mylrcauto.car

import androidx.car.app.versioning.CarAppApiLevels

/** Which template the car screen shows the lyrics in. */
internal enum class TemplateChoice {

    /** The app draws the full lyrics itself on the granted surface. */
    ON_SURFACE,

    /** The host draws the three-line card (previous / current / next). */
    FALLBACK,
}

/**
 * The split-screen threshold, in dp of granted surface width.
 *
 * The full-screen content area on this car is ~1000dp+ wide; a dashboard /
 * split-screen slot is roughly a third of the screen, ~300–400dp. 600dp
 * sits between the two, so a live surface narrower than that is a
 * split-screen slot the host is only showing a corner of — not a full
 * screen the app should cover with its own drawing.
 */
internal const val SPLIT_SCREEN_WIDTH_DP = 600f

/**
 * Decides which template shows the lyrics, from the facts about the surface.
 *
 * Pure, so the split-screen cases are unit-testable — the screen itself is
 * the only caller, and it passes in what it reads from [CarSurfaceState]
 * and the host:
 *
 * - [surfaceLive]: the host has granted a surface and it is being drawn on.
 * - [apiLevel]: the host's car-app API level. MapWithContentTemplate needs
 *   level 7, and a surface arrives from level 5, so there is a real window
 *   where the app can be drawing happily and still have no template able
 *   to carry it.
 * - [surfaceWidthDp]: the granted surface's width in dp, or null when no
 *   surface is live. A live but narrow surface is a split-screen /
 *   dashboard slot: the host is showing the app in a corner, and the
 *   three-line card is the template that fits there — the surface drawing
 *   would be a full-screen layout squeezed into a third of the screen.
 * - [visibleAreaEmpty]: the host reported a visible area that is empty —
 *   it granted a surface but is not actually showing it, so the surface
 *   drawing would be invisible and the card is the answer. False when the
 *   host has not reported an area yet (null): an unreported area does NOT
 *   block the surface template. v1.0.19 gated on the area being reported
 *   first, and that deadlocked the big screen for the whole session —
 *   the visible area is only ever reported once the surface template is
 *   on screen, so waiting for the report before choosing the surface
 *   template made the surface unreachable and left the fallback as the
 *   only screen the car ever showed. The surface *content* still waits
 *   for the area before drawing any lyrics (it draws black until then,
 *   v1.0.18), so the first drawn frame is already in the right place and
 *   nothing shifts; the template choice itself must not wait.
 * - [screenStarted]: the screen's own lifecycle is at least STARTED —
 *   the app is the full-screen foreground app. When the screen is not
 *   started (covered — the dashboard / split-screen slot on this car),
 *   the host is showing the app in a corner even if it kept the surface
 *   live and wide, so the three-line card is the answer and the surface
 *   template is not. This is the input that catches the case the width
 *   and visible-area checks cannot: a host that never takes the surface
 *   away in split-screen. Flicker risk during transitions is small: the
 *   screen flips STARTED/STOPPED exactly when the host moves the app
 *   between full-screen and the slot, which is when the template should
 *   change anyway, and the tick loop's redraw gap dampens rapid swaps.
 *
 * The visible area arrives as an android.graphics.Rect, which cannot run
 * on the JVM, so the screen maps it to this boolean before calling here
 * (null or non-empty → false, empty rect → true).
 */
internal fun chooseTemplate(
    surfaceLive: Boolean,
    apiLevel: Int,
    surfaceWidthDp: Float?,
    visibleAreaEmpty: Boolean,
    screenStarted: Boolean,
): TemplateChoice {
    if (!screenStarted) return TemplateChoice.FALLBACK
    if (!surfaceLive) return TemplateChoice.FALLBACK
    if (apiLevel < CarAppApiLevels.LEVEL_7) return TemplateChoice.FALLBACK
    if (visibleAreaEmpty) return TemplateChoice.FALLBACK
    if (surfaceWidthDp != null && surfaceWidthDp < SPLIT_SCREEN_WIDTH_DP) {
        return TemplateChoice.FALLBACK
    }
    return TemplateChoice.ON_SURFACE
}
