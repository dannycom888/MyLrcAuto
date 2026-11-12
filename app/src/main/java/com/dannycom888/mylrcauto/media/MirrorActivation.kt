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
 * with no picker selection. So this app starts its own service when
 * playback is detected, and stops it when playback stops or there is
 * no track.
 *
 * The decision ([shouldBeActive]) and the transition tracker
 * ([MirrorActivationTracker]) are pure and unit-tested; the only
 * Android part is [MirrorServiceControl], which performs the actual
 * start/stop.
 */
object MirrorActivation {
    /**
     * The mirror is wanted exactly while a track is actually playing:
     * a loaded-but-paused track and no track at all both mean stop.
     * Paused means no lyric line is advancing, so the card can fall
     * back to Poweramp's own session; pressing play anywhere starts
     * the mirror again on the next snapshot.
     */
    fun shouldBeActive(snapshot: PlayerSnapshot): Boolean =
        snapshot.hasTrack && snapshot.playback.isPlaying
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
 * A plain `startService` is the right call here, not a foreground
 * service with a notification: this app's process is kept in the
 * foreground state by the notification-listener service the system
 * binds to deliver media notifications, so a background-service start
 * is allowed while playback is being watched. If that ever stops
 * being true, the failure shows up in logcat as
 * `IllegalStateException` from this call — the next step then would
 * be `startForegroundService` with a minimal notification, not a
 * silent retry. Either way a failure here must never crash the app:
 * the worst case is the card stays Poweramp's, exactly as in
 * v1.0.12.
 */
object MirrorServiceControl {
    fun start(context: Context) {
        runCatching {
            context.startService(Intent(context, LyricsMediaService::class.java))
        }.onFailure { Log.w(TAG, "could not start mirror media service", it) }
    }

    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, LyricsMediaService::class.java))
        }.onFailure { Log.w(TAG, "could not stop mirror media service", it) }
    }

    private const val TAG = "MirrorServiceControl"
}
