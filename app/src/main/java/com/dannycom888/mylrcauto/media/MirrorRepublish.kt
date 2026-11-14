package com.dannycom888.mylrcauto.media

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The mirror's full re-publish after a host-side teardown —
 * and the Settings record that proves it fired (v1.0.32).
 *
 * The defect (2026-11-13 20:55 car run, v1.0.30): the Android
 * Auto host flashed its own error screen once, then recovered —
 * the big screen came back on its own, but the bottom taskbar
 * media widget never showed the song again for the rest of the
 * drive. The sticky last-error record （上次异常） was EMPTY:
 * nothing threw in this process. And the shape of the recovery
 * rules the other deaths out — had the projection dropped, the
 * container's hook would have stopped the mirror service and
 * the reconnect would have started it again (a fresh `onCreate`
 * publishes everything); had the process died, the same restart
 * path would have run. Neither happened: the teardown was
 * inside the host, while our process, our service, our media
 * session and the projection all stayed up.
 *
 * In that state nothing ever put the widget's content back:
 *
 * - [LyricsMediaService] had no `onStartCommand` — a start
 *   delivered to the already-running service (which is what
 *   every existing start call site sends at car-session start)
 *   was a no-op.
 * - `publishMirrorState` re-sends metadata only when its
 *   signature changes; with the same track and the same lyric
 *   line, `setMetadata` never fired again — and the title and
 *   the lyric-line subtitle are exactly what the taskbar widget
 *   shows. (The playback state IS re-sent on every render and
 *   every 250ms tick; it was never the missing piece.)
 * - Activation is an edge, not a state: once the session had
 *   gone active, `render()` never set `isActive` again, even
 *   if the host had forgotten the session entirely.
 *
 * The recovery is a trigger, not a loop: a start intent with
 * [MIRROR_REPUBLISH_ACTION], sent at car-session start (and on
 * a card tap landing on a surviving session — the other shape
 * of the same moment). The service answers with
 * `forceRepublish`: forget the published signature so the one
 * normal publish path re-sends the metadata (subtitle = the
 * current lyric line, as always), re-publish the playback
 * state with it, and set `isActive` again when a track is
 * loaded. A fresh service start records the same event with
 * [MirrorRepublishReason.SERVICE_START] — its `onCreate`
 * publish was already complete; the record just makes that
 * shape of recovery visible too.
 *
 * This record is that visibility: the most recent full
 * re-publish, its time and its trigger, kept sticky like the
 * launch log (and gated by the same Settings Diagnostics
 * switch). After the next bad run, one screenshot settles
 * whether the recovery fired — and if it fired and the widget
 * still stayed empty, the loss is on the host's side of the
 * session, which is the other half of the evidence.
 *
 * The record, its formatting and its persistence encoding are
 * pure and unit-tested; [MirrorRepublishStore] is only the
 * SharedPreferences shell.
 */

/** The start-intent action that asks a running mirror service to re-publish in full. */
const val MIRROR_REPUBLISH_ACTION = "com.dannycom888.mylrcauto.action.REPUBLISH_MIRROR"

/** Whether a start intent's action is the re-publish request. */
fun isMirrorRepublishAction(action: String?): Boolean = action == MIRROR_REPUBLISH_ACTION

/** What triggered a full re-publish of the mirror. */
enum class MirrorRepublishReason {
    /** A car session started (or a card tap landed on a live one): the host is (re)reading. */
    CAR_SESSION_START,

    /** The mirror service itself (re)started: its first publish is a full one by construction. */
    SERVICE_START,
}

/** One full re-publish of the mirror. */
data class MirrorRepublishRecord(
    /** Wall-clock time of the re-publish, epoch milliseconds. */
    val timestampMs: Long,
    val reason: MirrorRepublishReason,
)

/**
 * The Settings row's value for [record]: time and trigger.
 * [timeText] and [reasonText] are preformatted/localised by the
 * caller (the same pattern as the last-error row), so this
 * stays pure.
 */
fun formatMirrorRepublish(
    record: MirrorRepublishRecord,
    timeText: String,
    reasonText: String,
): String = "$timeText · $reasonText"

/** The record as preference values, so the round-trip is unit-testable without Android. */
fun MirrorRepublishRecord.toPrefsMap(): Map<String, Any?> = mapOf(
    KEY_TIMESTAMP_MS to timestampMs,
    KEY_REASON to reason.name,
)

/** The inverse of [toPrefsMap]; null when the map holds no (or a broken) record. */
fun mirrorRepublishRecordFromPrefsMap(values: Map<String, Any?>): MirrorRepublishRecord? {
    val timestampMs = values[KEY_TIMESTAMP_MS] as? Long ?: return null
    val reason = runCatching {
        MirrorRepublishReason.valueOf(values[KEY_REASON] as? String ?: return null)
    }.getOrNull() ?: return null
    return MirrorRepublishRecord(timestampMs = timestampMs, reason = reason)
}

private const val KEY_TIMESTAMP_MS = "timestamp_ms"
private const val KEY_REASON = "reason"

/**
 * SharedPreferences-backed holder for the most recent
 * [MirrorRepublishRecord]. Its own file, like the launch
 * log's: this is a diagnostic, not a setting.
 */
class MirrorRepublishStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())
    val last: StateFlow<MirrorRepublishRecord?> = _last.asStateFlow()

    private fun read(): MirrorRepublishRecord? = mirrorRepublishRecordFromPrefsMap(prefs.all)

    fun record(record: MirrorRepublishRecord) {
        val editor = prefs.edit().clear()
        for ((key, value) in record.toPrefsMap()) {
            when (value) {
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                null -> Unit
            }
        }
        editor.apply()
        _last.value = record
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-mirror-republish"
    }
}
