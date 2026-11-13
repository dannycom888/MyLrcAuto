package com.dannycom888.mylrcauto

import android.content.ContextWrapper
import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LocalLyricsSource
import com.dannycom888.mylrcauto.lyrics.LyricsEngine
import com.dannycom888.mylrcauto.lyrics.LyricsState
import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's refresh: a lookup answered by a stale index must not
 * stand for the whole song.
 *
 * This is the "不再犹豫" shape (2026-11-12): the track is looked up
 * while the served index does not contain its .lrc (in the app, the
 * disk-cached index a background rebuild has not replaced yet), the
 * answer is None, and without a re-run nothing ever looks the track
 * up again — the snapshot hook only fires on a track change. The app
 * wires [LocalLyricsProvider.indexVersion] to [LyricsEngine.refresh];
 * this test drives the same sequence by hand.
 */
class LyricsEngineRefreshTest {

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fixtures/beyond-bu-zai-you-yu.lrc")!!
            .readBytes()

    private class FakeSource(
        var lrcFiles: List<LocalLrcFile>,
        var bytesByName: Map<String, ByteArray>,
    ) : LocalLyricsSource {
        override fun isConfigured(): Boolean = true

        override suspend fun listAll(): Pair<List<LocalLrcFile>, List<LocalAudioFile>> =
            lrcFiles to listOf(
                LocalAudioFile("content://test/mp3", "Beyond - 不再犹豫.mp3"),
            )

        override suspend fun readBytes(file: LocalLrcFile): ByteArray? =
            bytesByName[file.fileName]
    }

    @Test
    fun refreshRefetchesTheCurrentTrackAfterTheIndexIsRebuilt() {
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/decoy", "Beyond - 喜欢你.lrc"),
            ),
            bytesByName = mapOf(
                "Beyond - 喜欢你.lrc" to
                    "[00:01.00]喜欢你".toByteArray(Charsets.UTF_8),
            ),
        )
        // folderUri = null: no disk cache in play, the Context is
        // never dereferenced (see LocalLyricsProviderTest).
        val provider = LocalLyricsProvider(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Unconfined,
            hasFolder = { true },
            folderUri = { null },
            backgroundScope = null,
        )
        provider.attach(source)
        val engine = LyricsEngine(CoroutineScope(Dispatchers.Unconfined), provider)
        val track = TrackInfo(
            title = "不再犹豫",
            artist = "Beyond",
            album = "",
            durationMs = 231_000L,
        )

        // The standing lookup is answered by an index without the
        // file: no lyrics for this track.
        engine.setTrack(track)
        assertTrue(engine.state.value is LyricsState.None)

        // The index is rebuilt and now contains the file (in the
        // app, the background rebuild landing bumps indexVersion,
        // which the container collects into refresh()).
        source.lrcFiles = listOf(
            LocalLrcFile("content://test/lrc", "Beyond - 不再犹豫.lrc"),
        )
        source.bytesByName = mapOf("Beyond - 不再犹豫.lrc" to fixtureBytes())
        runBlocking { provider.refreshIndex() }

        // Refresh re-runs the SAME track's lookup against the new
        // index — no track change involved — and the lyrics land.
        engine.refresh()
        val state = engine.state.value
        assertTrue(state is LyricsState.Loaded)
        val document = (state as LyricsState.Loaded).document
        assertEquals("Beyond - 不再犹豫.lrc", document.sourceName)
        assertEquals(50, document.lines.size)
    }

    @Test
    fun refreshWithoutATrackIsANoOp() {
        val provider = LocalLyricsProvider(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Unconfined,
            hasFolder = { true },
            folderUri = { null },
            backgroundScope = null,
        )
        val engine = LyricsEngine(CoroutineScope(Dispatchers.Unconfined), provider)
        engine.refresh()
        assertTrue(engine.state.value is LyricsState.Idle)
    }
}
