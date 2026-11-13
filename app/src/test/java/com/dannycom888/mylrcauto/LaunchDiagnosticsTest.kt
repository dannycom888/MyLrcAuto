package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.LaunchDecision
import com.dannycom888.mylrcauto.car.LaunchEntry
import com.dannycom888.mylrcauto.car.LaunchRecord
import com.dannycom888.mylrcauto.car.formatLaunchTimestamp
import com.dannycom888.mylrcauto.car.launchRecordFromPrefsMap
import com.dannycom888.mylrcauto.car.toPrefsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The last-launch diagnostics record (v1.0.21): the pure parts —
 * the persistence round-trip and the timestamp the Settings
 * screen shows. The SharedPreferences shell itself is the same
 * pattern the settings store already uses; everything that can
 * lose or garble a field lives here and is pinned.
 */
class LaunchDiagnosticsTest {

    private val record = LaunchRecord(
        timestampMs = 1_763_596_800_000L,
        entry = LaunchEntry.CREATE_SCREEN,
        intentAction = "android.intent.action.MAIN",
        intentCategories = setOf("android.intent.category.LAUNCHER"),
        intentExtrasKeys = setOf("some.extra.KEY", "another.key"),
        intentFlags = 268_435_456,
        mirrorAlreadyActive = true,
        bigScreenShownThisConnection = false,
        decision = LaunchDecision.FINISH_TO_DASHBOARD,
    )

    @Test
    fun recordSurvivesThePrefsRoundTrip() {
        assertEquals(record, launchRecordFromPrefsMap(record.toPrefsMap()))
    }

    @Test
    fun nullActionRoundTripsAsAbsent() {
        val bare = record.copy(intentAction = null, entry = LaunchEntry.NEW_INTENT)
        val restored = launchRecordFromPrefsMap(bare.toPrefsMap())
        assertEquals(bare, restored)
        assertNull(restored?.intentAction)
    }

    @Test
    fun emptyMapIsNoRecord() {
        assertNull(launchRecordFromPrefsMap(emptyMap()))
    }

    @Test
    fun brokenEnumValuesAreNoRecord() {
        // A record written by a newer build (unknown enum names)
        // must read as "no record", not crash the Settings screen.
        val values = record.toPrefsMap().toMutableMap()
        values["entry"] = "FROM_THE_FUTURE"
        assertNull(launchRecordFromPrefsMap(values))
        val values2 = record.toPrefsMap().toMutableMap()
        values2["decision"] = "EXPLODE"
        assertNull(launchRecordFromPrefsMap(values2))
    }

    @Test
    fun timestampFormatsAsReadableLocalTime() {
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(record.timestampMs)
        val formatted = formatLaunchTimestamp(record.timestampMs)
        assertEquals(expected, formatted)
        // The shape a screenshot shows: date, space, time.
        assertTrue(formatted.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")))
    }
}
