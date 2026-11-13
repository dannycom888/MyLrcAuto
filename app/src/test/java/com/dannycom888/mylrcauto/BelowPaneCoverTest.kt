package com.dannycom888.mylrcauto.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BelowPaneCoverTest {

    @Test
    fun noVisibleAreaYetHoldsNoCover() {
        assertNull(belowPaneCoverPlacement(areaLeftPx = null, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun narrowColumnHoldsNoCover() {
        // A 140dp column is narrower than margin + cover + margin (232dp).
        assertNull(belowPaneCoverPlacement(areaLeftPx = 280, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun normalColumnPuts200dpCoverBelowThePaneMidline() {
        // On this car the visible area starts ~60% across: a 2000px-wide
        // surface at density 2.0 has a 1200px pane column = 600dp.
        // The cover is 200dp, and its top edge (240dp) sits well below
        // the pane's midline (50dp) with clear space under the pane.
        // The cover is centred in the pane column (200dp from the left
        // edge), under the pane — v1.0.17 left-aligned it at the 16dp
        // margin and on the car it read as "on the left side" rather
        // than below the pane.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 2f)
        assertEquals(200f, placed?.leftDp)
        assertEquals(240f, placed?.topDp)
        assertEquals(200f, placed?.sizeDp)
        assertTrue(placed!!.topDp > BELOW_PANE_MIDLINE_DP)
    }

    @Test
    fun narrowButFittingColumnKeepsFullSizeCover() {
        // A 240dp column fits the 200dp cover with margins (240 - 32 =
        // 208 >= 200), so the cover stays 200dp, centred at 20dp.
        val placed = belowPaneCoverPlacement(areaLeftPx = 480, surfaceHeightPx = 1400, density = 2f)
        assertEquals(200f, placed?.sizeDp)
        assertEquals(20f, placed?.leftDp)
        assertEquals(240f, placed?.topDp)
    }

    @Test
    fun columnAtTheBoundary() {
        // Exactly margin + cover + margin (232dp): the cover fits.
        val fits = belowPaneCoverPlacement(areaLeftPx = 464, surfaceHeightPx = 1400, density = 2f)
        assertEquals(200f, fits?.sizeDp)
        // One dp narrower: no cover at all.
        assertNull(belowPaneCoverPlacement(areaLeftPx = 462, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun shortScreenClampsTheCoverToFit() {
        // A 300dp-tall surface: 240dp + 200dp would overflow, so the top
        // moves up to 84dp and the cover still ends at the bottom margin.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 600, density = 2f)
        assertEquals(84f, placed?.topDp)
        assertEquals(200f, placed?.sizeDp)
    }

    @Test
    fun zeroDensityOrHeightHoldsNoCover() {
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 0f))
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 0, density = 2f))
    }
}
