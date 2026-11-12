package com.dannycom888.mylrcauto.media

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import android.media.browse.MediaBrowser
import com.dannycom888.mylrcauto.MyLrcAutoApp
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
 * car host: the app starts it the moment a track is playing and stops
 * it when playback stops or there is no track (see [MirrorActivation]
 * and the AppContainer wiring), because the user's flow is Poweramp
 * selected, then tap the app — and tapping the app opens the
 * CarAppService template, never this service, so waiting for the host
 * to bind it would leave the card on Poweramp's session forever.
 * No picker selection is needed, and none is asked for.
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
        val container = (applicationContext as MyLrcAutoApp).container

        mediaSession = MediaSession(this, SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(MirrorCallback(container.media))
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

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
        super.onDestroy()
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
        val glance = CarGlance.of(
            state = lyricsState,
            hasTrack = snapshot.hasTrack,
            permissionGranted = permissionGranted,
            positionMs = snapshot.playback.currentMs(),
        )
        val metadata = mirrorMetadata(snapshot, glance)
        val signature = metadata?.signature()
        if (signature != lastSignature) {
            lastSignature = signature
            mediaSession.setMetadata(metadata?.toFramework())
        }
        mediaSession.setPlaybackState(mirrorPlayback(snapshot).toFramework())
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
    }
}

/** Thin framework mapping for the pure mirror metadata. */
private fun MirrorMetadata.toFramework(): MediaMetadata =
    MediaMetadata.Builder().apply {
        putString(MediaMetadata.METADATA_KEY_TITLE, title)
        putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        artwork?.let {
            putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        subtitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) }
        description?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
    }.build()

/** Thin framework mapping for the pure mirror playback state. */
private fun MirrorPlayback.toFramework(): PlaybackState =
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
        .setActions(actions.toFramework())
        .build()

/** Thin framework mapping for the pure transport actions. */
private fun MirrorTransportActions.toFramework(): Long {
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
