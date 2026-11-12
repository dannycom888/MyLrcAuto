package com.dannycom888.mylrcauto

import android.media.session.PlaybackState
import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.media.MirrorMetadata
import com.dannycom888.mylrcauto.media.MirrorPlaybackState
import com.dannycom888.mylrcauto.media.MirrorStateSink
import com.dannycom888.mylrcauto.media.MirrorTransportActions
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import com.dannycom888.mylrcauto.media.Transport
import com.dannycom888.mylrcauto.media.mirrorMetadata
import com.dannycom888.mylrcauto.media.mirrorPlayback
import com.dannycom888.mylrcauto.media.publishMirrorState
import com.dannycom888.mylrcauto.media.signature
import com.dannycom888.mylrcauto.media.toFramework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard card and the full-screen media view are drawn by the car
 * host from this app's mirror media session — never from a template — so
 * what the card shows is exactly what [mirrorMetadata] and
 * [mirrorPlayback] publish. The subtitle must be the line being sung
 * now, the description the next line, and a song with no lyrics must
 * never carry a stale line from the previous song.
 *
 * A real cover bitmap cannot be built in a plain JVM unit test (there
 * is no Robolectric in this project), so cover tests use a null bitmap
 * and pin the artwork URI instead; the `artwork != null` flag in the
 * signature is a single boolean expression, covered by inspection.
 */
class LyricsMirrorTest {

    private val track = TrackInfo(
        title = "一千年以后",
        artist = "林俊杰",
        album = "编号 89757",
        durationMs = 227_000L,
        packageName = "com.maxmpz.audioplayer",
        artworkUri = "content://media/cover/1",
    )

    private fun playingSnapshot(
        positionMs: Long = 100_000L,
        atElapsedRealtime: Long = 1_000L,
        isPlaying: Boolean = true,
        transport: Transport = Transport(
            playPause = true,
            skipPrevious = true,
            skipNext = true,
            seek = false,
        ),
    ) = PlayerSnapshot(
        track = track,
        playback = PlaybackPosition(
            isPlaying = isPlaying,
            positionMs = positionMs,
            atElapsedRealtime = atElapsedRealtime,
            speed = 1f,
            durationMs = track.durationMs,
        ),
        sourcePackage = track.packageName,
        sourceLabel = "Poweramp",
        transport = transport,
        artwork = null,
    )

    private val now = CarGlance.Now(
        previous = "上一句",
        line = "缠绕千年的寂寞",
        next = "下一句",
        instrumental = false,
    )

    @Test
    fun subtitleIsCurrentLineAndDescriptionIsNextLine() {
        val metadata = mirrorMetadata(playingSnapshot(), now)!!
        assertEquals("缠绕千年的寂寞", metadata.subtitle)
        assertEquals("下一句", metadata.description)
    }

    @Test
    fun trackFieldsAndCoverAreMirrored() {
        val metadata = mirrorMetadata(playingSnapshot(), now)!!
        assertEquals("一千年以后", metadata.title)
        assertEquals("林俊杰", metadata.artist)
        assertEquals("编号 89757", metadata.album)
        assertEquals(227_000L, metadata.durationMs)
        assertEquals("一千年以后", metadata.displayTitle)
        assertEquals("content://media/cover/1", metadata.artworkUri)
        assertNull(metadata.artwork)
        assertEquals(track.key, metadata.trackKey)
    }

    @Test
    fun noLyricsMeansEmptySubtitleNeverStale() {
        // The previous song had lines; this one has none — the card must
        // not keep showing the old line.
        val withLines = mirrorMetadata(playingSnapshot(), now)!!
        assertEquals("缠绕千年的寂寞", withLines.subtitle)
        val withoutLines = mirrorMetadata(playingSnapshot(), CarGlance.None)!!
        assertNull(withoutLines.subtitle)
        assertNull(withoutLines.description)
        assertNotEquals(withLines.signature(), withoutLines.signature())
    }

    @Test
    fun noTrackMeansNoMetadataAndPlaybackNone() {
        val empty = PlayerSnapshot()
        assertNull(mirrorMetadata(empty, CarGlance.Silent))
        val playback = mirrorPlayback(empty, nowElapsed = 5_000L)
        assertEquals(MirrorPlaybackState.NONE, playback.state)
        assertEquals(0L, playback.positionMs)
        assertEquals(0f, playback.speed)
        assertFalse(playback.actions.playPause)
        assertFalse(playback.actions.skipPrevious)
        assertFalse(playback.actions.skipNext)
        assertFalse(playback.actions.seek)
    }

    @Test
    fun instrumentalLineMeansNoSubtitleButNextLineShown() {
        val glance = CarGlance.Now(
            previous = "上一句",
            line = null,
            next = "下一句",
            instrumental = true,
        )
        val metadata = mirrorMetadata(playingSnapshot(), glance)!!
        assertNull(metadata.subtitle)
        assertEquals("下一句", metadata.description)
    }

    @Test
    fun lastLineHasNoDescription() {
        val glance = CarGlance.Now(
            previous = "上一句",
            line = "最后一句",
            next = null,
            instrumental = false,
        )
        val metadata = mirrorMetadata(playingSnapshot(), glance)!!
        assertEquals("最后一句", metadata.subtitle)
        assertNull(metadata.description)
    }

    @Test
    fun signatureChangesWhenLineChanges() {
        val first = mirrorMetadata(playingSnapshot(), now)!!.signature()
        val second = mirrorMetadata(
            playingSnapshot(),
            now.copy(line = "换了一行"),
        )!!.signature()
        assertNotEquals(first, second)
    }

    @Test
    fun signatureUnchangedWhenOnlyPositionAdvances() {
        // The playhead moves every 500ms while playing; that must not
        // re-publish the metadata — only the playback state moves.
        val first = mirrorMetadata(playingSnapshot(positionMs = 100_000L), now)!!
        val second = mirrorMetadata(playingSnapshot(positionMs = 100_500L), now)!!
        assertEquals(first, second)
        assertEquals(first.signature(), second.signature())
    }

    @Test
    fun signatureChangesWhenTrackChanges() {
        val first = mirrorMetadata(playingSnapshot(), now)!!.signature()
        val otherTrack = track.copy(title = "江南")
        val second = mirrorMetadata(playingSnapshot().copy(track = otherTrack), now)!!
            .signature()
        assertNotEquals(first, second)
    }

    @Test
    fun signatureChangesWhenArtworkUriChanges() {
        val first = mirrorMetadata(playingSnapshot(), now)!!.signature()
        val otherCover = track.copy(artworkUri = "content://media/cover/2")
        val second = mirrorMetadata(playingSnapshot().copy(track = otherCover), now)!!
            .signature()
        assertNotEquals(first, second)
    }

    @Test
    fun signatureHasNoCoverWhenArtworkIsNull() {
        val metadata = mirrorMetadata(playingSnapshot(), now)!!
        assertFalse(metadata.signature().artworkPresent)
    }

    @Test
    fun playbackPlayingMirrorsPositionAndSpeed() {
        val snapshot = playingSnapshot(positionMs = 1_000L, atElapsedRealtime = 1_000L)
        val playback = mirrorPlayback(snapshot, nowElapsed = 3_000L)
        assertEquals(MirrorPlaybackState.PLAYING, playback.state)
        assertEquals(3_000L, playback.positionMs)
        assertEquals(1f, playback.speed)
        assertTrue(playback.actions.playPause)
        assertTrue(playback.actions.skipPrevious)
        assertTrue(playback.actions.skipNext)
        assertFalse(playback.actions.seek)
    }

    @Test
    fun playbackPausedFreezesPositionAndSpeedZero() {
        val snapshot = playingSnapshot(
            positionMs = 5_000L,
            atElapsedRealtime = 1_000L,
            isPlaying = false,
        )
        val playback = mirrorPlayback(snapshot, nowElapsed = 99_000L)
        assertEquals(MirrorPlaybackState.PAUSED, playback.state)
        assertEquals(5_000L, playback.positionMs)
        assertEquals(0f, playback.speed)
    }

    @Test
    fun transportActionsOnlyForSupportedCapabilities() {
        // Play/pause and seek still follow what the player advertises —
        // but previous/next track are always offered for a track (see
        // skipButtonsOfferedEvenWhenPlayerDoesNotAdvertiseThem), so a
        // player that advertises nothing still gets skip buttons.
        val snapshot = playingSnapshot(
            transport = Transport(
                playPause = false,
                skipPrevious = false,
                skipNext = false,
                seek = false,
            ),
        )
        val playback = mirrorPlayback(snapshot, nowElapsed = 1_000L)
        assertFalse(playback.actions.playPause)
        assertTrue(playback.actions.skipPrevious)
        assertTrue(playback.actions.skipNext)
        assertFalse(playback.actions.seek)
    }

    @Test
    fun skipButtonsOfferedEvenWhenPlayerDoesNotAdvertiseThem() {
        // Poweramp supports track skipping through its transport
        // controls but does not advertise SKIP_TO_PREVIOUS / SKIP_TO_NEXT,
        // so copying the advertised actions left the mirror with no skip
        // buttons and the car showed 10s rewind/fast-forward instead.
        // The mirror offers previous/next whenever there is a track.
        val snapshot = playingSnapshot(
            transport = Transport(
                playPause = true,
                skipPrevious = false,
                skipNext = false,
                seek = true,
            ),
        )
        val playback = mirrorPlayback(snapshot, nowElapsed = 1_000L)
        assertTrue(playback.actions.skipPrevious)
        assertTrue(playback.actions.skipNext)
        assertTrue(playback.actions.playPause)
        assertTrue(playback.actions.seek)
    }

    @Test
    fun publishedActionsAreSkipNotRewind() {
        // The card's buttons come from this bitmask: previous/next
        // track in, 10s rewind/fast-forward out.
        val actions = MirrorTransportActions(
            playPause = true,
            skipPrevious = true,
            skipNext = true,
            seek = true,
        ).toFramework()
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(actions and PlaybackState.ACTION_REWIND == 0L)
        assertTrue(actions and PlaybackState.ACTION_FAST_FORWARD == 0L)
    }

    @Test
    fun publishSendsSkipActionsToTheSession() {
        // The bitmask test above only proves the flags are right on a
        // builder object. This one drives the actual publish step the
        // service runs — publishMirrorState into a recording sink — and
        // asserts the skip actions really reach the session, with no
        // rewind/fast-forward. v1.0.14 had the right bitmask and called
        // setPlaybackState on every render, yet the car still showed
        // 10s buttons, because the session went active before the
        // first (async) render published any state; the fix publishes
        // a complete state synchronously from the current snapshot
        // right after activation through this same function, so this
        // test pins the publish path, not just the flags.
        val sink = RecordingSink()
        val signature = publishMirrorState(
            sink,
            playingSnapshot(
                transport = Transport(
                    playPause = true,
                    skipPrevious = false,
                    skipNext = false,
                    seek = true,
                ),
            ),
            now,
            null,
            nowElapsed = 1_000L,
        )
        assertEquals(1, sink.playbackStates.size)
        val actions = sink.playbackStates.single().actions
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(actions and PlaybackState.ACTION_REWIND == 0L)
        assertTrue(actions and PlaybackState.ACTION_FAST_FORWARD == 0L)
        // Metadata goes out too, on the first publish.
        assertEquals(1, sink.metadata.size)
        assertEquals("缠绕千年的寂寞", sink.metadata.single()?.subtitle)
        // And the returned signature is the published metadata's.
        assertEquals(sink.metadata.single()?.signature(), signature)
    }

    @Test
    fun publishSendsPlaybackStateBeforeMetadata() {
        // v1.0.18: the card's buttons come from the playback state's
        // actions, and the car reads them the moment the session goes
        // active — so the actions must be published before the
        // metadata, and the service publishes the whole state before
        // activating. A metadata-first order let a throwing metadata
        // publish take the buttons down with it.
        val sink = RecordingSink()
        publishMirrorState(sink, playingSnapshot(), now, null, nowElapsed = 1_000L)
        assertEquals(listOf("playback", "metadata"), sink.callOrder)
    }

    @Test
    fun throwingMetadataStillLeavesPlaybackStatePublished() {
        // A bad cover bitmap makes the metadata publish throw; the
        // playback state — and with it the skip buttons — must already
        // be out by then, and the throw must reach the caller so the
        // signature is not recorded as published (the render loop
        // re-sends the metadata on the next emission).
        val sink = ThrowingMetadataSink()
        var thrown: Throwable? = null
        try {
            publishMirrorState(sink, playingSnapshot(), now, null, nowElapsed = 1_000L)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is IllegalStateException)
        assertEquals(1, sink.playbackStates.size)
        val actions = sink.playbackStates.single().actions
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
    }

    @Test
    fun publishSkipsMetadataWhenSignatureUnchanged() {
        // Playback state is re-sent on every call, but metadata only
        // when its signature changed — the subtitle must never be
        // spammed while the line stays the same.
        val sink = RecordingSink()
        val snapshot = playingSnapshot()
        val first = publishMirrorState(sink, snapshot, now, null, nowElapsed = 1_000L)
        val second = publishMirrorState(sink, snapshot, now, first, nowElapsed = 1_000L)
        assertEquals(2, sink.playbackStates.size)
        assertEquals(1, sink.metadata.size)
        assertEquals(first, second)
    }

    @Test
    fun publishKeepsCardAliveForNoLyricsSong() {
        // A song with no lyrics still gets a card: the metadata
        // carries the cover, title and artist with an empty lyric
        // line, and the playback state stays PLAYING with the skip
        // actions. The card must never vanish just because there is
        // no line to show — the mirror publishes the track either way.
        val sink = RecordingSink()
        val signature = publishMirrorState(
            sink,
            playingSnapshot(),
            CarGlance.None,
            null,
            nowElapsed = 1_000L,
        )
        assertEquals(1, sink.metadata.size)
        val metadata = sink.metadata.single()!!
        assertEquals("一千年以后", metadata.title)
        assertEquals("林俊杰", metadata.artist)
        assertNull(metadata.subtitle)
        assertNull(metadata.description)
        assertEquals(1, sink.playbackStates.size)
        val playback = sink.playbackStates.single()
        assertEquals(MirrorPlaybackState.PLAYING, playback.state)
        assertTrue(playback.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(playback.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertEquals(metadata.signature(), signature)
    }

    /** Records what publishMirrorState sends, in call order. */
    private class RecordingSink : MirrorStateSink {
        data class PublishedPlayback(
            val state: MirrorPlaybackState,
            val positionMs: Long,
            val speed: Float,
            val actions: Long,
        )

        val playbackStates = mutableListOf<PublishedPlayback>()
        val metadata = mutableListOf<MirrorMetadata?>()

        /** "playback" or "metadata", in the order the sink was called. */
        val callOrder = mutableListOf<String>()

        override fun setPlaybackState(
            state: MirrorPlaybackState,
            positionMs: Long,
            speed: Float,
            actions: Long,
        ) {
            callOrder.add("playback")
            playbackStates.add(PublishedPlayback(state, positionMs, speed, actions))
        }

        override fun setMetadata(metadata: MirrorMetadata?) {
            callOrder.add("metadata")
            this.metadata.add(metadata)
        }
    }

    /** A sink whose metadata publish throws, like a bad cover bitmap. */
    private class ThrowingMetadataSink : MirrorStateSink {
        val playbackStates = mutableListOf<RecordingSink.PublishedPlayback>()

        override fun setPlaybackState(
            state: MirrorPlaybackState,
            positionMs: Long,
            speed: Float,
            actions: Long,
        ) {
            playbackStates.add(RecordingSink.PublishedPlayback(state, positionMs, speed, actions))
        }

        override fun setMetadata(metadata: MirrorMetadata?) {
            throw IllegalStateException("cannot parcel metadata")
        }
    }
}
