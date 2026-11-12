package com.dannycom888.mylrcauto.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BelowPaneCoverTest {

    @Test
    fun noVisibleAreaYetHoldsNoCover() {
        assertNull(belowPaneCoverPlacement(areaLeftPx = null, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun narrowColumnHoldsNoCover() {
        // A 140dp column is narrower than margin + cover + margin (272dp).
        assertNull(belowPaneCoverPlacement(areaLeftPx = 280, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun normalColumnPuts240dpCoverBelowThePane() {
        // On this car the visible area starts ~60% across: a 2000px-wide
        // surface at density 2.0 has a 1200px pane column = 600dp.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 2f)
        assertEquals(16f, placed?.leftDp)
        assertEquals(200f, placed?.topDp)
        assertEquals(240f, placed?.sizeDp)
    }

    @Test
    fun narrowButFittingColumnKeepsFullSizeCover() {
        // A 280dp column fits the 240dp cover with margins (280 - 32 =
        // 248 >= 240), so the cover stays 240dp.
        val placed = belowPaneCoverPlacement(areaLeftPx = 560, surfaceHeightPx = 1400, density = 2f)
        assertEquals(240f, placed?.sizeDp)
        assertEquals(16f, placed?.leftDp)
        assertEquals(200f, placed?.topDp)
    }

    @Test
    fun columnAtTheBoundary() {
        // Exactly margin + cover + margin (272dp): the cover fits.
        val fits = belowPaneCoverPlacement(areaLeftPx = 544, surfaceHeightPx = 1400, density = 2f)
        assertEquals(240f, fits?.sizeDp)
        // One dp narrower: no cover at all.
        assertNull(belowPaneCoverPlacement(areaLeftPx = 542, surfaceHeightPx = 1400, density = 2f))
    }

    @Test
    fun shortScreenClampsTheCoverToFit() {
        // A 300dp-tall surface: 200dp + 240dp would overflow, so the top
        // moves up to 44dp and the cover still ends at the bottom margin.
        val placed = belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 600, density = 2f)
        assertEquals(44f, placed?.topDp)
        assertEquals(240f, placed?.sizeDp)
    }

    @Test
    fun zeroDensityOrHeightHoldsNoCover() {
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 1400, density = 0f))
        assertNull(belowPaneCoverPlacement(areaLeftPx = 1200, surfaceHeightPx = 0, density = 2f))
    }
}
