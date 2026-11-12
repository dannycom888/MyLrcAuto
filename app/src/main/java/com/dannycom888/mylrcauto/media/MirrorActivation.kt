package com.dannycom888.mylrcauto.media

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * When the mirror media session runs — and when it must not.
 *
 * v1.0.12 added the mirror session ([LyricsMediaService]) but left it
 * to the car host to start: a `MediaBrowserService` only runs when
 * something binds to it or starts it, and the host only binds the
 * service of the media source the user selected in the car picker.
 * The user's flow is the other way round — he selects Poweramp as the
 * media source, then taps My LRC Auto — and tapping the app icon
 * opens the `CarAppService` template, never the browser service, so
 * in his flow the service never started, the session never became
 * active, and the card stayed Poweramp's. AALyrics works with exactly
 * that flow: its session is active as soon as playback is detected,
 * with no picker selection. So this app starts its own service when a
 * track is loaded, and stops it when there is no track.
 *
 * v1.0.18 — and only while a car session is live. Starting the
 * service whenever a track was loaded meant it ran as a foreground
 * service holding an ACTIVE media session while the phone was
 * nowhere near a car (playing or paused, at home, in the pocket) —
 * and that blocked the phone's wireless Android Auto connection:
 * with the app installed wireless never connected, and after
 * uninstalling it wireless connected normally and reliably.
 * AALyrics' equivalent service never self-starts and never runs as
 * a foreground service; it exists only when a media client binds to
 * it, so it holds no active session while disconnected and never
 * interferes with the connection handshake. The mirror's only
 * consumer is the car's dashboard card, so it has no business
 * running without a car: the gate is [shouldBeActive] with a live
 * car session, and the session ending stops the service (and
 * deactivates the session) at once, so the next connection starts
 * clean.
 *
 * The decision ([shouldBeActive]) and the transition tracker
 * ([MirrorActivationTracker]) are pure and unit-tested; the only
 * Android part is [MirrorServiceControl], which performs the actual
 * start/stop.
 */
object MirrorActivation {
    /**
     * The mirror is wanted exactly while a track is loaded — playing or
     * paused. A paused track still has a current lyric line, and the
     * card should keep showing it: v1.0.13 stopped the service the
     * moment playback paused, so the session deactivated, the card fell
     * back to Poweramp's, and the lyric line vanished the moment he
     * paused — which reads as "the card doesn't work". Keeping the
     * session active while paused changes nothing about the music
     * itself (the mirror never plays audio; its transport only
     * forwards to Poweramp). No track at all means stop.
     */
    fun shouldBeActive(snapshot: PlayerSnapshot): Boolean =
        snapshot.hasTrack

    /**
     * The mirror is wanted only while a car session is live as well.
     *
     * v1.0.18: a track loaded is not enough on its own. The service
     * used to start whenever a track was loaded — including with no
     * car anywhere — and its active media session, held by a
     * foreground service, blocked the phone's wireless Android Auto
     * connection (uninstalling the app made wireless connect again).
     * The dashboard card is the mirror's only consumer, so without a
     * car session there is nothing to mirror to; the service stays
     * stopped and the session stays inactive until the car actually
     * connects, and stopping on disconnect keeps the next connection
     * clean.
     */
    fun shouldBeActive(snapshot: PlayerSnapshot, carConnected: Boolean): Boolean =
        carConnected && shouldBeActive(snapshot)
}

/** What the activation tracker wants done: start the service, or stop it. */
enum class MirrorAction {
    START,
    STOP,
}

/**
 * Remembers whether the mirror service is currently wanted, and turns
 * a stream of wants into actions — at most one start/stop per change,
 * plus re-starts that keep the card alive.
 *
 * The plain flip-only version of this tracker stranded the card: it
 * recorded "active" the moment a start was *asked for*, even when the
 * start itself failed (blocked while the phone sat in the pocket), and
 * the snapshot hook's `distinctUntilChanged` then never re-fired — so
 * a service that died, or never started, stayed dead for the whole
 * drive. This version re-issues START in the two cases that mean the
 * service may not be running even though it is still wanted: the
 * track changed (a new song is a fresh chance the old service missed),
 * and the last start attempt is older than [RETRY_INTERVAL_MS] (a
 * start refused once may succeed a few seconds later, and a service
 * killed mid-drive comes back). Starting an already-running service
 * is a no-op, so a redundant START is harmless; the throttle keeps
 * the repository's 500ms position ticker from re-starting a running
 * service every half second.
 */
class MirrorActivationTracker {
    var active: Boolean = false
        private set

    private var lastTrackKey: String? = null
    private var lastStartAttemptMs: Long = 0L

    fun update(wantActive: Boolean, trackKey: String?, nowMs: Long): MirrorAction? {
        if (!wantActive) {
            if (!active) return null
            active = false
            lastTrackKey = null
            return MirrorAction.STOP
        }
        if (!active) {
            active = true
            lastTrackKey = trackKey
            lastStartAttemptMs = nowMs
            return MirrorAction.START
        }
        if (trackKey != lastTrackKey) {
            lastTrackKey = trackKey
            lastStartAttemptMs = nowMs
            return MirrorAction.START
        }
        if (nowMs - lastStartAttemptMs >= RETRY_INTERVAL_MS) {
            lastStartAttemptMs = nowMs
            return MirrorAction.START
        }
        return null
    }

    companion object {
        /** A start refused once is retried no more often than this. */
        const val RETRY_INTERVAL_MS = 5_000L
    }
}

/**
 * The Android effect: start / stop the mirror service.
 *
 * A plain `startService` is tried first. It is not enough on its own:
 * v1.0.13 proved it — the snapshot hook's start was blocked while the
 * phone sat in the pocket, and the service never started even though
 * the tracker had already recorded "active", so the card stayed
 * Poweramp's. A background start throws `IllegalStateException` when
 * the process is not in a state that allows it, and the fallback is
 * `startForegroundService`: the service promotes itself to foreground
 * with a minimal silent notification in `onCreate`
 * ([LyricsMediaService]), so the foreground start cannot strand it.
 * Either way a failure here must never crash the app: the worst case
 * is the card stays Poweramp's, exactly as in v1.0.12.
 */
object MirrorServiceControl {
    fun start(context: Context) {
        when (
            startMirrorWithFallback(
                startService = {
                    context.startService(Intent(context, LyricsMediaService::class.java))
                },
                startForegroundService = {
                    context.startForegroundService(Intent(context, LyricsMediaService::class.java))
                },
            )
        ) {
            MirrorStartResult.STARTED -> Unit
            MirrorStartResult.STARTED_FOREGROUND ->
                Log.i(TAG, "mirror media service started in foreground")
            MirrorStartResult.FAILED ->
                Log.w(TAG, "could not start mirror media service")
        }
    }

    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, LyricsMediaService::class.java))
        }.onFailure { Log.w(TAG, "could not stop mirror media service", it) }
    }

    private const val TAG = "MirrorServiceControl"
}

/** How a mirror service start went, for the caller to log. */
internal enum class MirrorStartResult {
    /** The plain background start worked. */
    STARTED,

    /** The background start was refused; the foreground start worked. */
    STARTED_FOREGROUND,

    /** Neither start worked. */
    FAILED,
}

/**
 * Start the mirror service, with the foreground fallback.
 *
 * Pure apart from the two lambdas the caller supplies, so the fallback
 * is unit-testable without a Context: a plain start that throws
 * `IllegalStateException` (background start not allowed from this
 * process state) falls back to the foreground start; any other failure
 * does not — retrying a different start cannot fix a missing service
 * or a bad intent. No logging here: the caller logs the result, so a
 * unit test never touches `android.util.Log`.
 */
internal fun startMirrorWithFallback(
    startService: () -> Unit,
    startForegroundService: () -> Unit,
): MirrorStartResult {
    try {
        startService()
        return MirrorStartResult.STARTED
    } catch (e: IllegalStateException) {
        // Background start refused — fall through to the foreground start.
    } catch (_: Exception) {
        return MirrorStartResult.FAILED
    }
    return try {
        startForegroundService()
        MirrorStartResult.STARTED_FOREGROUND
    } catch (_: Exception) {
        MirrorStartResult.FAILED
    }
}
