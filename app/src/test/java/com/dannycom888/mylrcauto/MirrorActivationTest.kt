package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.media.MirrorAction
import com.dannycom888.mylrcauto.media.MirrorActivation
import com.dannycom888.mylrcauto.media.MirrorActivationTracker
import com.dannycom888.mylrcauto.media.MirrorStartResult
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import com.dannycom888.mylrcauto.media.shouldActivateMirror
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
    fun emptySnapshotDoesNotActivateTheMirrorSession() {
        // v1.0.19: activating the MediaSession with an empty snapshot
        // hands the car an empty action set, and this car locks the
        // card buttons from its first read and never re-derives them —
        // the card would show 10s rewind/fast-forward forever. The
        // session must stay inactive until a track is loaded.
        assertFalse(shouldActivateMirror(sessionActive = false, snapshot = PlayerSnapshot()))
        assertFalse(
            shouldActivateMirror(
                sessionActive = false,
                snapshot = snapshot(isPlaying = true, track = null),
            ),
        )
    }

    @Test
    fun loadedSnapshotActivatesTheMirrorSessionOnce() {
        assertTrue(
            shouldActivateMirror(sessionActive = false, snapshot = snapshot(isPlaying = true)),
        )
        // Already active: no second activation.
        assertFalse(
            shouldActivateMirror(sessionActive = true, snapshot = snapshot(isPlaying = true)),
        )
    }

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
    fun trackLoadedButNoCarDoesNotWantTheMirror() {
        // v1.0.18: a track loaded is not enough — the mirror runs only
        // while a car session is live. Without a car the service used
        // to start anyway and hold an active media session, which
        // blocked the phone's wireless Android Auto connection.
        assertFalse(MirrorActivation.shouldBeActive(snapshot(isPlaying = true), false))
        assertFalse(MirrorActivation.shouldBeActive(snapshot(isPlaying = false), false))
    }

    @Test
    fun trackLoadedAndCarConnectedWantsTheMirror() {
        assertTrue(MirrorActivation.shouldBeActive(snapshot(isPlaying = true), true))
        assertTrue(MirrorActivation.shouldBeActive(snapshot(isPlaying = false), true))
    }

    @Test
    fun noTrackAndCarConnectedDoesNotWantTheMirror() {
        assertFalse(
            MirrorActivation.shouldBeActive(snapshot(isPlaying = true, track = null), true),
        )
        assertFalse(MirrorActivation.shouldBeActive(PlayerSnapshot(), true))
    }

    @Test
    fun carDisconnectStopsTheMirror() {
        // The car going away must stop the service even though the
        // track is still loaded: the combined hook drives the tracker
        // with wantActive = carConnected && hasTrack, so a disconnect
        // flips it to false and the tracker issues STOP.
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        assertEquals(MirrorAction.STOP, tracker.update(false, track.key, nowMs = 1_000L))
        assertFalse(tracker.active)
    }

    @Test
    fun trackerStartsOnceOnFirstPlayingSnapshot() {
        val tracker = MirrorActivationTracker()
        assertFalse(tracker.active)
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        assertTrue(tracker.active)
    }

    @Test
    fun trackerDoesNotReStartWhileAlreadyActive() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        // The repository's 500ms position ticker re-emits the snapshot
        // while playing; that must not re-start a running service
        // within the retry interval.
        assertNull(tracker.update(true, track.key, nowMs = 500L))
        assertNull(tracker.update(true, track.key, nowMs = 4_999L))
        assertTrue(tracker.active)
    }

    @Test
    fun trackerStopsOnceWhenPlaybackStops() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        assertEquals(MirrorAction.STOP, tracker.update(false, null, nowMs = 1_000L))
        assertFalse(tracker.active)
        assertNull(tracker.update(false, null, nowMs = 2_000L))
    }

    @Test
    fun trackerRestartsAfterAStop() {
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        assertEquals(MirrorAction.STOP, tracker.update(false, null, nowMs = 1_000L))
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 2_000L))
        assertTrue(tracker.active)
    }

    @Test
    fun fullSequencePlayPausePlayNoTrack() {
        // v1.0.14: a paused track still counts as loaded, playing
        // or paused — so pausing does not stop it; only losing the
        // track does.
        val tracker = MirrorActivationTracker()
        val snapshots = listOf(
            snapshot(isPlaying = true),
            snapshot(isPlaying = true),
            snapshot(isPlaying = false),
            snapshot(isPlaying = true),
            snapshot(isPlaying = false, track = null),
        )
        val actions = snapshots.mapIndexed { index, snap ->
            tracker.update(
                MirrorActivation.shouldBeActive(snap),
                snap.track?.key,
                nowMs = index * 1_000L,
            )
        }
        assertEquals(
            listOf(MirrorAction.START, null, null, null, MirrorAction.STOP),
            actions,
        )
        assertFalse(tracker.active)
    }

    @Test
    fun trackerReStartsWhenTrackChanges() {
        // A service that died, or never started, must be re-attempted
        // when the next song loads — a flip-only tracker recorded
        // "active" once and never fired again, which stranded the
        // card for the whole drive.
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        val nextTrack = track.copy(title = "江南")
        assertEquals(
            MirrorAction.START,
            tracker.update(true, nextTrack.key, nowMs = 1_000L),
        )
        assertTrue(tracker.active)
    }

    @Test
    fun trackerRetriesStartAfterFiveSeconds() {
        // A start refused once is retried: the same track, still
        // wanted, earns another START once the retry interval has
        // passed — but not before.
        val tracker = MirrorActivationTracker()
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 0L))
        assertNull(tracker.update(true, track.key, nowMs = 4_999L))
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 5_000L))
        assertNull(tracker.update(true, track.key, nowMs = 9_999L))
        assertEquals(MirrorAction.START, tracker.update(true, track.key, nowMs = 10_000L))
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
