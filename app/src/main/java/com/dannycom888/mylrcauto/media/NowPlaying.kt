package com.dannycom888.mylrcauto.media

import android.graphics.Bitmap
import android.os.SystemClock

/** What a player says about the track that is playing. */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val packageName: String = "",
    /**
     * Where the player says the cover lives, if it publishes one at all —
     * METADATA_KEY_ALBUM_ART_URI, METADATA_KEY_ART_URI or
     * METADATA_KEY_DISPLAY_ICON_URI. Only `content://` and `file://` are
     * ever opened (see [Artwork.isLocalUri]); an `http(s)://` address is
     * left alone, because this app has no network permission.
     */
    val artworkUri: String? = null,
) {
    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank()

    /** Stable key for telling one track from the next. */
    val key: String
        get() = buildString {
            append(title.lowercase().trim())
            append('|')
            append(artist.lowercase().trim())
            append('|')
            append(durationMs / 2000)
        }
}

data class PlaybackPosition(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported. */
    val atElapsedRealtime: Long = 0L,
    val speed: Float = 1f,
    val durationMs: Long = 0L,
) {
    /**
     * Where the playhead is *now*. Media sessions only publish a position when
     * something changes, so between updates we extrapolate from the wall clock
     * — exactly what every "synced lyrics" implementation has to do.
     */
    fun currentMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val drift = ((nowElapsed - atElapsedRealtime) * speed).toLong()
        val projected = positionMs + drift
        return if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected.coerceAtLeast(0L)
    }

    /**
     * A new track starts at 0 — never with the old track's playhead.
     *
     * The player can publish a new track's metadata before it updates the
     * playback state, so a fresh snapshot can inherit the old track's
     * near-end position and timestamp. [currentMs] then clamps that to the
     * new track's duration and the playhead never advances again, which
     * leaves the lyrics highlight stuck on the last line. A position from
     * before the change belongs to the old track, so it is discarded. When
     * there was no previous track (the app just started mid-song) or the
     * track did not change, the state's own position is trusted as-is.
     */
    fun resetIfTrackChanged(
        oldTrack: TrackInfo?,
        newTrack: TrackInfo?,
        nowElapsed: Long,
    ): PlaybackPosition {
        if (oldTrack == null || oldTrack.isEmpty) return this
        if (newTrack == null || newTrack.isEmpty) return this
        if (oldTrack.key == newTrack.key) return this
        return copy(positionMs = 0L, atElapsedRealtime = nowElapsed)
    }

    companion object {
        val IDLE = PlaybackPosition()
    }
}

/**
 * Which transport operations the player actually supports.
 *
 * A session that publishes a playback state has not thereby promised to accept
 * every command: a live stream cannot be sought or rewound. It says which in
 * `PlaybackState.actions`, and a control that is shown but silently ignored is
 * worse than one that is visibly unavailable.
 */
data class Transport(
    val playPause: Boolean = false,
    val skipNext: Boolean = false,
    val skipPrevious: Boolean = false,
    val seek: Boolean = false,
) {
    val any: Boolean get() = playPause || skipNext || skipPrevious || seek
}

data class PlayerSnapshot(
    val track: TrackInfo? = null,
    val playback: PlaybackPosition = PlaybackPosition.IDLE,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val transport: Transport = Transport(),
    /**
     * The current track's cover, read from the player's own metadata (a
     * bitmap, or a locally opened artwork URI) and scaled down to
     * [Artwork.MAX_DIMENSION_PX]. Null while a URI cover is still loading
     * or when the player publishes none — never a previous track's cover:
     * every publish builds a fresh snapshot from the current metadata.
     */
    val artwork: Bitmap? = null,
) {
    val hasTrack: Boolean get() = track != null && !track.isEmpty

    /** True when the player accepts at least one command, so a control row is worth showing. */
    val canControl: Boolean get() = transport.any
}
