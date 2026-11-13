package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last car launch, kept for the phone's Settings screen —
 * the falsifiable instrument behind the item-7 heuristic
 * (v1.0.21).
 *
 * The launch decision ([decideLaunch]) rests on one unproven
 * assumption: that a card tap's intent differs from a plain icon
 * tap's. This record is how that assumption gets checked on the
 * user's real head unit: every car launch (new session or new
 * intent) stores its decision inputs and the decision taken, and
 * Settings shows the most recent one. The user taps the icon,
 * screenshots Settings; taps the card, screenshots Settings —
 * and the two records say what his car actually sends for each.
 *
 * Only the latest launch is kept: one record is the whole point
 * (compare the last icon tap with the last card tap), and a
 * history would need a list UI nobody asked for.
 *
 * The record, its persistence encoding and its time formatting
 * are pure and unit-tested; [LaunchDiagnosticsStore] is only the
 * SharedPreferences shell around them.
 */
data class LaunchRecord(
    /** Wall-clock time of the launch, epoch milliseconds. */
    val timestampMs: Long,
    val entry: LaunchEntry,
    val intentAction: String?,
    val intentCategories: Set<String>,
    val intentExtrasKeys: Set<String>,
    /** The raw intent flags — recorded because the decision does not look at them, and a card/icon difference could be hiding there. */
    val intentFlags: Int,
    val mirrorAlreadyActive: Boolean,
    val bigScreenShownThisConnection: Boolean,
    val decision: LaunchDecision,
)

/** `yyyy-MM-dd HH:mm:ss` in the device's locale/timezone — what the user reads in a screenshot. */
fun formatLaunchTimestamp(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

/**
 * The record as preference values, so the encoding round-trip is
 * unit-testable without Android: strings, string sets, a long, an
 * int and booleans under fixed keys. A null action is stored as
 * an absent key.
 */
fun LaunchRecord.toPrefsMap(): Map<String, Any?> = buildMap {
    put(KEY_TIMESTAMP_MS, timestampMs)
    put(KEY_ENTRY, entry.name)
    intentAction?.let { put(KEY_ACTION, it) }
    put(KEY_CATEGORIES, intentCategories)
    put(KEY_EXTRAS_KEYS, intentExtrasKeys)
    put(KEY_FLAGS, intentFlags)
    put(KEY_MIRROR_ACTIVE, mirrorAlreadyActive)
    put(KEY_BIG_SCREEN_SHOWN, bigScreenShownThisConnection)
    put(KEY_DECISION, decision.name)
}

/** The inverse of [toPrefsMap]; null when the map holds no (or a broken) record. */
fun launchRecordFromPrefsMap(values: Map<String, Any?>): LaunchRecord? {
    val timestampMs = values[KEY_TIMESTAMP_MS] as? Long ?: return null
    val entry = runCatching {
        LaunchEntry.valueOf(values[KEY_ENTRY] as? String ?: return null)
    }.getOrNull() ?: return null
    val decision = runCatching {
        LaunchDecision.valueOf(values[KEY_DECISION] as? String ?: return null)
    }.getOrNull() ?: return null

    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String> =
        (values[key] as? Set<String>).orEmpty()

    return LaunchRecord(
        timestampMs = timestampMs,
        entry = entry,
        intentAction = values[KEY_ACTION] as? String,
        intentCategories = stringSet(KEY_CATEGORIES),
        intentExtrasKeys = stringSet(KEY_EXTRAS_KEYS),
        intentFlags = values[KEY_FLAGS] as? Int ?: 0,
        mirrorAlreadyActive = values[KEY_MIRROR_ACTIVE] as? Boolean ?: false,
        bigScreenShownThisConnection = values[KEY_BIG_SCREEN_SHOWN] as? Boolean ?: false,
        decision = decision,
    )
}

private const val KEY_TIMESTAMP_MS = "timestamp_ms"
private const val KEY_ENTRY = "entry"
private const val KEY_ACTION = "action"
private const val KEY_CATEGORIES = "categories"
private const val KEY_EXTRAS_KEYS = "extras_keys"
private const val KEY_FLAGS = "flags"
private const val KEY_MIRROR_ACTIVE = "mirror_active"
private const val KEY_BIG_SCREEN_SHOWN = "big_screen_shown"
private const val KEY_DECISION = "decision"

/**
 * SharedPreferences-backed holder for the most recent
 * [LaunchRecord]. Its own file, not the settings file: this is a
 * diagnostic, not a setting, and it changes on every car launch
 * rather than on a user choice.
 */
class LaunchDiagnosticsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())
    val last: StateFlow<LaunchRecord?> = _last.asStateFlow()

    private fun read(): LaunchRecord? = launchRecordFromPrefsMap(prefs.all)

    fun record(record: LaunchRecord) {
        val editor = prefs.edit().clear()
        for ((key, value) in record.toPrefsMap()) {
            when (value) {
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> Unit
            }
        }
        editor.apply()
        _last.value = record
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-launch-diagnostics"
    }
}
