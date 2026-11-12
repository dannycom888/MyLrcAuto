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
            skipPrevious = snapshot.transport.skipPrevious,
            skipNext = snapshot.transport.skipNext,
            seek = snapshot.transport.seek,
        ),
    )
}
