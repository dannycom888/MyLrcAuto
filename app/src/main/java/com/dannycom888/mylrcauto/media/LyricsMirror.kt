package com.dannycom888.mylrcauto.media

import android.graphics.Bitmap
import android.os.SystemClock
import com.dannycom888.mylrcauto.car.CarGlance

/**
 * The mirror media session, in pure form.
 *
 * Why this exists: the car draws the dashboard media card (split-screen,
 * map on the left) and the full-screen media view from the *active media
 * session* — never from a car-app template. My LRC Auto is a template app,
 * so its screen only appears when the app itself is opened full-screen;
 * no template fix can ever put a card in that slot. AALyrics gets its card
 * by publishing its own media session whose metadata carries the current
 * lyric line (see its `NowPlayingScreen`: the line goes in
 * `METADATA_KEY_DISPLAY_SUBTITLE`, the next line in
 * `METADATA_KEY_DISPLAY_DESCRIPTION`). This module is the same idea,
 * written against this app's own data: the Poweramp snapshot from
 * [MediaSessionRepository] and the lyric line from [CarGlance].
 *
 * The session is a mirror only: it never plays audio. Its transport
 * callback forwards play/pause/skip/seek to the player the repository
 * is following (Poweramp), and the repository already ignores this
 * app's own sessions when choosing what to follow, so the mirror can
 * never follow itself.
 *
 * Everything here is pure Kotlin except for holding a [Bitmap] (the
 * cover, passed through untouched, never drawn or measured here) — so
 * the rules are directly unit-testable on the JVM. The thin mapping to
 * framework `MediaMetadata` / `PlaybackState` lives in
 * [LyricsMediaService], not here.
 */
data class MirrorMetadata(
    /** Stable key of the track these words belong to (see [TrackInfo.key]). */
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** The cover, already scaled down by the repository. Null while loading or absent. */
    val artwork: Bitmap?,
    /** Where the cover came from, for telling one cover from another. Null when none. */
    val artworkUri: String?,
    val displayTitle: String,
    /** The line being sung right now, or null when there is none to show. */
    val subtitle: String?,
    /** The next line with words in it, or null when there is none. */
    val description: String?,
)

/**
 * What decides whether the published metadata is re-sent: every field
 * that shows on the card, and nothing else. The playhead is deliberately
 * not in here — it moves every 500ms while playing and belongs to the
 * playback state, not the metadata. The cover bitmap itself is not in
 * here either (a bitmap has no value equality); its URI plus whether one
 * is present is enough to notice a cover arriving or changing.
 */
data class MirrorMetadataSignature(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val artworkPresent: Boolean,
    val subtitle: String?,
    val description: String?,
)

/** The signature of this metadata: re-publish only when it changes. */
fun MirrorMetadata.signature(): MirrorMetadataSignature = MirrorMetadataSignature(
    trackKey = trackKey,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    artworkPresent = artwork != null,
    subtitle = subtitle,
    description = description,
)

/** What the host should show as the playback state. */
enum class MirrorPlaybackState {
    PLAYING,
    PAUSED,
    /** No track: nothing to show, and the session must not look alive. */
    NONE,
}

/** Which transport buttons the mirrored player actually supports. */
data class MirrorTransportActions(
    val playPause: Boolean = false,
    val skipPrevious: Boolean = false,
    val skipNext: Boolean = false,
    val seek: Boolean = false,
)

data class MirrorPlayback(
    val state: MirrorPlaybackState,
    val positionMs: Long,
    /** 0 when paused or idle — a stopped playhead does not drift. */
    val speed: Float,
    val actions: MirrorTransportActions,
)

/**
 * The narrow surface the mirror needs to publish its state, so the
 * publish step is unit-testable without an Android runtime. The real
 * sink is the framework `MediaSession` ([LyricsMediaService]); a test
 * uses a recording fake. The values are the pure mirror types plus the
 * actions bitmask — no framework `PlaybackState` / `MediaMetadata` is
 * built here, so a plain JVM test can drive the whole publish path.
 */
internal interface MirrorStateSink {
    fun setPlaybackState(
        state: MirrorPlaybackState,
        positionMs: Long,
        speed: Float,
        actions: Long,
    )

    fun setMetadata(metadata: MirrorMetadata?)
}

/**
 * Publishes the mirror state for this snapshot and glance: metadata
 * when its signature changed, playback state on every call. Returns the
 * new metadata signature, for the caller to remember.
 *
 * This is the actual publish step the service runs — not just the
 * bitmask — so a test can assert the skip actions really reach the
 * session. v1.0.14's card still showed 10s rewind/fast-forward on the
 * real car even though the bitmask was right and `render` called
 * `setPlaybackState` on every tick: the session went active in
 * `onCreate` before the first (async) render published any state, so
 * the host's dashboard card locked in its buttons from that first,
 * empty read and never re-derived them. v1.0.17 tried activate-first
 * with a synchronous publish right after, and the car still showed
 * 10s — the first read at activation wins. The fix is to publish a
 * complete state — with the skip actions — synchronously from the
 * current snapshot BEFORE the session goes active (see
 * [LyricsMediaService]), and this function is the single publish path
 * both the initial publish and the render loop use. v1.0.15 did
 * publish before activation, but unguarded, so a throwing first
 * publish was fatal to the whole service; v1.0.18 keeps the order
 * and guards it.
 */
internal fun publishMirrorState(
    sink: MirrorStateSink,
    snapshot: PlayerSnapshot,
    glance: CarGlance,
    lastSignature: MirrorMetadataSignature?,
    nowElapsed: Long = SystemClock.elapsedRealtime(),
): MirrorMetadataSignature? {
    val metadata = mirrorMetadata(snapshot, glance)
    val signature = metadata?.signature()
    // The playback state goes out FIRST, before the metadata. The
    // card's buttons come from its actions, and the car reads them
    // the moment the session goes active — so the actions must land
    // even if the metadata publish throws (a bad bitmap takes the
    // metadata down, and the old order let it take the buttons with
    // it). A failed metadata publish leaves the signature
    // un-updated, so the render loop re-sends it on the next
    // emission; the actions are already in place either way.
    val playback = mirrorPlayback(snapshot, nowElapsed)
    sink.setPlaybackState(
        playback.state,
        playback.positionMs,
        playback.speed,
        playback.actions.toFramework(),
    )
    if (signature != lastSignature) {
        sink.setMetadata(metadata)
    }
    return signature
}

/**
 * The metadata to publish for this snapshot and glance, or null when
 * there is no track (the session then publishes no metadata at all).
 *
 * The subtitle is the line being sung *now*, the description the next
 * line — both read fresh from [glance] on every render, so a song with
 * no lyrics never carries a stale line from the previous song, and a
 * track change clears the line at the same moment the title changes.
 * A glance that is not [CarGlance.Now] (looking, no lyrics, untimed,
 * no permission, nothing playing) has no line to show: subtitle and
 * description are null, never a label pretending to be a lyric.
 */
fun mirrorMetadata(snapshot: PlayerSnapshot, glance: CarGlance): MirrorMetadata? {
    val track = snapshot.track?.takeIf { !it.isEmpty } ?: return null
    val now = glance as? CarGlance.Now
    return MirrorMetadata(
        trackKey = track.key,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        artwork = snapshot.artwork,
        artworkUri = track.artworkUri,
        displayTitle = track.title,
        subtitle = now?.line?.takeIf { it.isNotBlank() },
        description = now?.next?.takeIf { it.isNotBlank() },
    )
}

/**
 * The playback state to publish for this snapshot.
 *
 * @param nowElapsed the clock for extrapolating the playhead; defaults
 *   to now, and is a parameter only so tests can pin it.
 */
fun mirrorPlayback(
    snapshot: PlayerSnapshot,
    nowElapsed: Long = SystemClock.elapsedRealtime(),
): MirrorPlayback {
    if (!snapshot.hasTrack) {
        return MirrorPlayback(
            state = MirrorPlaybackState.NONE,
            positionMs = 0L,
            speed = 0f,
            actions = MirrorTransportActions(),
        )
    }
    val playback = snapshot.playback
    return MirrorPlayback(
        state = if (playback.isPlaying) MirrorPlaybackState.PLAYING else MirrorPlaybackState.PAUSED,
        positionMs = playback.currentMs(nowElapsed),
        speed = if (playback.isPlaying) playback.speed else 0f,
        actions = MirrorTransportActions(
            playPause = snapshot.transport.playPause,
            // Previous/next track, always — never the 10s rewind /
            // fast-forward the car shows instead. Poweramp supports
            // track skipping through its transport controls (the
            // repository calls skipToNext/skipToPrevious directly, and
            // the mirror callback forwards them), but it does not
            // advertise SKIP_TO_PREVIOUS / SKIP_TO_NEXT in its
            // playback-state actions, so copying the advertised
            // actions left the mirror with no skip buttons at all and
            // the car fell back to 10s rewind/fast-forward. The card
            // gets previous/next; seek still follows the transport.
            skipPrevious = true,
            skipNext = true,
            seek = snapshot.transport.seek,
        ),
    )
}
