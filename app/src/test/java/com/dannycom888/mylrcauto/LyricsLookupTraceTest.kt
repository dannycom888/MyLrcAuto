package com.dannycom888.mylrcauto

import android.content.ContextWrapper
import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LocalLyricsSource
import com.dannycom888.mylrcauto.lyrics.LookupOutcome
import com.dannycom888.mylrcauto.lyrics.LyricsIndexState
import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The v1.0.22 lookup trace: every `fetch` records its inputs
 * verbatim and its step-by-step outcome into
 * [LocalLyricsProvider.lastLookup], and the index state lands in
 * [LocalLyricsProvider.indexDiag] — the record the Settings
 * lyrics-diagnostics block shows. These tests pin the trace for
 * each exit path of the lookup, using the same fake-source
 * harness as [LocalLyricsProviderTest].
 */
class LyricsLookupTraceTest {

    /** The user's real "Beyond - 不再犹豫.lrc", byte-identical (UTF-8, CRLF, 50 timed lines). */
    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fixtures/beyond-bu-zai-you-yu.lrc")!!
            .readBytes()

    private class FakeSource(
        var lrcFiles: List<LocalLrcFile>,
        var audioFiles: List<LocalAudioFile>,
        var bytesByName: Map<String, ByteArray>,
        var failWalk: Boolean = false,
    ) : LocalLyricsSource {
        override fun isConfigured(): Boolean = true

        override suspend fun listAll(): Pair<List<LocalLrcFile>, List<LocalAudioFile>> {
            if (failWalk) throw IllegalStateException("folder gone")
            return lrcFiles to audioFiles
        }

        override suspend fun readBytes(file: LocalLrcFile): ByteArray? =
            bytesByName[file.fileName]
    }

    private fun providerFor(
        source: LocalLyricsSource,
        hasFolder: () -> Boolean = { true },
    ): LocalLyricsProvider {
        val provider = LocalLyricsProvider(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Unconfined,
            hasFolder = hasFolder,
            folderUri = { null },
            backgroundScope = null,
        )
        provider.attach(source)
        return provider
    }

    private fun beyondSource() = FakeSource(
        lrcFiles = listOf(
            LocalLrcFile("content://test/boy/Beyond - 不再犹豫.lrc", "Beyond - 不再犹豫.lrc"),
            LocalLrcFile("content://test/boy/Beyond - 喜欢你.lrc", "Beyond - 喜欢你.lrc"),
        ),
        audioFiles = listOf(
            LocalAudioFile("content://test/boy/Beyond - 不再犹豫.mp3", "Beyond - 不再犹豫.mp3"),
        ),
        bytesByName = mapOf("Beyond - 不再犹豫.lrc" to fixtureBytes()),
    )

    @Test
    fun sameStemHitRecordsInputsAndSteps() {
        val provider = providerFor(beyondSource())
        val track = TrackInfo(
            title = "不再犹豫",
            artist = "Beyond",
            album = "",
            durationMs = 231_000L,
            packageName = "com.maxmpz.audioplayer",
        )

        val document = runBlocking { provider.fetch(track) }
        assertNotNull(document)

        val trace = provider.lastLookup.value
        assertNotNull(trace)
        trace!!
        // Inputs verbatim — including the source package, which is
        // how a Poweramp-vs-Kuwo input difference becomes visible.
        assertEquals("不再犹豫", trace.title)
        assertEquals("Beyond", trace.artist)
        assertEquals("com.maxmpz.audioplayer", trace.sourcePackage)
        assertEquals(2, trace.indexLrcCount)
        assertEquals(true, trace.audioFileFound)
        assertEquals("Beyond - 不再犹豫.lrc", trace.sameStemCandidate)
        assertEquals(true, trace.titleCheckPassed)
        // The fallback never ran.
        assertNull(trace.fallbackMatchCount)
        assertEquals("Beyond - 不再犹豫.lrc", trace.winnerFile)
        assertEquals(50, trace.parsedLineCount)
        assertEquals(0, trace.readFailedCount)
        assertEquals(0, trace.parseEmptyCount)
        assertEquals(LookupOutcome.FOUND_SAME_STEM, trace.outcome)

        // The index half of the block: ready, with the served count.
        val diag = provider.indexDiag.value
        assertEquals(LyricsIndexState.READY, diag.state)
        assertEquals(2, diag.lrcCount)
    }

    @Test
    fun notFoundRecordsThatBothStepsRan() {
        val provider = providerFor(beyondSource())

        val document = runBlocking {
            provider.fetch("晴天", listOf("周杰伦"), 0L)
        }
        assertNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(false, trace.audioFileFound)
        assertNull(trace.sameStemCandidate)
        assertNull(trace.titleCheckPassed)
        assertEquals(0, trace.fallbackMatchCount)
        assertNull(trace.winnerFile)
        assertNull(trace.parsedLineCount)
        assertEquals(LookupOutcome.NOT_FOUND, trace.outcome)
    }

    @Test
    fun titleGuardRejectionIsVisibleInTheTrace() {
        // The v1.0.19 guard's shape: the track is "爱" (周深), the
        // only audio file is the longer "周深 - 爱你.mp3" —
        // findAudioFile picks it by substring, its same-stem .lrc
        // is the WRONG song, and the title check rejects it. The
        // lookup then misses entirely. Before the trace existed
        // this exit was indistinguishable from "file not in the
        // folder".
        val timed = "[00:01.00]爱你".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/x/周深 - 爱你.lrc", "周深 - 爱你.lrc"),
            ),
            audioFiles = listOf(
                LocalAudioFile("content://test/x/周深 - 爱你.mp3", "周深 - 爱你.mp3"),
            ),
            bytesByName = mapOf("周深 - 爱你.lrc" to timed),
        )
        val provider = providerFor(source)

        val document = runBlocking { provider.fetch("爱", listOf("周深"), 0L) }
        assertNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(true, trace.audioFileFound)
        assertEquals("周深 - 爱你.lrc", trace.sameStemCandidate)
        assertEquals(false, trace.titleCheckPassed)
        assertEquals(0, trace.fallbackMatchCount)
        assertEquals(LookupOutcome.NOT_FOUND, trace.outcome)
    }

    @Test
    fun matchedButUntimedFileRecordsParseEmpty() {
        val untimed = "不再犹豫\n无聊望见了犹豫".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/boy/Beyond - 不再犹豫.lrc", "Beyond - 不再犹豫.lrc"),
            ),
            audioFiles = emptyList(),
            bytesByName = mapOf("Beyond - 不再犹豫.lrc" to untimed),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(false, trace.audioFileFound)
        assertEquals(1, trace.fallbackMatchCount)
        assertEquals(0, trace.readFailedCount)
        assertEquals(1, trace.parseEmptyCount)
        assertEquals(LookupOutcome.PARSE_EMPTY, trace.outcome)
    }

    @Test
    fun matchedButUnreadableFileRecordsReadFailed() {
        // The name is in the index (so the fallback matches it) but
        // opening it yields nothing — on the device this is the
        // URI/grant failure kind, a different bug from an untimed
        // file, and the trace keeps them apart.
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/boy/Beyond - 不再犹豫.lrc", "Beyond - 不再犹豫.lrc"),
            ),
            audioFiles = emptyList(),
            bytesByName = emptyMap(),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(1, trace.fallbackMatchCount)
        assertEquals(1, trace.readFailedCount)
        assertEquals(0, trace.parseEmptyCount)
        assertEquals(LookupOutcome.READ_FAILED, trace.outcome)
    }

    @Test
    fun failedWalkRecordsErrorAndUnreadableIndex() {
        val source = beyondSource().apply { failWalk = true }
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertNull(document)

        val trace = provider.lastLookup.value!!
        assertNull(trace.indexLrcCount)
        assertEquals(LookupOutcome.ERROR, trace.outcome)
        assertEquals(LyricsIndexState.UNREADABLE, provider.indexDiag.value.state)
        assertNull(provider.indexDiag.value.lrcCount)
    }

    @Test
    fun noFolderPickedShowsInTheIndexState() {
        val provider = providerFor(beyondSource(), hasFolder = { false })
        assertEquals(LyricsIndexState.NO_FOLDER, provider.indexDiag.value.state)
    }

    @Test
    fun metadataHitRecordsFoundMetadata() {
        // No audio file at all: the name fallback has to carry it,
        // and the trace says so (fallback count + winner).
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/boy/Beyond - 不再犹豫.lrc", "Beyond - 不再犹豫.lrc"),
            ),
            audioFiles = emptyList(),
            bytesByName = mapOf("Beyond - 不再犹豫.lrc" to fixtureBytes()),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertNotNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(false, trace.audioFileFound)
        assertEquals(1, trace.fallbackMatchCount)
        assertEquals("Beyond - 不再犹豫.lrc", trace.winnerFile)
        assertEquals(50, trace.parsedLineCount)
        assertEquals(LookupOutcome.FOUND_METADATA, trace.outcome)
    }
}
