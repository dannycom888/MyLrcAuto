package com.dannycom888.mylrcauto.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.lyrics.LyricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile

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
 * This is the AALyrics shape: [MediaBrowserServiceCompat] with a
 * [MediaSessionCompat], collectors plus a 250ms tick re-publishing
 * the playback state unconditionally and the metadata only when its
 * signature changes. One deliberate deviation from AALyrics: the
 * publish/activation order (see the activation note below). The
 * pure publish logic lives in [LyricsMirror] and is unchanged —
 * only this sink and service were swapped to the Compat classes,
 * because the framework `MediaSession` is what the car reads the
 * buttons from and the Compat session is the one AALyrics' working
 * card uses.
 *
 * Activation: the session goes active only once a track is loaded —
 * never with an empty snapshot. The car locks the card buttons from
 * its first read of the session and never re-derives them, and the
 * first publish can happen while the snapshot is still empty (no
 * track → empty action set → the car locks 10s forever). v1.0.18
 * published the complete state before activation, guarded — and the
 * car still showed 10s, because the snapshot was empty at that
 * first publish. So this version gates activation itself on a
 * track: with one, the complete state is published first (guarded —
 * a publish must never be fatal) and the session goes active
 * immediately after, so the car's first read sees the skip actions;
 * without one, the session stays inactive and the render loop
 * activates it the moment a track arrives, in the same
 * publish-then-activate order (see [render]).
 *
 * Why not AALyrics' activate-first order, which v1.0.19 copied for
 * its onCreate path? AALyrics' host BINDS its browser service and
 * holds a controller subscription from before activation, so every
 * publish after activation is delivered to it. This service is
 * never bound by the car host (the app is a POI template app, not
 * the selected media source): the host discovers the session
 * through the system's active-session list, and its first read can
 * land the moment `isActive` flips — before any publish. On the
 * path the user actually hits (the service starts while Poweramp
 * is already playing), v1.0.19's onCreate activated first and the
 * first read found a session with no playback state at all, which
 * this car renders as the default 10-second rewind/fast-forward
 * card — and never revisits. Publish-then-activate, everywhere,
 * is the order that survives that read.
 * The service is started by the app itself, not by the car host: the
 * app starts it the moment a track is loaded and stops it when there
 * is no track (see [MirrorActivation] and the AppContainer wiring),
 * because the user's flow is Poweramp selected, then tap the app —
 * and tapping the app opens the CarAppService template, never this
 * service, so waiting for the host to bind it would leave the card
 * on Poweramp's session forever. No picker selection is needed, and
 * none is asked for.
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
 * the permission flag — the same inputs the car screen reads — plus a
 * 250ms tick, so the playback state is re-published unconditionally
 * on every render. Metadata is re-sent only when its signature
 * changes (track, cover, or lyric line), so the subtitle is never
 * spammed; the playback state is set on every render. A render that
 * throws is caught and logged, never allowed to take the service
 * (and with it the card) down.
 */
class LyricsMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat

    /** Signature of the metadata currently published, or null when none is. */
    private var lastSignature: MirrorMetadataSignature? = null

    /** True once the session has gone active — only after a track was published. */
    private var sessionActive = false

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

        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(MirrorCallback(container.media))
        }
        sessionToken = mediaSession.sessionToken
        // Publish first, activate after — the render loop's order,
        // and the only safe one for a session the host discovers
        // through the active-session list (see the class doc):
        // render() publishes the complete state and then activates
        // the session itself when a track is present. Activation is
        // still gated on a track (an empty snapshot must never go
        // active — its empty action set is what locked the card to
        // 10s in the first place), and the publish is guarded, as in
        // v1.0.18: a failure is logged and left to the render loop,
        // never allowed to take the service down. v1.0.19 activated
        // first on this path; that was the AALyrics order without
        // AALyrics' binding, and the car's first read found no
        // playback state at all.
        val firstSnapshot = container.media.snapshot.value
        if (shouldActivateMirror(sessionActive, firstSnapshot)) {
            runCatching { renderCurrent(container) }
                .onFailure { Log.w(TAG, "initial mirror publish failed, the render loop will retry", it) }
        } else {
            Log.i(
                TAG,
                "mirror not activating yet: no track in snapshot; " +
                    "the render loop will activate it when a track arrives",
            )
        }

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
        // AALyrics' 250ms tick: the collectors above fire on state
        // changes, but nothing emits when only the playhead advances,
        // and the car reads the session whenever it likes — so the
        // playback state is re-published on a tick too, unconditionally.
        // The tick is also what activates a session that started with
        // no track, the moment one arrives.
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { renderCurrent(container) }
                    .onFailure { Log.w(TAG, "mirror tick render failed", it) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        isSessionActive = false
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
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.sendResult(mutableListOf())
    }

    private fun renderCurrent(container: MyLrcAutoApp.AppContainer) {
        render(
            container.media.snapshot.value,
            container.lyrics.state.value,
            container.media.permissionGranted.value,
        )
    }

    private fun render(
        snapshot: PlayerSnapshot,
        lyricsState: LyricsState,
        permissionGranted: Boolean,
    ) {
        // The published action mask, for the car-test log below: the
        // card's buttons come from these actions, so a log line that
        // says whether there was a track and which actions went out is
        // the only way to tell from the phone whether the car could
        // have seen the skip buttons.
        val publishedActions = mirrorPlayback(snapshot).actions.toFramework()
        lastSignature = publishMirrorState(
            MediaSessionCompatSink(mediaSession),
            snapshot,
            CarGlance.of(
                state = lyricsState,
                hasTrack = snapshot.hasTrack,
                permissionGranted = permissionGranted,
                positionMs = snapshot.playback.currentMs(),
            ),
            lastSignature,
        )
        Log.i(
            TAG,
            "mirror publish: hasTrack=${snapshot.hasTrack} " +
                "actions=$publishedActions active=$sessionActive",
        )
        // First track after an empty start: the publish above already
        // put the complete state out, so going active now means the
        // car's first read sees the skip actions, not an empty set.
        if (shouldActivateMirror(sessionActive, snapshot)) {
            mediaSession.isActive = true
            sessionActive = true
            isSessionActive = true
            Log.i(TAG, "mirror session activated with a track")
        }
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
    ) : MediaSessionCompat.Callback() {
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

        /**
         * Whether the mirror media session is currently active.
         *
         * Item-7 diagnostic (v1.0.19): read by [CarAppService]
         * before a new car session starts the mirror, so the session
         * can log whether the card was already showing — the state
         * fact the follow-up needs to tell an icon tap from a card
         * tap. Mirrors the instance [sessionActive]; written on the
         * main thread (service lifecycle and render run there) and
         * read on the main thread, volatile so a binder-thread read
         * can never see a stale value.
         */
        @Volatile
        var isSessionActive: Boolean = false
            private set

        /** AALyrics' projection tick: re-publish the playback state this often. */
        private const val TICK_MS = 250L
    }
}

/** Thin Compat mapping for the pure mirror metadata. */
private fun MirrorMetadata.toCompat(): MediaMetadataCompat =
    MediaMetadataCompat.Builder().apply {
        putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        // A recycled bitmap cannot be parceled across Binder — sending
        // one throws out of setMetadata. The snapshot can hold a bitmap
        // Poweramp itself recycled when it published new metadata (the
        // repository keeps the framework-owned bitmap as-is when it fits
        // the size cap), so a cover that is already recycled is left
        // out here rather than published: the card shows the title
        // without a cover until the next render brings a live one.
        artwork?.takeUnless { it.isRecycled }?.let {
            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        subtitle?.let { putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it) }
        description?.let { putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
    }.build()

/** The real [MirrorStateSink]: the Compat session behind it. */
private class MediaSessionCompatSink(
    private val session: MediaSessionCompat,
) : MirrorStateSink {
    override fun setPlaybackState(
        state: MirrorPlaybackState,
        positionMs: Long,
        speed: Float,
        actions: Long,
    ) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    when (state) {
                        MirrorPlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
                        MirrorPlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                        MirrorPlaybackState.NONE -> PlaybackStateCompat.STATE_NONE
                    },
                    positionMs,
                    speed,
                )
                .setActions(actions)
                .build(),
        )
    }

    override fun setMetadata(metadata: MirrorMetadata?) {
        session.setMetadata(metadata?.toCompat())
    }
}

/**
 * Whether the mirror session may go active for this snapshot: only once
 * a track is loaded, so the car's first read of the session sees the
 * complete skip actions rather than an empty action set. An empty
 * snapshot must never activate the session — activating it would hand
 * the car an empty action set, and this car locks the card buttons
 * from its first read and never re-derives them, so the card would
 * show 10s rewind/fast-forward forever. Pure, so the guard is
 * unit-testable: an empty snapshot does not activate.
 */
internal fun shouldActivateMirror(sessionActive: Boolean, snapshot: PlayerSnapshot): Boolean =
    !sessionActive && snapshot.hasTrack

/**
 * The transport actions as a `PlaybackState` flags bitmask.
 *
 * Internal (not private) so the published actions are unit-testable:
 * the card must offer skip-to-previous / skip-to-next and never
 * rewind / fast-forward. The flags are compile-time constants, so
 * calling this from a plain JVM unit test needs no Android runtime.
 * The values are the same in framework `PlaybackState` and
 * `PlaybackStateCompat` by design, so this one bitmask serves both —
 * the Compat sink above passes it straight to
 * `PlaybackStateCompat.Builder.setActions`.
 */
internal fun MirrorTransportActions.toFramework(): Long {
    var actions = 0L
    if (playPause) {
        actions = actions or PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE
    }
    if (skipPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    if (skipNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    if (seek) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
    return actions
}
