package com.dannycom888.mylrcauto.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import android.media.browse.MediaBrowser
import androidx.core.app.NotificationCompat
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.lyrics.LyricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The mirror media session, as the car sees it.
 *
 * The dashboard media card (split-screen, map on the left) and the
 * full-screen media view are drawn by the host from the *active media
 * session* — never from a car-app template. This service publishes a
 * session that mirrors whatever Poweramp is playing and carries the
 * current lyric line in its metadata, so the host draws the card the
 * way it does for AALyrics: cover, title, one lyric line, progress,
 * transport. The app's own full-screen full-lyrics list (the car-app
 * screen, opened from the app icon) is untouched and stays the place
 * for the whole song.
 *
 * Mirror only: this session never plays audio. Its transport callback
 * forwards to the player the repository follows, and the repository
 * already ignores this app's own sessions when choosing what to
 * follow, so the mirror can never follow itself.
 *
 * Activation: the session is made active in [onCreate] and stays so
 * while this service lives; what the host shows is driven by the
 * published state — playing/paused with a track, [PlaybackState.STATE_NONE]
 * with no track. The service is started by the app itself, not by the
 * car host: the app starts it the moment a track is loaded and stops
 * it when there is no track (see [MirrorActivation]
 * and the AppContainer wiring), because the user's flow is Poweramp
 * selected, then tap the app — and tapping the app opens the
 * CarAppService template, never this service, so waiting for the host
 * to bind it would leave the card on Poweramp's session forever.
 * No picker selection is needed, and none is asked for.
 *
 * Foreground: the service promotes itself in [onCreate], with a
 * minimal silent notification, whichever way it was started. A plain
 * background start is refused outright when the process is
 * backgrounded (v1.0.13: the snapshot hook's start was blocked while
 * the phone sat in the pocket), and a foreground start
 * (`startForegroundService`) requires a `startForeground` call within
 * seconds — so the promotion happens here, at once, and the
 * notification only exists while the service runs, which is while a
 * track is playing. It is low-importance, so it never sounds or pops
 * up. The service is declared `mediaPlayback` in the manifest for
 * exactly this.
 *
 * Updates: the render is driven by the snapshot, the lyrics state and
 * the permission flag — the same inputs the car screen reads. Metadata
 * is re-sent only when its signature changes (track, cover, or lyric
 * line), so the subtitle is never spammed; the playback state is set
 * on every render, which is at most the repository's 500ms tick while
 * playing. A render that throws is caught and logged, never allowed to
 * take the service (and with it the card) down.
 */
class LyricsMediaService : MediaBrowserService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession

    /** Signature of the metadata currently published, or null when none is. */
    private var lastSignature: MirrorMetadataSignature? = null

    // The media-button / transport-control flags are deprecated in API 34
    // (there they are no-ops — a session with a callback handles them), but
    // they are still what tells the framework below API 34 to route media
    // buttons and transport controls to this session, so they stay set.
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        // Promote first, before anything else: a foreground start gives
        // the service seconds to call startForeground, and a background
        // start is refused outright — so the promotion happens here, at
        // once, whichever way the service was started.
        promoteToForeground()
        val container = (applicationContext as MyLrcAutoApp).container

        mediaSession = MediaSession(this, SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(MirrorCallback(container.media))
        }
        sessionToken = mediaSession.sessionToken
        // Publish FIRST, activate after — v1.0.18. The car reads the
        // session's actions the moment it goes active, and locks the
        // card's buttons in from that first read: v1.0.14 and v1.0.17
        // both went active first (v1.0.17 with a synchronous publish
        // right after), and the car still showed 10s rewind /
        // fast-forward on both, because its first read found an
        // empty action set. v1.0.15 published before activation and
        // that fixed the buttons in principle — but the publish was
        // unguarded, so a throwing first publish (a recycled cover
        // bitmap cannot be parceled across Binder) killed onCreate,
        // the service never started, and there was no card at all.
        // This version does both: the full state — with the skip
        // actions — is published synchronously from the current
        // snapshot BEFORE the session goes active, and the publish is
        // guarded, so a failure is logged and left for the render
        // loop below instead of taking the service (and with it the
        // card) down. Setting state on an inactive session is valid;
        // the state is there when the car reads it at activation.
        runCatching {
            val sink = MediaSessionSink(mediaSession)
            lastSignature = publishMirrorState(
                sink,
                container.media.snapshot.value,
                CarGlance.of(
                    state = container.lyrics.state.value,
                    hasTrack = container.media.snapshot.value.hasTrack,
                    permissionGranted = container.media.permissionGranted.value,
                    positionMs = container.media.snapshot.value.playback.currentMs(),
                ),
                lastSignature,
            )
        }.onFailure { Log.w(TAG, "initial mirror publish failed, the render loop will retry", it) }
        mediaSession.isActive = true

        scope.launch {
            combine(
                container.media.snapshot,
                container.lyrics.state,
                container.media.permissionGranted,
            ) { snapshot, lyricsState, permissionGranted ->
                Triple(snapshot, lyricsState, permissionGranted)
            }.collect { (snapshot, lyricsState, permissionGranted) ->
                runCatching { render(snapshot, lyricsState, permissionGranted) }
                    .onFailure { Log.w(TAG, "mirror render failed", it) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { mediaSession.isActive = false }
        runCatching { mediaSession.release() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * The minimal silent notification that makes this a foreground
     * service. Low-importance channel, no sound, no badge, no pop-up —
     * it only says the mirror is running, and it only exists while a
     * track is playing (the service is stopped when playback stops).
     */
    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.mirror_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mirror_notification_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mirror_notification_text))
            .setSilent(true)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        // Now-playing mirror only: there is no browsable library, so every
        // caller gets the same empty root. Nothing to protect.
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        result.sendResult(mutableListOf())
    }

    private fun render(
        snapshot: PlayerSnapshot,
        lyricsState: LyricsState,
        permissionGranted: Boolean,
    ) {
        lastSignature = publishMirrorState(
            MediaSessionSink(mediaSession),
            snapshot,
            CarGlance.of(
                state = lyricsState,
                hasTrack = snapshot.hasTrack,
                permissionGranted = permissionGranted,
                positionMs = snapshot.playback.currentMs(),
            ),
            lastSignature,
        )
    }

    /**
     * Forwards the host's transport buttons to the player being mirrored.
     *
     * Play/pause go through [MediaSessionRepository.togglePlayPause],
     * which reads the player's live state — the host only sends play when
     * it believes we are paused and pause when it believes we are playing,
     * and our published state mirrors the player, so the toggle lands on
     * the right side. A seek is forwarded only when the player says it
     * accepts one; the action mask already omits it otherwise.
     */
    private inner class MirrorCallback(
        private val media: MediaSessionRepository,
    ) : MediaSession.Callback() {
        override fun onPlay() {
            runCatching { media.togglePlayPause() }
        }

        override fun onPause() {
            runCatching { media.togglePlayPause() }
        }

        override fun onSkipToNext() {
            runCatching { media.skipNext() }
        }

        override fun onSkipToPrevious() {
            runCatching { media.skipPrevious() }
        }

        override fun onSeekTo(pos: Long) {
            if (pos < 0L) return
            if (!media.snapshot.value.transport.seek) return
            runCatching { media.seekTo(pos) }
        }
    }

    companion object {
        private const val TAG = "LyricsMediaService"
        private const val ROOT_ID = "root"
        private const val SESSION_TAG = "MyLrcAuto"
        private const val NOTIFICATION_CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 1
    }
}

/** Thin framework mapping for the pure mirror metadata. */
private fun MirrorMetadata.toFramework(): MediaMetadata =
    MediaMetadata.Builder().apply {
        putString(MediaMetadata.METADATA_KEY_TITLE, title)
        putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        // A recycled bitmap cannot be parceled across Binder — sending
        // one throws out of setMetadata. The snapshot can hold a bitmap
        // Poweramp itself recycled when it published new metadata (the
        // repository keeps the framework-owned bitmap as-is when it fits
        // the size cap), so a cover that is already recycled is left
        // out here rather than published: the card shows the title
        // without a cover until the next render brings a live one.
        artwork?.takeUnless { it.isRecycled }?.let {
            putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        subtitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) }
        description?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
    }.build()

/** The real [MirrorStateSink]: the framework session behind it. */
private class MediaSessionSink(
    private val session: MediaSession,
) : MirrorStateSink {
    override fun setPlaybackState(
        state: MirrorPlaybackState,
        positionMs: Long,
        speed: Float,
        actions: Long,
    ) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    when (state) {
                        MirrorPlaybackState.PLAYING -> PlaybackState.STATE_PLAYING
                        MirrorPlaybackState.PAUSED -> PlaybackState.STATE_PAUSED
                        MirrorPlaybackState.NONE -> PlaybackState.STATE_NONE
                    },
                    positionMs,
                    speed,
                )
                .setActions(actions)
                .build(),
        )
    }

    override fun setMetadata(metadata: MirrorMetadata?) {
        session.setMetadata(metadata?.toFramework())
    }
}

/**
 * The transport actions as a `PlaybackState` flags bitmask.
 *
 * Internal (not private) so the published actions are unit-testable:
 * the card must offer skip-to-previous / skip-to-next and never
 * rewind / fast-forward. The flags are compile-time constants, so
 * calling this from a plain JVM unit test needs no Android runtime.
 */
internal fun MirrorTransportActions.toFramework(): Long {
    var actions = 0L
    if (playPause) {
        actions = actions or PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
    }
    if (skipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    if (skipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
    if (seek) actions = actions or PlaybackState.ACTION_SEEK_TO
    return actions
}
