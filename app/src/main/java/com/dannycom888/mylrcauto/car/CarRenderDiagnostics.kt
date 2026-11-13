package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the big screen actually showed, kept for the phone's
 * Settings screen — the falsifiable instrument behind the
 * v1.0.27 big-screen fix.
 *
 * The 2026-11-13 car test came back with the card perfect and
 * the big screen showing only the title and artist, with a back
 * arrow that did nothing. Three different panes produce exactly
 * that picture (the surface template with a dead surface, the
 * neutral fallback pane, the emergency pane), and from a photo
 * there is no telling which one the host was given — the
 * template code's catch hid the reason, and nothing recorded
 * which path ran. So every template the screen returns now
 * leaves a record: which pane it was, what the lyrics side had
 * (the glance and the document's line count), the surface facts
 * the template choice was made from, and — when the catch fired
 * — the exception that caused the fallback. One Settings
 * screenshot after a car run separates the three shapes.
 *
 * Only the latest render is kept: it is overwritten whenever the
 * returned pane or its inputs change (the screen dedupes), so
 * what Settings shows is the current state of the big screen,
 * not a history.
 *
 * The record, its derivation and its persistence encoding are
 * pure and unit-tested; [CarRenderDiagnosticsStore] is only the
 * SharedPreferences shell around them.
 */

/** Which pane [CarLyricsScreen] handed the host. */
enum class CarPaneKind {
    /** MapWithContentTemplate: the app draws the full lyrics on its own surface. */
    ON_SURFACE,

    /** The fallback pane with the previous / current / next lyric rows. */
    LYRICS,

    /** The fallback neutral pane: title and artist only (looking, or no match). */
    NEUTRAL,

    /** A MessageTemplate (silent / untimed / no permission). */
    MESSAGE,

    /** The last-resort pane, returned when composing or building the main pane threw. */
    EMERGENCY,
}

/** The lyrics side of a render, in the record's own vocabulary. */
enum class CarGlanceKind {
    SILENT,
    LOOKING,
    NONE,
    UNTIMED,
    NO_PERMISSION,
    NOW,
}

/** The record's name for a glance. */
fun CarGlance.kind(): CarGlanceKind = when (this) {
    CarGlance.Silent -> CarGlanceKind.SILENT
    CarGlance.Looking -> CarGlanceKind.LOOKING
    CarGlance.None -> CarGlanceKind.NONE
    CarGlance.Untimed -> CarGlanceKind.UNTIMED
    CarGlance.NoPermission -> CarGlanceKind.NO_PERMISSION
    is CarGlance.Now -> CarGlanceKind.NOW
}

/**
 * Which pane a render produced, from the same facts the screen
 * itself used: a failure is the emergency pane whatever the
 * content was; a live-surface content is the surface template;
 * the fallback splits by glance exactly as the screen's own
 * `when` does (Now → lyric rows, Looking/None → the neutral
 * pane, the rest → a message).
 */
fun carPaneKind(onSurface: Boolean, glance: CarGlance, failed: Boolean): CarPaneKind = when {
    failed -> CarPaneKind.EMERGENCY
    onSurface -> CarPaneKind.ON_SURFACE
    glance is CarGlance.Now -> CarPaneKind.LYRICS
    glance == CarGlance.Looking || glance == CarGlance.None -> CarPaneKind.NEUTRAL
    else -> CarPaneKind.MESSAGE
}

/** One big-screen render, as the screen returned it. */
data class CarRenderRecord(
    /** Wall-clock time of the render, epoch milliseconds. */
    val timestampMs: Long,
    val pane: CarPaneKind,
    val glance: CarGlanceKind,
    /** Timed lines in the document the car session had; 0 when it had none. */
    val lineCount: Int,
    /** The surface facts the template choice was made from. */
    val surfaceLive: Boolean,
    val surfaceWidthDp: Float?,
    val visibleAreaReported: Boolean,
    val visibleAreaEmpty: Boolean,
    val screenStarted: Boolean,
    val apiLevel: Int,
    /** The fallback's cause, when the catch fired: exception class simple name. */
    val errorClass: String?,
    /** The fallback's cause, truncated: exception message. */
    val errorMessage: String?,
)

/**
 * Builds the record for one render: the pane derivation and the
 * error flattening live here so the screen only supplies facts.
 * [error] is the exception that sent the render to the emergency
 * pane, or null for a normal return.
 */
fun carRenderRecord(
    timestampMs: Long,
    onSurface: Boolean,
    glance: CarGlance,
    lineCount: Int,
    surfaceLive: Boolean,
    surfaceWidthDp: Float?,
    visibleAreaReported: Boolean,
    visibleAreaEmpty: Boolean,
    screenStarted: Boolean,
    apiLevel: Int,
    error: Throwable?,
): CarRenderRecord = CarRenderRecord(
    timestampMs = timestampMs,
    pane = carPaneKind(onSurface, glance, failed = error != null),
    glance = glance.kind(),
    lineCount = lineCount,
    surfaceLive = surfaceLive,
    surfaceWidthDp = surfaceWidthDp,
    visibleAreaReported = visibleAreaReported,
    visibleAreaEmpty = visibleAreaEmpty,
    screenStarted = screenStarted,
    apiLevel = apiLevel,
    errorClass = error?.javaClass?.simpleName,
    errorMessage = error?.message?.take(MAX_ERROR_MESSAGE_CHARS),
)

/**
 * True when two records describe the same render (everything but
 * the time): the screen records only on a change, so a pane that
 * stays up across a hundred template asks leaves one record.
 */
fun CarRenderRecord.sameRenderAs(other: CarRenderRecord?): Boolean =
    other != null && copy(timestampMs = 0L) == other.copy(timestampMs = 0L)

/** Cap on a recorded exception message: enough to identify it, short enough for a screenshot row. */
private const val MAX_ERROR_MESSAGE_CHARS = 160

/**
 * The record as preference values, so the encoding round-trip is
 * unit-testable without Android. Nulls (width, error fields) are
 * stored as absent keys.
 */
fun CarRenderRecord.toPrefsMap(): Map<String, Any?> = buildMap {
    put(KEY_TIMESTAMP_MS, timestampMs)
    put(KEY_PANE, pane.name)
    put(KEY_GLANCE, glance.name)
    put(KEY_LINE_COUNT, lineCount)
    put(KEY_SURFACE_LIVE, surfaceLive)
    surfaceWidthDp?.let { put(KEY_SURFACE_WIDTH_DP, it) }
    put(KEY_AREA_REPORTED, visibleAreaReported)
    put(KEY_AREA_EMPTY, visibleAreaEmpty)
    put(KEY_SCREEN_STARTED, screenStarted)
    put(KEY_API_LEVEL, apiLevel)
    errorClass?.let { put(KEY_ERROR_CLASS, it) }
    errorMessage?.let { put(KEY_ERROR_MESSAGE, it) }
}

/** The inverse of [toPrefsMap]; null when the map holds no (or a broken) record. */
fun carRenderRecordFromPrefsMap(values: Map<String, Any?>): CarRenderRecord? {
    val timestampMs = values[KEY_TIMESTAMP_MS] as? Long ?: return null
    val pane = runCatching {
        CarPaneKind.valueOf(values[KEY_PANE] as? String ?: return null)
    }.getOrNull() ?: return null
    val glance = runCatching {
        CarGlanceKind.valueOf(values[KEY_GLANCE] as? String ?: return null)
    }.getOrNull() ?: return null
    return CarRenderRecord(
        timestampMs = timestampMs,
        pane = pane,
        glance = glance,
        lineCount = values[KEY_LINE_COUNT] as? Int ?: 0,
        surfaceLive = values[KEY_SURFACE_LIVE] as? Boolean ?: false,
        surfaceWidthDp = values[KEY_SURFACE_WIDTH_DP] as? Float,
        visibleAreaReported = values[KEY_AREA_REPORTED] as? Boolean ?: false,
        visibleAreaEmpty = values[KEY_AREA_EMPTY] as? Boolean ?: false,
        screenStarted = values[KEY_SCREEN_STARTED] as? Boolean ?: false,
        apiLevel = values[KEY_API_LEVEL] as? Int ?: 0,
        errorClass = values[KEY_ERROR_CLASS] as? String,
        errorMessage = values[KEY_ERROR_MESSAGE] as? String,
    )
}

private const val KEY_TIMESTAMP_MS = "timestamp_ms"
private const val KEY_PANE = "pane"
private const val KEY_GLANCE = "glance"
private const val KEY_LINE_COUNT = "line_count"
private const val KEY_SURFACE_LIVE = "surface_live"
private const val KEY_SURFACE_WIDTH_DP = "surface_width_dp"
private const val KEY_AREA_REPORTED = "area_reported"
private const val KEY_AREA_EMPTY = "area_empty"
private const val KEY_SCREEN_STARTED = "screen_started"
private const val KEY_API_LEVEL = "api_level"
private const val KEY_ERROR_CLASS = "error_class"
private const val KEY_ERROR_MESSAGE = "error_message"

/**
 * SharedPreferences-backed holder for the most recent
 * [CarRenderRecord]. Its own file, not the settings file: this
 * is a diagnostic, not a setting, and it changes whenever the
 * big screen's answer changes.
 */
class CarRenderDiagnosticsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())
    val last: StateFlow<CarRenderRecord?> = _last.asStateFlow()

    private fun read(): CarRenderRecord? = carRenderRecordFromPrefsMap(prefs.all)

    fun record(record: CarRenderRecord) {
        val editor = prefs.edit().clear()
        for ((key, value) in record.toPrefsMap()) {
            when (value) {
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                null -> Unit
            }
        }
        editor.apply()
        _last.value = record
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-car-render-diagnostics"
    }
}
