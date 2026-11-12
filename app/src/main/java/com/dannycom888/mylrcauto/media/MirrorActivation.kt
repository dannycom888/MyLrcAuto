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
}

/** What the activation tracker wants done: start the service, or stop it. */
enum class MirrorAction {
    START,
    STOP,
}

/**
 * Remembers whether the mirror service is currently wanted, and turns
 * a stream of wants into at most one action per change — so the
 * repository's 500ms position ticker, which re-emits the snapshot
 * while playing, never re-starts an already-running service, and a
 * stop is only sent once.
 */
class MirrorActivationTracker {
    var active: Boolean = false
        private set

    fun update(wantActive: Boolean): MirrorAction? {
        if (wantActive == active) return null
        active = wantActive
        return if (wantActive) MirrorAction.START else MirrorAction.STOP
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
