package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track-change playhead reset (v1.0.9): a new track must never inherit
 * the old track's position. The player can publish a new track's metadata
 * before it updates the playback state, so without the reset the new track
 * starts near its end, the playhead clamps to its duration and never
 * advances — the lyrics highlight stays stuck on the last line.
 */
class PlaybackResetTest {

    private fun track(title: String, artist: String = "Artist", durationMs: Long = 240_000L) =
        TrackInfo(title = title, artist = artist, album = "", durationMs = durationMs)

    private fun playing(positionMs: Long) = PlaybackPosition(
        isPlaying = true,
        positionMs = positionMs,
        atElapsedRealtime = 1_000L,
        speed = 1f,
        durationMs = 240_000L,
    )

    @Test
    fun `track change resets the playhead to zero`() {
        val reset = playing(230_000L).resetIfTrackChanged(
            oldTrack = track("Old Song"),
            newTrack = track("New Song"),
            nowElapsed = 5_000L,
        )
        assertEquals(0L, reset.positionMs)
        assertEquals(5_000L, reset.atElapsedRealtime)
        // Everything else about the new track's playback is kept.
        assertTrue(reset.isPlaying)
        assertEquals(1f, reset.speed, 0f)
        assertEquals(240_000L, reset.durationMs)
    }

    @Test
    fun `no previous track keeps the state's position`() {
        // App just started mid-song: the state's position is the only one
        // there is, so it is trusted as-is.
        val position = playing(90_000L)
        assertSame(
            position,
            position.resetIfTrackChanged(null, track("New Song"), 5_000L),
        )
    }

    @Test
    fun `same track keeps the state's position`() {
        val position = playing(90_000L)
        assertSame(
            position,
            position.resetIfTrackChanged(track("Song"), track("Song"), 5_000L),
        )
    }

    @Test
    fun `empty previous track keeps the state's position`() {
        val position = playing(90_000L)
        val empty = TrackInfo(title = "", artist = "", album = "", durationMs = 0L)
        assertSame(
            position,
            position.resetIfTrackChanged(empty, track("New Song"), 5_000L),
        )
    }

    @Test
    fun `null new track keeps the state's position`() {
        val position = playing(90_000L)
        assertSame(
            position,
            position.resetIfTrackChanged(track("Old Song"), null, 5_000L),
        )
    }
}
