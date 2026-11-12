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
        // A 140dp column is narrower than margin + cover + margin (212dp).
        assertNull(belowPaneCoverPlacement(areaLeftPx = 280, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun normalColumnPuts180dpCoverBelowThePaneMidline() {
        // On this car the visible area starts ~60% across: a 2000px-wide
        // surface at density 2.0 has a 1200px pane column = 600dp.
        // The cover is 180dp, and its top edge (240dp) sits well below
        // the pane's midline (50dp) with clear space under the pane.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 2f)
        assertEquals(16f, placed?.leftDp)
        assertEquals(240f, placed?.topDp)
        assertEquals(180f, placed?.sizeDp)
        assertTrue(placed!!.topDp > BELOW_PANE_MIDLINE_DP)
    }

    @Test
    fun narrowButFittingColumnKeepsFullSizeCover() {
        // A 220dp column fits the 180dp cover with margins (220 - 32 =
        // 188 >= 180), so the cover stays 180dp.
        val placed = belowPaneCoverPlacement(areaLeftPx = 440, surfaceHeightPx = 1400, density = 2f)
        assertEquals(180f, placed?.sizeDp)
        assertEquals(16f, placed?.leftDp)
        assertEquals(240f, placed?.topDp)
    }

    @Test
    fun columnAtTheBoundary() {
        // Exactly margin + cover + margin (212dp): the cover fits.
        val fits = belowPaneCoverPlacement(areaLeftPx = 424, surfaceHeightPx = 1400, density = 2f)
        assertEquals(180f, fits?.sizeDp)
        // One dp narrower: no cover at all.
        assertNull(belowPaneCoverPlacement(areaLeftPx = 422, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun shortScreenClampsTheCoverToFit() {
        // A 300dp-tall surface: 240dp + 180dp would overflow, so the top
        // moves up to 104dp and the cover still ends at the bottom margin.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 600, density = 2f)
        assertEquals(104f, placed?.topDp)
        assertEquals(180f, placed?.sizeDp)
    }

    @Test
    fun zeroDensityOrHeightHoldsNoCover() {
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 0f))
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 0, density = 2f))
    }
}
