package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.media.MirrorAction
import com.dannycom888.mylrcauto.media.MirrorActivation
import com.dannycom888.mylrcauto.media.MirrorActivationTracker
import com.dannycom888.mylrcauto.media.MirrorStartResult
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import com.dannycom888.mylrcauto.media.startMirrorWithFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard card only appears when the mirror media session is
 * running, and the car host never starts it in the user's flow
 * (Poweramp selected, then tap the app — tapping opens the
 * CarAppService template, never the browser service). So the app
 * itself must start the service the moment a track is loaded and
 * stop it when there is no track — no picker selection. These tests
 * pin that decision and the tracker that turns the snapshot stream
 * into at most one start/stop per change. (v1.0.14: a paused track
 * still counts as loaded, so the card keeps its lyric line while
 * paused.)
 */
class MirrorActivationTest {

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
    fun playingTrackWantsTheMirror() {
        assertTrue(MirrorActivation.shouldBeActive(snapshot(isPlaying = true)))
    }

    @Test
    fun pausedTrackStillWantsTheMirror() {
        // v1.0.14: a paused track still has a current lyric line, and the
        // card should keep showing it — stopping on pause made the card
        // fall back to Poweramp's the moment he paused.
        assertTrue(MirrorActivation.shouldBeActive(snapshot(isPlaying = false)))
    }

    @Test
    fun noTrackDoesNotWantTheMirror() {
        assertFalse(MirrorActivation.shouldBeActive(snapshot(isPlaying = true, track = null)))
        assertFalse(MirrorActivation.shouldBeActive(snapshot(isPlaying = false, track = null)))
        assertFalse(MirrorActivation.shouldBeActive(PlayerSnapshot()))
    }

    @Test
    fun emptyTrackDoesNotWantTheMirror() {
        val empty = TrackInfo(title = "", artist = "", album = "", durationMs = 0L)
        assertFalse(MirrorActivation.shouldBeActive(snapshot(isPlaying = true, track = empty)))
    }

    @Test
    fun trackerStartsOnceOnFirstPlayingSnapshot() {
        val tracker = MirrorActivationTracker()
        assertFalse(tracker.active)
        assertEquals(MirrorAction.START, tracker.update(true))
        assertTrue(tracker.active)
    }

    @Test
    fun trackerDoesNotReStartWhileAlreadyActive() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true))
        // The repository's 500ms position ticker re-emits the snapshot
        // while playing; that must not re-start a running service.
        assertNull(tracker.update(true))
        assertNull(tracker.update(true))
        assertTrue(tracker.active)
    }

    @Test
    fun trackerStopsOnceWhenPlaybackStops() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true))
        assertEquals(MirrorAction.STOP, tracker.update(false))
        assertFalse(tracker.active)
        assertNull(tracker.update(false))
    }

    @Test
    fun trackerRestartsAfterAStop() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true))
        assertEquals(MirrorAction.STOP, tracker.update(false))
        assertEquals(MirrorAction.START, tracker.update(true))
        assertTrue(tracker.active)
    }

    @Test
    fun fullSequencePlayPausePlayNoTrack() {
        // v1.0.14: the mirror is wanted while a track is loaded, playing
        // or paused — so pausing does not stop it; only losing the track
        // does.
        val tracker = MirrorActivationTracker()
        val actions = listOf(
            snapshot(isPlaying = true),
            snapshot(isPlaying = true),
            snapshot(isPlaying = false),
            snapshot(isPlaying = true),
            snapshot(isPlaying = false, track = null),
        ).map { MirrorActivation.shouldBeActive(it) }
            .map { tracker.update(it) }
        assertEquals(
            listOf(MirrorAction.START, null, null, null, MirrorAction.STOP),
            actions,
        )
        assertFalse(tracker.active)
    }

    @Test
    fun plainStartSuccessDoesNotTouchForegroundStart() {
        var foregroundCalled = false
        val result = startMirrorWithFallback(
            startService = {},
            startForegroundService = { foregroundCalled = true },
        )
        assertEquals(MirrorStartResult.STARTED, result)
        assertFalse(foregroundCalled)
    }

    @Test
    fun backgroundRefusalFallsBackToForegroundStart() {
        var foregroundCalled = false
        val result = startMirrorWithFallback(
            startService = { throw IllegalStateException("background start not allowed") },
            startForegroundService = { foregroundCalled = true },
        )
        assertEquals(MirrorStartResult.STARTED_FOREGROUND, result)
        assertTrue(foregroundCalled)
    }

    @Test
    fun otherStartFailureDoesNotFallBack() {
        var foregroundCalled = false
        val result = startMirrorWithFallback(
            startService = { throw SecurityException("no permission") },
            startForegroundService = { foregroundCalled = true },
        )
        assertEquals(MirrorStartResult.FAILED, result)
        assertFalse(foregroundCalled)
    }

    @Test
    fun foregroundStartFailingTooReportsFailed() {
        val result = startMirrorWithFallback(
            startService = { throw IllegalStateException("background start not allowed") },
            startForegroundService = { throw IllegalStateException("foreground start failed") },
        )
        assertEquals(MirrorStartResult.FAILED, result)
    }
}
