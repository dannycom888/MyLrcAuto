package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.media.MirrorPlaybackState
import com.dannycom888.mylrcauto.media.PlayerSnapshot
import com.dannycom888.mylrcauto.media.PlaybackPosition
import com.dannycom888.mylrcauto.media.TrackInfo
import com.dannycom888.mylrcauto.media.Transport
import com.dannycom888.mylrcauto.media.mirrorMetadata
import com.dannycom888.mylrcauto.media.mirrorPlayback
import com.dannycom888.mylrcauto.media.signature
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
        assertFalse(playback.actions.skipPrevious)
        assertFalse(playback.actions.skipNext)
        assertFalse(playback.actions.seek)
    }
}
