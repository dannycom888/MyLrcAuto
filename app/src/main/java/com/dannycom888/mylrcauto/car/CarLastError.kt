package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last exception on a car entry path, kept STICKY for the
 * phone's Settings screen (v1.0.30) — the instrument the
 * 2026-11-13 20:06 car run proved necessary.
 *
 * That run's defect — the host's own error screen ("出现意外
 * 错误" + 退出， a host drawing, not app text; the app's own
 * error strings were deleted in v1.0.19) flashing on big-screen
 * entry after a Poweramp playlist switch, ~3s the first time, a
 * flash on repeats, then full recovery — left NO trace: the
 * render record written afterwards showed 回退原因 （无）. Of
 * course it did: the render record is overwritten by every
 * render change, so a transient failure followed by a healthy
 * render erases itself, and if the failure was a process death
 * the record the new process writes describes only the recovery.
 *
 * This record is the opposite shape: written ONLY on failure,
 * never cleared by success, overwritten only by a newer failure.
 * One Settings screenshot after a bad run names the callback,
 * the exception class and its message — or, when the row is
 * empty after a flash, proves the failure never passed through
 * app code at all (a host-side template timeout), which is the
 * other half of the evidence.
 *
 * Writes are synchronous (`commit()`, not `apply()`): the
 * record exists to survive the process death it may be
 * diagnosing, and failures are rare — the dedupe in
 * [shouldRecordCarError] keeps a persistent failure from
 * rewriting the same fact on every retry.
 *
 * The record, its flattening, its formatting and its
 * persistence encoding are pure and unit-tested;
 * [CarLastErrorStore] is only the SharedPreferences shell.
 */

/** Where a recorded failure happened — the car entry paths, by callback name. */
object CarErrorSite {
    const val ON_CREATE_SESSION = "onCreateSession"
    const val ON_CREATE_SCREEN = "onCreateScreen"
    const val ON_NEW_INTENT = "onNewIntent"
    const val ON_GET_TEMPLATE = "onGetTemplate"
    const val EMERGENCY_PANE = "emergencyPane"
    const val ON_SURFACE_AVAILABLE = "onSurfaceAvailable"
    const val ON_VISIBLE_AREA_CHANGED = "onVisibleAreaChanged"
    const val ON_SURFACE_DESTROYED = "onSurfaceDestroyed"
    const val SESSION_START = "sessionStart"
    const val BACK_PRESSED = "backPressed"
    const val COMPOSE_STATE = "composeState"
    const val COMPOSE_TICK = "composeTick"

    /** An exception no entry-path guard caught — recorded by the chained default handler. */
    const val UNCAUGHT = "uncaught"
}

/** One recorded car entry-path failure. */
data class CarLastErrorRecord(
    /** Wall-clock time of the failure, epoch milliseconds. */
    val timestampMs: Long,
    /** The callback the failure escaped (or was caught) at — see [CarErrorSite]. */
    val site: String,
    /** The exception's class simple name. */
    val errorClass: String,
    /** The exception's message, truncated; null when it had none. */
    val errorMessage: String?,
)

/** Cap on a recorded exception message — the same budget as the render record's. */
private const val MAX_LAST_ERROR_MESSAGE_CHARS = 160

/** Flattens [error] at [site] into a record. */
fun carLastErrorRecord(
    timestampMs: Long,
    site: String,
    error: Throwable,
): CarLastErrorRecord = CarLastErrorRecord(
    timestampMs = timestampMs,
    site = site,
    errorClass = error.javaClass.simpleName,
    errorMessage = error.message?.take(MAX_LAST_ERROR_MESSAGE_CHARS),
)

/**
 * The Settings row's value for [record]: time, site, exception.
 * [timeText] is preformatted by the caller (the same formatter
 * the other diagnostics rows use), so this stays pure.
 */
fun formatCarLastError(record: CarLastErrorRecord, timeText: String): String =
    if (record.errorMessage != null) {
        "$timeText · ${record.site} · ${record.errorClass}: ${record.errorMessage}"
    } else {
        "$timeText · ${record.site} · ${record.errorClass}"
    }

/**
 * Whether [next] should replace [last] in the store: a repeat of
 * the same failure (same site, class and message — a tick loop
 * hitting the same wall every 500ms) is not newer information,
 * and rewriting it would only churn the synchronous write.
 */
fun shouldRecordCarError(last: CarLastErrorRecord?, next: CarLastErrorRecord): Boolean =
    last == null ||
        last.site != next.site ||
        last.errorClass != next.errorClass ||
        last.errorMessage != next.errorMessage

/** The record as preference values, so the round-trip is unit-testable without Android. */
fun CarLastErrorRecord.toPrefsMap(): Map<String, Any?> = buildMap {
    put(KEY_TIMESTAMP_MS, timestampMs)
    put(KEY_SITE, site)
    put(KEY_ERROR_CLASS, errorClass)
    errorMessage?.let { put(KEY_ERROR_MESSAGE, it) }
}

/** The inverse of [toPrefsMap]; null when the map holds no (or a broken) record. */
fun carLastErrorRecordFromPrefsMap(values: Map<String, Any?>): CarLastErrorRecord? {
    val timestampMs = values[KEY_TIMESTAMP_MS] as? Long ?: return null
    val site = values[KEY_SITE] as? String ?: return null
    val errorClass = values[KEY_ERROR_CLASS] as? String ?: return null
    return CarLastErrorRecord(
        timestampMs = timestampMs,
        site = site,
        errorClass = errorClass,
        errorMessage = values[KEY_ERROR_MESSAGE] as? String,
    )
}

private const val KEY_TIMESTAMP_MS = "timestamp_ms"
private const val KEY_SITE = "site"
private const val KEY_ERROR_CLASS = "error_class"
private const val KEY_ERROR_MESSAGE = "error_message"

/**
 * SharedPreferences-backed holder for the most recent
 * [CarLastErrorRecord]. Its own file, separate from the render
 * record's: the two have opposite lifetimes (the render record
 * tracks the current pane; this one sticks to the last failure).
 */
class CarLastErrorStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())
    val last: StateFlow<CarLastErrorRecord?> = _last.asStateFlow()

    private fun read(): CarLastErrorRecord? = carLastErrorRecordFromPrefsMap(prefs.all)

    /**
     * Stores [record] synchronously. `commit()` over `apply()`:
     * this write may be the last thing the process does before
     * the host restarts it, and the record is the evidence.
     */
    fun record(record: CarLastErrorRecord) {
        if (!shouldRecordCarError(_last.value, record)) return
        val editor = prefs.edit().clear()
        for ((key, value) in record.toPrefsMap()) {
            when (value) {
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                null -> Unit
            }
        }
        editor.commit()
        _last.value = record
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-car-last-error"
    }
}
