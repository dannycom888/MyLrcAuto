package com.dannycom888.mylrcauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Watches every media session on the device and publishes whichever one is
 * actually playing music, together with a playhead we can extrapolate from.
 *
 * Simplified port of Melisma's repository: no queue, no prefetch, no
 * ignored-players. Poweramp is preferred; among the rest, a session that
 * is playing beats one that played most recently, which beats one that
 * merely has metadata. Artwork comes from the player's own metadata
 * only — a bitmap in the metadata, or a `content://` / `file://`
 * artwork URI opened through the resolver — and is never downloaded.
 *
 * **Everything inside runs on the main thread.** The watched controllers are
 * reached from framework callbacks delivered on the main looper (that is the
 * handler we register with), so the public methods hop to the main thread
 * themselves rather than trusting every caller to know.
 */
class MediaSessionRepository(
    private val context: Context,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent =
        ComponentName(context, MediaNotificationListener::class.java)

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    /** App-lifetime scope: the permission watch and the position ticker. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /** Controllers we have callbacks attached to, keyed by session token identity. */
    private val watched = LinkedHashMap<MediaController, ControllerWatcher>()

    /** Last time each controller reported that it was playing. Breaks ties. */
    private val lastPlayingAt = HashMap<String, Long>()

    private var started = false
    private var selected: MediaController? = null
    private var bindAttempts = 0

    /** Advances the published playhead while something is playing. */
    private var tickerJob: Job? = null

    /** Covers fetched from artwork URIs, bounded so a long session cannot grow it. */
    private val artworkCache = ArtworkCache<Bitmap>()

    /** Artwork URIs already tried once, so a missing cover is not re-fetched every publish. */
    private val artworkAttempted = HashSet<String>()

    /** I/O for URI artwork runs here, not on the main thread. */
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebind(controllers.orEmpty())
        }

    init {
        // Notification access is two facts, not one: the toggle is on in
        // Settings *and* the system has actually bound our listener service.
        // The second half arrives (and goes away) on its own schedule, so
        // watch it rather than only checking at start/refresh time.
        scope.launch {
            MediaNotificationListener.connected.collect { connected ->
                updatePermission()
                if (connected && isPermissionGranted() && !started) {
                    // The listener bound late, after the retry loop already
                    // gave up: start session watching now.
                    bindAttempts = 0
                    start()
                }
            }
        }
        updatePermission()
    }

    /**
     * Run on the main thread: now if we are already there, otherwise as soon as it is free.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    /** True when any media session is currently known, whether it has a track or not. */
    val hasSessions: Boolean get() = watched.isNotEmpty()

    fun isPermissionGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    private fun updatePermission() {
        _permissionGranted.value =
            isPermissionGranted() && MediaNotificationListener.connected.value
    }

    fun start() = onMain {
        updatePermission()
        if (!isPermissionGranted() || started || sessionManager == null) return@onMain

        started = true
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                listenerComponent,
                handler,
            )
            rebind(sessionManager.getActiveSessions(listenerComponent))
            bindAttempts = 0
        } catch (e: SecurityException) {
            // The toggle is on but the system has not bound our listener service yet.
            // This is the normal path immediately after the user grants access:
            // keep asking for a few seconds rather than leaving the screen stuck
            // on "nothing playing".
            Log.w(TAG, "Not allowed to read sessions yet: ${e.message}")
            started = false
            // Force the bind: after first grant the system does not always
            // bind the listener service on its own (Samsung quirk).
            runCatching { NotificationListenerService.requestRebind(listenerComponent) }
            scheduleBindRetry()
        }
    }

    private fun scheduleBindRetry() {
        if (bindAttempts >= MAX_BIND_ATTEMPTS) return
        val delayMs = BIND_RETRY_BASE_MS shl bindAttempts
        bindAttempts++
        handler.postDelayed({ if (!started) start() }, delayMs)
    }

    fun stop() = onMain {
        if (!started) return@onMain
        started = false
        stopTicker()
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        watched.values.forEach { it.detach() }
        watched.clear()
        selected = null
    }

    /** Call from `onResume`: picks up a permission that was granted while we were away. */
    fun refresh() = onMain {
        updatePermission()
        if (!isPermissionGranted()) {
            stop()
            _snapshot.value = PlayerSnapshot()
            return@onMain
        }
        if (!MediaNotificationListener.connected.value) {
            // The toggle is on but the system has not bound our listener
            // service yet (Samsung does not always bind it on its own after
            // first grant): force the bind, then let start() retry against it.
            runCatching { NotificationListenerService.requestRebind(listenerComponent) }
        }
        if (!started) {
            // Coming back from the settings screen: allow a fresh round of retries.
            bindAttempts = 0
            start()
        } else {
            runCatching { rebind(sessionManager?.getActiveSessions(listenerComponent).orEmpty()) }
        }
    }

    private fun rebind(controllers: List<MediaController>) {
        val incoming = controllers.filter { it.packageName != context.packageName }

        // Drop watchers for sessions that went away.
        val gone = watched.keys.filter { existing -> incoming.none { it.sameSession(existing) } }
        gone.forEach { watched.remove(it)?.detach() }

        // Attach to sessions we have not seen yet.
        for (controller in incoming) {
            if (watched.keys.none { it.sameSession(controller) }) {
                val watcher = ControllerWatcher(controller)
                watched[controller] = watcher
                watcher.attach()
            }
        }

        publish()
    }

    private fun MediaController.sameSession(other: MediaController): Boolean =
        sessionToken == other.sessionToken

    /** Re-runs the choice of session, for when something changed other than the sessions. */
    fun republish() = onMain { publish() }

    /**
     * Decide which session the lyrics should follow, then publish it.
     *
     * Preference order: Poweramp first; among the rest, something playing
     * right now, then whatever played most recently, then anything with
     * usable metadata at all. That keeps the screen on the paused track you
     * were listening to instead of jumping to a silent video session that
     * happens to exist.
     */
    private fun publish() {
        val winner = chooseSession(watched.keys.map { it.asCandidate() })?.owner
        if (winner == null) {
            selected = null
            stopTicker()
            _snapshot.value = PlayerSnapshot()
            return
        }

        selected = winner
        val previous = _snapshot.value
        val fresh = winner.toSnapshot()
        _snapshot.value = fresh.copy(
            // A new track starts at 0: the state read here can still carry
            // the old track's position (see resetIfTrackChanged).
            playback = fresh.playback.resetIfTrackChanged(
                oldTrack = previous.track,
                newTrack = fresh.track,
                nowElapsed = SystemClock.elapsedRealtime(),
            ),
        )
        updateTicker()
    }

    /** One session, reduced to what choosing between them needs. */
    private class Candidate(
        val packageName: String,
        /** A session with no title is not a track; players publish empty ones while they start up. */
        val hasMetadata: Boolean,
        val isPlaying: Boolean,
        /** When this package last reported playing, for the tie-break. */
        val lastPlayingAt: Long,
        val owner: MediaController,
    )

    private fun chooseSession(candidates: List<Candidate>): Candidate? {
        val eligible = candidates.filter { it.hasMetadata }
        if (eligible.isEmpty()) return null
        return eligible.sortedWith(
            compareByDescending<Candidate> { it.packageName == POWERAMP_PACKAGE }
                .thenByDescending { it.isPlaying }
                .thenByDescending { it.lastPlayingAt },
        ).first()
    }

    /** Read once per publish, rather than metadata and state over again for each question. */
    private fun MediaController.asCandidate(): Candidate = Candidate(
        packageName = packageName,
        hasMetadata = !metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank(),
        isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
        lastPlayingAt = lastPlayingAt[packageName] ?: 0L,
        owner = this,
    )

    private fun MediaController.track(md: MediaMetadata?): TrackInfo = TrackInfo(
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim(),
        artist = (
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ).orEmpty().trim(),
        album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim(),
        durationMs = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
        packageName = packageName,
        artworkUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
    )

    private fun MediaController.toSnapshot(): PlayerSnapshot {
        val md = metadata
        val state = playbackState
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val track = track(md)

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val playback = PlaybackPosition(
            isPlaying = isPlaying,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            atElapsedRealtime = state?.lastPositionUpdateTime?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0.05f } ?: 1f,
            durationMs = duration.coerceAtLeast(0L),
        )

        // The cover, from the player's own metadata: a bitmap first, then a
        // cached URI cover. Some players publish only a URI. Reading it
        // needs I/O, so it cannot happen here on the main thread — the
        // snapshot goes out without it and the bitmap arrives in a later
        // one. A metadata bitmap is scaled down before it is kept: the
        // template's image travels to the car host over Binder.
        //
        // Guarded: a bad metadata bitmap (recycled, malformed, huge) must
        // never throw out of publish(), which runs on the main thread from
        // framework callbacks — that would take the whole car app down. On
        // failure the cover is simply null and the URI path below still runs.
        val art = runCatching {
            md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?.let { Artwork.scaleDown(it) }
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?.let { Artwork.scaleDown(it) }
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?.let { Artwork.scaleDown(it) }
        }.getOrNull()
            ?: artworkCache.get(track.artworkUri).also { if (it == null) loadArtwork(track) }

        return PlayerSnapshot(
            track = track,
            playback = playback,
            sourcePackage = packageName,
            sourceLabel = appLabel(packageName),
            transport = state.transport(),
            artwork = art,
        )
    }

    /**
     * Fetch artwork that was published as a URI rather than a bitmap.
     *
     * One attempt per URI, cached, and the result is published by re-running
     * [publish] so it reaches the UI through the same path as everything
     * else. Only `content://` and `file://` are opened (see
     * [Artwork.isLocalUri]): an `http(s)://` artwork URI would need a
     * download, and this app has no network permission.
     */
    private fun loadArtwork(track: TrackInfo) {
        val uri = track.artworkUri?.takeIf { Artwork.isLocalUri(it) } ?: return
        if (!artworkAttempted.add(uri)) return

        artworkScope.launch {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    decodeArtwork(stream)
                }
            }.getOrNull() ?: return@launch

            // Bounded: a long listening session must not accumulate covers.
            artworkCache.put(uri, bitmap)
            // Only publish while still watching: a load that finishes after
            // stop() must not resurrect a snapshot nobody is showing.
            if (started) publish()
        }
    }

    /**
     * Decode a cover stream, downsampling so the longest side fits
     * [Artwork.MAX_DIMENSION_PX] (sampled decode first, exact scale after,
     * so a 1000px cover lands near the cap instead of a quarter of it).
     */
    private fun decodeArtwork(stream: InputStream): Bitmap? {
        val bytes = stream.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampled = BitmapFactory.Options().apply {
            inSampleSize = Artwork.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)
            ?: return null
        return Artwork.scaleDown(decoded)
    }

    /**
     * What this session says it will accept.
     *
     * `ACTION_PLAY_PAUSE` is the combined flag some players use instead of naming both, so
     * either spelling counts.
     */
    private fun PlaybackState?.transport(): Transport {
        val actions = this?.actions ?: return Transport()
        fun has(flag: Long) = actions and flag != 0L
        return Transport(
            playPause = has(PlaybackState.ACTION_PLAY) ||
                has(PlaybackState.ACTION_PAUSE) ||
                has(PlaybackState.ACTION_PLAY_PAUSE),
            skipNext = has(PlaybackState.ACTION_SKIP_TO_NEXT),
            skipPrevious = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            seek = has(PlaybackState.ACTION_SEEK_TO),
        )
    }

    /**
     * The player's display name.
     *
     * Android 11 hides other packages from us unless the manifest declares an interest
     * in them, and even with the `<queries>` block a player that exposes no media
     * service stays invisible — so fall back to something readable rather than showing
     * `com.example.someplayer` in the UI.
     */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { prettifyPackageName(pkg) }

    private fun prettifyPackageName(pkg: String): String =
        pkg.substringAfterLast('.')
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
            .ifBlank { pkg }

    // ---- position ticker ---------------------------------------------------

    /**
     * Media sessions only publish a position when something changes, so
     * between updates the playhead would stand still. While something is
     * playing, advance the published snapshot on a short tick so the lyrics
     * follow the song; the extrapolation itself lives in
     * [PlaybackPosition.currentMs].
     */
    private fun updateTicker() {
        if (!_snapshot.value.playback.isPlaying) {
            stopTicker()
            return
        }
        if (tickerJob != null) return
        tickerJob = scope.launch {
            while (true) {
                delay(TICKER_INTERVAL_MS)
                tickPosition()
            }
        }
    }

    private fun tickPosition() {
        val current = _snapshot.value
        val playback = current.playback
        if (!playback.isPlaying) {
            stopTicker()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val advanced = playback.currentMs(now)
        if (advanced == playback.positionMs) return
        _snapshot.value = current.copy(
            playback = playback.copy(
                positionMs = advanced,
                atElapsedRealtime = now,
            ),
        )
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ---- transport controls -------------------------------------------------

    fun togglePlayPause() = onMain {
        val controller = selected ?: return@onMain
        runCatching {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }
    }

    fun skipNext() = onMain {
        runCatching { selected?.transportControls?.skipToNext() }
    }

    fun skipPrevious() = onMain {
        runCatching { selected?.transportControls?.skipToPrevious() }
    }

    fun seekTo(positionMs: Long) = onMain {
        val controller = selected ?: return@onMain
        runCatching { controller.transportControls.seekTo(positionMs.coerceAtLeast(0L)) }
        // Optimistically move our own playhead so the lyrics jump immediately
        // instead of waiting for the player to echo the seek back.
        _snapshot.value = _snapshot.value.let { current ->
            current.copy(
                playback = current.playback.copy(
                    positionMs = positionMs.coerceAtLeast(0L),
                    atElapsedRealtime = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    // ---- per-controller callbacks ------------------------------------------

    private inner class ControllerWatcher(private val controller: MediaController) {

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                publish()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    lastPlayingAt[controller.packageName] = SystemClock.elapsedRealtime()
                }
                publish()
            }

            override fun onSessionDestroyed() {
                watched.remove(controller)?.detach()
                publish()
            }
        }

        fun attach() {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                lastPlayingAt[controller.packageName] = SystemClock.elapsedRealtime()
            }
            controller.registerCallback(callback, handler)
        }

        fun detach() {
            runCatching { controller.unregisterCallback(callback) }
        }
    }

    companion object {
        val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"

        private const val TAG = "MediaSessionRepo"

        /** Roughly 0.4 s, 0.8 s, 1.6 s, 3.2 s, 6.4 s — long enough to cover the bind. */
        private const val MAX_BIND_ATTEMPTS = 5
        private const val BIND_RETRY_BASE_MS = 400L

        /** How often the published playhead advances while playing. */
        private const val TICKER_INTERVAL_MS = 500L
    }
}
