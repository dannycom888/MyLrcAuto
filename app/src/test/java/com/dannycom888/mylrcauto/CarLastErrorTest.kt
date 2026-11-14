package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarErrorSite
import com.dannycom888.mylrcauto.car.CarLastErrorRecord
import com.dannycom888.mylrcauto.car.carLastErrorRecord
import com.dannycom888.mylrcauto.car.carLastErrorRecordFromPrefsMap
import com.dannycom888.mylrcauto.car.formatCarLastError
import com.dannycom888.mylrcauto.car.shouldRecordCarError
import com.dannycom888.mylrcauto.car.toPrefsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky last-error record (v1.0.30): the pure parts — how
 * an exception flattens, how the Settings row reads, when a
 * repeat is not worth rewriting, and the persistence
 * round-trip. The record exists because the 2026-11-13
 * playlist-switch flash left nothing behind: the render record
 * is overwritten by the recovery, so the failure that matters
 * needs a store success never clears.
 */
class CarLastErrorTest {

    @Test
    fun flattensAnExceptionIntoSiteClassAndMessage() {
        val record = carLastErrorRecord(
            timestampMs = 1_763_000_000_000L,
            site = CarErrorSite.ON_SURFACE_AVAILABLE,
            error = IllegalStateException("display already released"),
        )
        assertEquals(1_763_000_000_000L, record.timestampMs)
        assertEquals("onSurfaceAvailable", record.site)
        assertEquals("IllegalStateException", record.errorClass)
        assertEquals("display already released", record.errorMessage)
    }

    @Test
    fun anExceptionWithoutAMessageRecordsNull() {
        val record = carLastErrorRecord(
            timestampMs = 1L,
            site = CarErrorSite.ON_GET_TEMPLATE,
            error = RuntimeException(),
        )
        assertEquals("RuntimeException", record.errorClass)
        assertNull(record.errorMessage)
    }

    @Test
    fun aLongMessageIsTruncatedToTheBudget() {
        val record = carLastErrorRecord(
            timestampMs = 1L,
            site = CarErrorSite.UNCAUGHT,
            error = IllegalArgumentException("x".repeat(500)),
        )
        assertEquals(160, record.errorMessage!!.length)
    }

    @Test
    fun formatIncludesTimeSiteClassAndMessage() {
        val record = CarLastErrorRecord(
            timestampMs = 1L,
            site = "onSurfaceAvailable",
            errorClass = "InvalidDisplayException",
            errorMessage = "display is invalid",
        )
        assertEquals(
            "20:06:55 · onSurfaceAvailable · InvalidDisplayException: display is invalid",
            formatCarLastError(record, "20:06:55"),
        )
    }

    @Test
    fun formatOmitsTheMessagePartWhenThereIsNone() {
        val record = CarLastErrorRecord(
            timestampMs = 1L,
            site = "uncaught",
            errorClass = "NullPointerException",
            errorMessage = null,
        )
        assertEquals(
            "20:06:55 · uncaught · NullPointerException",
            formatCarLastError(record, "20:06:55"),
        )
    }

    @Test
    fun theFirstFailureIsAlwaysRecorded() {
        val next = CarLastErrorRecord(1L, "onNewIntent", "IllegalStateException", "boom")
        assertTrue(shouldRecordCarError(null, next))
    }

    @Test
    fun anIdenticalRepeatIsNotRewritten() {
        val last = CarLastErrorRecord(1L, "composeTick", "IllegalStateException", "boom")
        val repeat = CarLastErrorRecord(2L, "composeTick", "IllegalStateException", "boom")
        assertFalse(shouldRecordCarError(last, repeat))
    }

    @Test
    fun aDifferentSiteClassOrMessageIsRecorded() {
        val last = CarLastErrorRecord(1L, "composeTick", "IllegalStateException", "boom")
        assertTrue(
            shouldRecordCarError(
                last,
                CarLastErrorRecord(2L, "onGetTemplate", "IllegalStateException", "boom"),
            ),
        )
        assertTrue(
            shouldRecordCarError(
                last,
                CarLastErrorRecord(2L, "composeTick", "IllegalArgumentException", "boom"),
            ),
        )
        assertTrue(
            shouldRecordCarError(
                last,
                CarLastErrorRecord(2L, "composeTick", "IllegalStateException", "different"),
            ),
        )
        assertTrue(
            shouldRecordCarError(
                last,
                CarLastErrorRecord(2L, "composeTick", "IllegalStateException", null),
            ),
        )
    }

    @Test
    fun prefsRoundTripKeepsEveryField() {
        val record = CarLastErrorRecord(
            timestampMs = 1_763_000_000_000L,
            site = "onCreateScreen",
            errorClass = "SecurityException",
            errorMessage = "listener not bound",
        )
        assertEquals(record, carLastErrorRecordFromPrefsMap(record.toPrefsMap()))
    }

    @Test
    fun prefsRoundTripWithoutAMessageStaysNull() {
        val record = CarLastErrorRecord(5L, "backPressed", "IllegalStateException", null)
        val restored = carLastErrorRecordFromPrefsMap(record.toPrefsMap())
        assertEquals(record, restored)
        assertNull(restored!!.errorMessage)
    }

    @Test
    fun aBrokenPrefsMapReadsAsNoRecord() {
        assertNull(carLastErrorRecordFromPrefsMap(emptyMap()))
        assertNull(
            carLastErrorRecordFromPrefsMap(
                mapOf("timestamp_ms" to 1L, "site" to "uncaught"),
            ),
        )
    }
}
