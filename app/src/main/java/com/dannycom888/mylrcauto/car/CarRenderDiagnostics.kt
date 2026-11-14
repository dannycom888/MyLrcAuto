package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.SharedPreferences
import androidx.car.app.versioning.CarAppApiLevels
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
 * Why the surface is not the thing drawing the big screen (v1.0.29).
 *
 * Up to v1.0.28 the record could only say the surface was not live
 * and that no template build had failed — which left the
 * 2026-11-13 19:07 run (fallback pane up, lyrics live-updating,
 * surface never connected, fallback cause EMPTY) undecidable:
 * "not live" hides at least four different states, and the empty
 * cause read as "nothing went wrong" when in fact nothing had
 * been given the chance to go wrong. The values below are the
 * states, in the order the surface path itself walks them:
 * register → be offered → use the offer → be chosen.
 */
enum class CarSurfaceBlock {
    /** The surface template is the pane that went back. */
    ACTIVE,

    /** The host is below car API 7: no template of ours can carry a surface. */
    API_TOO_LOW,

    /** The session never registered a surface callback with the host. */
    NOT_REGISTERED,

    /** Registered, but the host never called `onSurfaceAvailable`. */
    NEVER_OFFERED,

    /** The host offered, and using the offer failed on the app's side. */
    OFFER_FAILED,

    /** The host offered and later took the surface back before this render. */
    OFFER_ENDED,

    /** A surface is live, but the screen is covered (dashboard / split-screen). */
    NOT_FOREGROUND,

    /** A surface is live, but the host reports its visible area as empty. */
    AREA_EMPTY,

    /** A surface is live, but it is a split-screen slot, not a full screen. */
    TOO_NARROW,

    /** The facts do not explain the fallback (should not happen). */
    UNKNOWN,
}

/**
 * Derives [CarSurfaceBlock] from one render's facts, in the
 * surface path's own order. Pure, so every branch is unit-tested:
 * the next car screenshot names the exact step the path stopped
 * at instead of another "surface not connected".
 */
fun carSurfaceBlock(
    pane: CarPaneKind,
    surfaceLive: Boolean,
    surfaceWidthDp: Float?,
    visibleAreaEmpty: Boolean,
    screenStarted: Boolean,
    apiLevel: Int,
    callbackRegistered: Boolean,
    callbackFired: Boolean,
    surfaceFailure: String?,
): CarSurfaceBlock = when {
    pane == CarPaneKind.ON_SURFACE -> CarSurfaceBlock.ACTIVE
    apiLevel < CarAppApiLevels.LEVEL_7 -> CarSurfaceBlock.API_TOO_LOW
    !surfaceLive && !callbackRegistered -> CarSurfaceBlock.NOT_REGISTERED
    !surfaceLive && !callbackFired -> CarSurfaceBlock.NEVER_OFFERED
    !surfaceLive && surfaceFailure != null -> CarSurfaceBlock.OFFER_FAILED
    !surfaceLive -> CarSurfaceBlock.OFFER_ENDED
    !screenStarted -> CarSurfaceBlock.NOT_FOREGROUND
    visibleAreaEmpty -> CarSurfaceBlock.AREA_EMPTY
    surfaceWidthDp != null && surfaceWidthDp < SPLIT_SCREEN_WIDTH_DP ->
        CarSurfaceBlock.TOO_NARROW
    else -> CarSurfaceBlock.UNKNOWN
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
    /**
     * The surface-offer facts (v1.0.29): whether the session's
     * callback reached the host, whether the host ever answered
     * it, and the size of the container it offered.
     */
    val surfaceCallbackRegistered: Boolean = false,
    val surfaceCallbackFired: Boolean = false,
    val surfaceContainerWidthPx: Int? = null,
    val surfaceContainerHeightPx: Int? = null,
    /** Why using an offered surface failed, truncated; null when none did. */
    val surfaceFailure: String? = null,
    /** Derived: why the surface is not drawing (see [carSurfaceBlock]). */
    val surfaceBlock: CarSurfaceBlock = CarSurfaceBlock.UNKNOWN,
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
    surfaceCallbackRegistered: Boolean = false,
    surfaceCallbackFired: Boolean = false,
    surfaceContainerWidthPx: Int? = null,
    surfaceContainerHeightPx: Int? = null,
    surfaceFailure: String? = null,
): CarRenderRecord {
    val pane = carPaneKind(onSurface, glance, failed = error != null)
    return CarRenderRecord(
        timestampMs = timestampMs,
        pane = pane,
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
        surfaceCallbackRegistered = surfaceCallbackRegistered,
        surfaceCallbackFired = surfaceCallbackFired,
        surfaceContainerWidthPx = surfaceContainerWidthPx,
        surfaceContainerHeightPx = surfaceContainerHeightPx,
        surfaceFailure = surfaceFailure?.take(MAX_ERROR_MESSAGE_CHARS),
        surfaceBlock = carSurfaceBlock(
            pane = pane,
            surfaceLive = surfaceLive,
            surfaceWidthDp = surfaceWidthDp,
            visibleAreaEmpty = visibleAreaEmpty,
            screenStarted = screenStarted,
            apiLevel = apiLevel,
            callbackRegistered = surfaceCallbackRegistered,
            callbackFired = surfaceCallbackFired,
            surfaceFailure = surfaceFailure,
        ),
    )
}

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
    put(KEY_CALLBACK_REGISTERED, surfaceCallbackRegistered)
    put(KEY_CALLBACK_FIRED, surfaceCallbackFired)
    surfaceContainerWidthPx?.let { put(KEY_CONTAINER_WIDTH_PX, it) }
    surfaceContainerHeightPx?.let { put(KEY_CONTAINER_HEIGHT_PX, it) }
    surfaceFailure?.let { put(KEY_SURFACE_FAILURE, it) }
    put(KEY_SURFACE_BLOCK, surfaceBlock.name)
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
        surfaceCallbackRegistered = values[KEY_CALLBACK_REGISTERED] as? Boolean ?: false,
        surfaceCallbackFired = values[KEY_CALLBACK_FIRED] as? Boolean ?: false,
        surfaceContainerWidthPx = values[KEY_CONTAINER_WIDTH_PX] as? Int,
        surfaceContainerHeightPx = values[KEY_CONTAINER_HEIGHT_PX] as? Int,
        surfaceFailure = values[KEY_SURFACE_FAILURE] as? String,
        surfaceBlock = runCatching {
            CarSurfaceBlock.valueOf(values[KEY_SURFACE_BLOCK] as? String ?: "")
        }.getOrDefault(CarSurfaceBlock.UNKNOWN),
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
private const val KEY_CALLBACK_REGISTERED = "callback_registered"
private const val KEY_CALLBACK_FIRED = "callback_fired"
private const val KEY_CONTAINER_WIDTH_PX = "container_width_px"
private const val KEY_CONTAINER_HEIGHT_PX = "container_height_px"
private const val KEY_SURFACE_FAILURE = "surface_failure"
private const val KEY_SURFACE_BLOCK = "surface_block"

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
