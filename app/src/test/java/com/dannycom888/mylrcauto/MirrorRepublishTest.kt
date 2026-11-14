package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.media.MIRROR_REPUBLISH_ACTION
import com.dannycom888.mylrcauto.media.MirrorMetadata
import com.dannycom888.mylrcauto.media.MirrorPlaybackState
import com.dannycom888.mylrcauto.media.MirrorRepublishRecord
import com.dannycom888.mylrcauto.media.MirrorRepublishReason
import com.dannycom888.mylrcauto.media.MirrorStateSink
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import com.dannycom888.mylrcauto.media.Transport
import com.dannycom888.mylrcauto.media.formatMirrorRepublish
import com.dannycom888.mylrcauto.media.isMirrorRepublishAction
import com.dannycom888.mylrcauto.media.mirrorRepublishRecordFromPrefsMap
import com.dannycom888.mylrcauto.media.publishMirrorState
import com.dannycom888.mylrcauto.media.toPrefsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror re-publish (v1.0.32): the pure parts — which start
 * intents count as the trigger, how the Settings row reads, the
 * persistence round-trip — and the mechanism the service's
 * force is built on: publishing with no remembered signature
 * re-sends the metadata even when nothing about the track or
 * the lyric line changed. That re-send is the whole recovery:
 * after a host-side teardown the same song keeps playing, so
 * only a forced publish ever puts its title and lyric line
 * back on the taskbar widget.
 */
class MirrorRepublishTest {

    @Test
    fun onlyTheRepublishActionIsTheTrigger() {
        assertTrue(isMirrorRepublishAction(MIRROR_REPUBLISH_ACTION))
        assertFalse(isMirrorRepublishAction(null))
        assertFalse(isMirrorRepublishAction("android.media.browse.MediaBrowserService"))
        assertFalse(isMirrorRepublishAction(""))
    }

    @Test
    fun formatIsTimeAndTrigger() {
        val record = MirrorRepublishRecord(
            timestampMs = 1_763_000_000_000L,
            reason = MirrorRepublishReason.CAR_SESSION_START,
        )
        assertEquals(
            "20:55:57 · car session start",
            formatMirrorRepublish(record, "20:55:57", "car session start"),
        )
    }

    @Test
    fun prefsRoundTripKeepsEveryField() {
        for (reason in MirrorRepublishReason.entries) {
            val record = MirrorRepublishRecord(
                timestampMs = 1_763_000_000_000L,
                reason = reason,
            )
            assertEquals(record, mirrorRepublishRecordFromPrefsMap(record.toPrefsMap()))
        }
    }

    @Test
    fun aBrokenPrefsMapReadsAsNoRecord() {
        assertNull(mirrorRepublishRecordFromPrefsMap(emptyMap()))
        assertNull(mirrorRepublishRecordFromPrefsMap(mapOf("timestamp_ms" to 1L)))
        assertNull(
            mirrorRepublishRecordFromPrefsMap(
                mapOf("timestamp_ms" to 1L, "reason" to "NOT_A_REASON"),
            ),
        )
    }

    @Test
    fun forcingAPublishResendsUnchangedMetadataWithTheLyricLine() {
        // The drive after the teardown: same track, same line.
        // A normal publish sends the metadata once and then stays
        // silent while the signature is unchanged — the widget
        // would wait forever. Publishing with the remembered
        // signature forgotten (what the service's forceRepublish
        // does) sends the same metadata again, subtitle intact.
        val sink = RecordingSink()
        val snapshot = playingSnapshot()
        val first = publishMirrorState(sink, snapshot, now, null, nowElapsed = 1_000L)
        val second = publishMirrorState(sink, snapshot, now, first, nowElapsed = 1_000L)
        assertEquals(first, second)
        assertEquals(1, sink.metadata.size)

        val forced = publishMirrorState(sink, snapshot, now, null, nowElapsed = 1_000L)
        assertEquals(first, forced)
        assertEquals(2, sink.metadata.size)
        assertEquals("缠绕千年的寂寞", sink.metadata.last()?.subtitle)
        assertEquals("下一句", sink.metadata.last()?.description)
        assertEquals("一千年以后", sink.metadata.last()?.title)
        // The playback state went out on every publish, forced or not.
        assertEquals(3, sink.playbackStates.size)
    }

    private val track = TrackInfo(
        title = "一千年以后",
        artist = "林俊杰",
        album = "编号 89757",
        durationMs = 227_000L,
        packageName = "com.maxmpz.audioplayer",
        artworkUri = "content://media/cover/1",
    )

    private fun playingSnapshot() = PlayerSnapshot(
        track = track,
        playback = PlaybackPosition(
            isPlaying = true,
            positionMs = 100_000L,
            atElapsedRealtime = 1_000L,
            speed = 1f,
            durationMs = track.durationMs,
        ),
        sourcePackage = track.packageName,
        sourceLabel = "Poweramp",
        transport = Transport(
            playPause = true,
            skipPrevious = true,
            skipNext = true,
            seek = false,
        ),
        artwork = null,
    )

    private val now = CarGlance.Now(
        previous = "上一句",
        line = "缠绕千年的寂寞",
        next = "下一句",
        instrumental = false,
    )

    /** Records what publishMirrorState sends. */
    private class RecordingSink : MirrorStateSink {
        val playbackStates = mutableListOf<MirrorPlaybackState>()
        val metadata = mutableListOf<MirrorMetadata?>()

        override fun setPlaybackState(
            state: MirrorPlaybackState,
            positionMs: Long,
            speed: Float,
            actions: Long,
        ) {
            playbackStates.add(state)
        }

        override fun setMetadata(metadata: MirrorMetadata?) {
            this.metadata.add(metadata)
        }
    }
}
