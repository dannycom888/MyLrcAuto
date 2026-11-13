package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.car.CarGlanceKind
import com.dannycom888.mylrcauto.car.CarPaneKind
import com.dannycom888.mylrcauto.car.CarRenderRecord
import com.dannycom888.mylrcauto.car.carPaneKind
import com.dannycom888.mylrcauto.car.carRenderRecord
import com.dannycom888.mylrcauto.car.carRenderRecordFromPrefsMap
import com.dannycom888.mylrcauto.car.kind
import com.dannycom888.mylrcauto.car.sameRenderAs
import com.dannycom888.mylrcauto.car.toPrefsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The big-screen render record (v1.0.27): the pure parts — which
 * pane a set of facts means, how an exception flattens into the
 * record, the change-dedupe the screen records through, and the
 * persistence round-trip. The three title-and-artist-only panes
 * the 2026-11-13 car photo could not tell apart (surface with a
 * dead surface, neutral fallback, emergency) must stay three
 * different records here.
 */
class CarRenderDiagnosticsTest {

    private val now = CarGlance.Now(
        previous = "上一句",
        line = "正在唱的这一句",
        next = "下一句",
        instrumental = false,
    )

    private fun record(
        onSurface: Boolean = false,
        glance: CarGlance = now,
        error: Throwable? = null,
        lineCount: Int = 43,
    ) = carRenderRecord(
        timestampMs = 1_763_000_000_000L,
        onSurface = onSurface,
        glance = glance,
        lineCount = lineCount,
        surfaceLive = true,
        surfaceWidthDp = 1280f,
        visibleAreaReported = true,
        visibleAreaEmpty = false,
        screenStarted = true,
        apiLevel = 7,
        error = error,
    )

    // ---- the pane derivation ---------------------------------------------

    @Test
    fun surfaceContentIsTheSurfacePane() {
        assertEquals(CarPaneKind.ON_SURFACE, record(onSurface = true).pane)
    }

    @Test
    fun fallbackWithLyricsIsTheLyricsPane() {
        assertEquals(CarPaneKind.LYRICS, record(glance = now).pane)
    }

    @Test
    fun lookingAndNoMatchAreTheNeutralPane() {
        // The neutral pane is the shape the broken big screen
        // showed; it must not be confused with the other two.
        assertEquals(CarPaneKind.NEUTRAL, record(glance = CarGlance.Looking).pane)
        assertEquals(CarPaneKind.NEUTRAL, record(glance = CarGlance.None).pane)
    }

    @Test
    fun silentUntimedAndNoPermissionAreMessagePanes() {
        assertEquals(CarPaneKind.MESSAGE, record(glance = CarGlance.Silent).pane)
        assertEquals(CarPaneKind.MESSAGE, record(glance = CarGlance.Untimed).pane)
        assertEquals(CarPaneKind.MESSAGE, record(glance = CarGlance.NoPermission).pane)
    }

    @Test
    fun aFailureIsTheEmergencyPaneWhateverTheContentWas() {
        val boom = IllegalStateException("bad template")
        assertEquals(CarPaneKind.EMERGENCY, record(error = boom).pane)
        assertEquals(
            CarPaneKind.EMERGENCY,
            record(onSurface = true, glance = now, error = boom).pane,
        )
        assertEquals(CarPaneKind.EMERGENCY, carPaneKind(true, now, failed = true))
    }

    // ---- the glance vocabulary -------------------------------------------

    @Test
    fun everyGlanceHasItsKind() {
        assertEquals(CarGlanceKind.NOW, now.kind())
        assertEquals(CarGlanceKind.LOOKING, CarGlance.Looking.kind())
        assertEquals(CarGlanceKind.NONE, CarGlance.None.kind())
        assertEquals(CarGlanceKind.SILENT, CarGlance.Silent.kind())
        assertEquals(CarGlanceKind.UNTIMED, CarGlance.Untimed.kind())
        assertEquals(CarGlanceKind.NO_PERMISSION, CarGlance.NoPermission.kind())
    }

    // ---- the error flattening ---------------------------------------------

    @Test
    fun theFallbackCauseIsRecorded() {
        val r = record(error = IllegalArgumentException("Action strip must contain at least one action"))
        assertEquals("IllegalArgumentException", r.errorClass)
        assertEquals("Action strip must contain at least one action", r.errorMessage)
    }

    @Test
    fun aNormalRenderHasNoCause() {
        val r = record()
        assertNull(r.errorClass)
        assertNull(r.errorMessage)
    }

    @Test
    fun aLongErrorMessageIsTruncated() {
        val r = record(error = RuntimeException("x".repeat(500)))
        assertEquals(160, r.errorMessage?.length)
    }

    @Test
    fun anErrorWithoutAMessageKeepsItsClass() {
        val r = record(error = RuntimeException())
        assertEquals("RuntimeException", r.errorClass)
        assertNull(r.errorMessage)
    }

    // ---- the dedupe ---------------------------------------------------------

    @Test
    fun theSameRenderLaterIsTheSameRecord() {
        val a = record()
        val b = a.copy(timestampMs = a.timestampMs + 60_000L)
        assertTrue(b.sameRenderAs(a))
    }

    @Test
    fun aChangedPaneOrErrorIsANewRecord() {
        val a = record()
        assertFalse(record(glance = CarGlance.None).sameRenderAs(a))
        assertFalse(record(error = RuntimeException("x")).sameRenderAs(a))
        assertFalse(a.sameRenderAs(null))
    }

    // ---- persistence --------------------------------------------------------

    @Test
    fun recordSurvivesThePrefsRoundTrip() {
        val full = record(error = IllegalStateException("boom"))
        assertEquals(full, carRenderRecordFromPrefsMap(full.toPrefsMap()))
    }

    @Test
    fun absentWidthAndErrorRoundTripAsAbsent() {
        val bare = CarRenderRecord(
            timestampMs = 5L,
            pane = CarPaneKind.NEUTRAL,
            glance = CarGlanceKind.LOOKING,
            lineCount = 0,
            surfaceLive = false,
            surfaceWidthDp = null,
            visibleAreaReported = false,
            visibleAreaEmpty = false,
            screenStarted = false,
            apiLevel = 6,
            errorClass = null,
            errorMessage = null,
        )
        val restored = carRenderRecordFromPrefsMap(bare.toPrefsMap())
        assertEquals(bare, restored)
        assertNull(restored?.surfaceWidthDp)
        assertNull(restored?.errorClass)
    }

    @Test
    fun emptyMapIsNoRecord() {
        assertNull(carRenderRecordFromPrefsMap(emptyMap()))
    }

    @Test
    fun brokenEnumValuesAreNoRecord() {
        // A record written by a newer build (unknown enum names)
        // must read as "no record", not crash the Settings screen.
        val values = record().toPrefsMap().toMutableMap()
        values["pane"] = "HOLOGRAM"
        assertNull(carRenderRecordFromPrefsMap(values))
        val values2 = record().toPrefsMap().toMutableMap()
        values2["glance"] = "TELEPATHIC"
        assertNull(carRenderRecordFromPrefsMap(values2))
    }
}
