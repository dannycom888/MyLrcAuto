package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.startMirrorFromCarApp
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.TrackInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car-app start path for the mirror media session (v1.0.14).
 *
 * v1.0.13 started the service only from the snapshot hook, and the
 * hook's background start was blocked while the phone sat in the
 * pocket — the card stayed Poweramp's even with 1.0.13 installed.
 * So the car app also starts the service itself when the session and
 * the main screen are created, when it is the foreground car app and
 * a start is allowed. These tests pin that the start fires exactly
 * while a track is loaded, and never when there is no track.
 */
class CarMirrorStartTest {

    private val track = TrackInfo(
        title = "一千年以后",
        artist = "林俊杰",
        album = "编号 89757",
        durationMs = 227_000L,
        packageName = "com.maxmpz.audioplayer",
    )

    private fun snapshot(isPlaying: Boolean, track: TrackInfo? = this.track) =
        PlayerSnapshot(
            track = track,
            playback = PlaybackPosition(
                isPlaying = isPlaying,
                positionMs = 100_000L,
                atElapsedRealtime = 1_000L,
                speed = 1f,
                durationMs = track?.durationMs ?: 0L,
            ),
            sourcePackage = track?.packageName,
            sourceLabel = "Poweramp",
        )

    @Test
    fun playingTrackStartsTheService() {
        var started = false
        startMirrorFromCarApp(snapshot(isPlaying = true)) { started = true }
        assertTrue(started)
    }

    @Test
    fun pausedTrackStillStartsTheService() {
        // v1.0.14: the mirror is wanted while a track is loaded, playing
        // or paused — a paused track still has a lyric line for the card.
        var started = false
        startMirrorFromCarApp(snapshot(isPlaying = false)) { started = true }
        assertTrue(started)
    }

    @Test
    fun noTrackDoesNotStartTheService() {
        var started = false
        startMirrorFromCarApp(snapshot(isPlaying = true, track = null)) { started = true }
        assertFalse(started)
        startMirrorFromCarApp(PlayerSnapshot()) { started = true }
        assertFalse(started)
    }

    @Test
    fun emptyTrackDoesNotStartTheService() {
        val empty = TrackInfo(title = "", artist = "", album = "", durationMs = 0L)
        var started = false
        startMirrorFromCarApp(snapshot(isPlaying = true, track = empty)) { started = true }
        assertFalse(started)
    }
}
