package com.dannycom888.mylrcauto.media

import android.os.SystemClock

/** What a player says about the track that is playing. */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val packageName: String = "",
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
) {
    val hasTrack: Boolean get() = track != null && !track.isEmpty

    /** True when the player accepts at least one command, so a control row is worth showing. */
    val canControl: Boolean get() = transport.any
}
