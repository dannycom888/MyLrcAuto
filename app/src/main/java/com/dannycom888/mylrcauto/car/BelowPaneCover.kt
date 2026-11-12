package com.dannycom888.mylrcauto.car

/**
 * Where the small cover below the pane goes, in dp from the surface's
 * top-left corner.
 */
data class BelowPaneCover(
    val leftDp: Float,
    val topDp: Float,
    val sizeDp: Float,
)

/**
 * The small cover below the pane (the top-left box), in the pane column.
 *
 * This was judged infeasible in v1.0.10 ("the area under the pane is
 * host-reserved, there is nowhere below the pane to draw") — and that was
 * wrong. What the code actually says:
 *
 * - The host grants a surface ([CarSurface] builds the VirtualDisplay
 *   from the full `SurfaceContainer` width and height); the visible area
 *   is only the *unobstructed* part of it. On this car the visible area
 *   starts ~60% across the screen, so its left edge proves the surface
 *   extends left of it — the pane column, below the pane box included,
 *   is real drawable surface.
 * - The visible area is a single Rect, which cannot be L-shaped, so the
 *   host reports the largest clean rectangle (right of the pane column).
 *   The region below the pane box is not covered by the pane itself; it
 *   is just not in the reported rect.
 * - The user's Melisma photos prove the same thing from the other side:
 *   Melisma's blurred background fills the whole screen, including below
 *   its pane box — the host draws nothing there.
 *
 * So the cover goes in the pane column, below the pane box, at a fixed
 * offset that clears it: the pane holds one title row plus the artist,
 * ~100dp tall on any car, so its horizontal midline sits at ~50dp. The
 * cover's top edge goes well below that midline — the pane bottom
 * (~100dp) plus a clear gap — so the cover sits lower with clear space
 * between the pane and the cover instead of hugging the pane's bottom
 * edge. The lyrics then take the visible area's full width (no cover
 * column beside them).
 *
 * Pure float math, so it is unit-testable on the JVM: [areaLeftPx] is the
 * visible area's left edge in surface pixels (null when the host has not
 * reported an area yet), [surfaceHeightPx] the surface height, [density]
 * the surface density (dpi / 160).
 */
fun belowPaneCoverPlacement(
    areaLeftPx: Int?,
    surfaceHeightPx: Int,
    density: Float,
): BelowPaneCover? {
    if (areaLeftPx == null || density <= 0f || surfaceHeightPx <= 0) return null
    val columnWidthDp = areaLeftPx / density
    // A column too narrow for a cover plus margins holds no cover at all.
    // The gate guarantees the full-size cover fits, so there is no
    // shrinking: the cover is always BELOW_PANE_COVER_SIZE_DP.
    if (columnWidthDp < BELOW_PANE_MIN_COLUMN_DP) return null
    val sizeDp = BELOW_PANE_COVER_SIZE_DP
    val surfaceHeightDp = surfaceHeightPx / density
    // Below the pane's midline, and clamped so the cover still fits on a
    // short screen.
    val topDp = minOf(
        BELOW_PANE_TOP_DP,
        (surfaceHeightDp - sizeDp - BELOW_PANE_MARGIN_DP).coerceAtLeast(0f),
    )
    return BelowPaneCover(
        leftDp = BELOW_PANE_MARGIN_DP,
        topDp = topDp,
        sizeDp = sizeDp,
    )
}

/** The small cover's size: 180dp, down from the 240dp of v1.0.15. */
const val BELOW_PANE_COVER_SIZE_DP = 180f

/**
 * The pane box holds one title row plus the artist, ~100dp tall on any
 * car (see above); its horizontal midline is half that.
 */
const val BELOW_PANE_HEIGHT_DP = 100f

/** The pane's horizontal midline, from the surface top. */
const val BELOW_PANE_MIDLINE_DP = BELOW_PANE_HEIGHT_DP / 2f

/**
 * Top offset from the surface top: the pane bottom (~100dp) plus a
 * 140dp clear gap, so the cover's top edge (240dp) sits well below the
 * pane's midline (50dp) with clear space between the pane and the
 * cover, instead of hugging the pane's bottom edge.
 */
const val BELOW_PANE_TOP_DP = 240f

/** Margin around the cover inside the pane column. */
const val BELOW_PANE_MARGIN_DP = 16f

/** Narrowest pane column that still holds a cover: margin + cover + margin. */
const val BELOW_PANE_MIN_COLUMN_DP = BELOW_PANE_COVER_SIZE_DP + 2 * BELOW_PANE_MARGIN_DP
