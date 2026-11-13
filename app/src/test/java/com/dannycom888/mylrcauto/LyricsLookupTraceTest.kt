package com.dannycom888.mylrcauto

import android.content.ContextWrapper
import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.FolderWalk
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LocalLyricsSource
import com.dannycom888.mylrcauto.lyrics.LookupMatchStep
import com.dannycom888.mylrcauto.lyrics.LookupOutcome
import com.dannycom888.mylrcauto.lyrics.LyricsIndexState
import com.dannycom888.mylrcauto.lyrics.StoredNameKind
import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        var droppedCount: Int = 0,
    ) : LocalLyricsSource {
        override fun isConfigured(): Boolean = true

        override suspend fun listAll(): FolderWalk {
            if (failWalk) throw IllegalStateException("folder gone")
            return FolderWalk(lrcFiles, audioFiles, droppedCount)
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
        // v1.0.23: the index's audio side, the raw probe and the
        // producing step are recorded too.
        assertEquals(1, trace.indexAudioCount)
        assertEquals(1, trace.rawProbeLrcCount)
        assertEquals(1, trace.rawProbeAudioCount)
        assertEquals(LookupMatchStep.AUDIO_WITH_ARTIST, trace.matchStep)

        // The index half of the block: ready, with the served count.
        val diag = provider.indexDiag.value
        assertEquals(LyricsIndexState.READY, diag.state)
        assertEquals(2, diag.lrcCount)
        assertEquals(1, diag.audioCount)
        assertEquals(0, diag.droppedCount)
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
        // Nothing in the index carries the title at all: the probe
        // is 0/0 — the "entries absent" signature.
        assertEquals(1, trace.indexAudioCount)
        assertEquals(0, trace.rawProbeLrcCount)
        assertEquals(0, trace.rawProbeAudioCount)
        assertNull(trace.matchStep)
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
        // The discriminator the v1.0.22 trace could not provide:
        // the probe is POSITIVE (the entries ARE in the index)
        // while the outcome is NOT_FOUND — so this miss is the
        // matcher's doing, not the walk's. (The device traces are
        // the opposite shape; see deviceTraceShapesAreReproducible.)
        assertEquals(1, trace.rawProbeLrcCount)
        assertEquals(1, trace.rawProbeAudioCount)
        assertNull(trace.matchStep)
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
        assertNull(trace.indexAudioCount)
        assertNull(trace.rawProbeLrcCount)
        assertNull(trace.rawProbeAudioCount)
        assertEquals(LookupOutcome.ERROR, trace.outcome)
        assertEquals(LyricsIndexState.UNREADABLE, provider.indexDiag.value.state)
        assertNull(provider.indexDiag.value.lrcCount)
        assertNull(provider.indexDiag.value.audioCount)
        assertNull(provider.indexDiag.value.droppedCount)
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
        assertEquals(LookupMatchStep.NAME_WITH_ARTIST, trace.matchStep)
    }

    @Test
    fun deviceTraceShapesAreReproducible() {
        // The two v1.0.22 device traces (2026-11-12 23:24 / 23:25):
        // clean inputs — title "不再犹豫" / artist "Beyond" and
        // title "九百九十九朵玫瑰" / artist "邰正宵", both from
        // Poweramp — against an index that reported ready, yet
        // audio found NO, fallback 0, NO_MATCH, for files PROVEN
        // present in the picked tree. The fake source here serves
        // an index WITHOUT the entries — the degraded-walk shape —
        // and the v1.0.23 probe is what names it: 0/0, entries
        // absent. (The same inputs against an index WITH the
        // entries resolve — see sameStemHitRecordsInputsAndSteps
        // and titleOnlyAudioFileHitsViaTheRetry.)
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/boy/Beyond - 喜欢你.lrc", "Beyond - 喜欢你.lrc"),
            ),
            audioFiles = listOf(
                LocalAudioFile("content://test/boy/Beyond - 喜欢你.mp3", "Beyond - 喜欢你.mp3"),
            ),
            bytesByName = emptyMap(),
        )
        val provider = providerFor(source)

        val byBeyond = runBlocking {
            provider.fetch(
                TrackInfo(
                    title = "不再犹豫",
                    artist = "Beyond",
                    album = "",
                    durationMs = 231_000L,
                    packageName = "com.maxmpz.audioplayer",
                ),
            )
        }
        assertNull(byBeyond)
        val beyondTrace = provider.lastLookup.value!!
        assertEquals(false, beyondTrace.audioFileFound)
        assertEquals(0, beyondTrace.fallbackMatchCount)
        assertEquals(LookupOutcome.NOT_FOUND, beyondTrace.outcome)
        assertEquals(0, beyondTrace.rawProbeLrcCount)
        assertEquals(0, beyondTrace.rawProbeAudioCount)
        assertNull(beyondTrace.matchStep)

        val byTai = runBlocking {
            provider.fetch("九百九十九朵玫瑰", listOf("邰正宵"), 0L)
        }
        assertNull(byTai)
        val taiTrace = provider.lastLookup.value!!
        assertEquals(false, taiTrace.audioFileFound)
        assertEquals(0, taiTrace.fallbackMatchCount)
        assertEquals(LookupOutcome.NOT_FOUND, taiTrace.outcome)
        assertEquals(0, taiTrace.rawProbeLrcCount)
        assertEquals(0, taiTrace.rawProbeAudioCount)
    }

    @Test
    fun titleOnlyAudioFileHitsViaTheRetry() {
        // The 九百九十九朵玫瑰 files exist but carry NO artist in
        // their names: the artist pass cannot find the audio file,
        // the v1.0.23 title-only retry can, and the trace names the
        // step that did it.
        val timed = "[00:01.00]九百九十九朵玫瑰".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/girl/九百九十九朵玫瑰.lrc", "九百九十九朵玫瑰.lrc"),
            ),
            audioFiles = listOf(
                LocalAudioFile("content://test/girl/九百九十九朵玫瑰.mp3", "九百九十九朵玫瑰.mp3"),
            ),
            bytesByName = mapOf("九百九十九朵玫瑰.lrc" to timed),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("九百九十九朵玫瑰", listOf("邰正宵"), 0L)
        }
        assertNotNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(true, trace.audioFileFound)
        assertEquals("九百九十九朵玫瑰.lrc", trace.sameStemCandidate)
        assertEquals(true, trace.titleCheckPassed)
        assertNull(trace.fallbackMatchCount)
        assertEquals(LookupOutcome.FOUND_SAME_STEM, trace.outcome)
        assertEquals(LookupMatchStep.AUDIO_TITLE_ONLY, trace.matchStep)
        assertEquals(1, trace.rawProbeLrcCount)
        assertEquals(1, trace.rawProbeAudioCount)
    }

    @Test
    fun trackNumberPrefixedLrcHitsViaTitleOnlyNameStep() {
        // No audio file, and the .lrc's name is poisoned for the
        // key ranking by its track-number prefix: only the
        // title-only phase of the name fallback reaches it.
        val timed = "[00:01.00]九百九十九朵玫瑰".toByteArray(Charsets.UTF_8)
        val name = "01. 邰正宵 - 九百九十九朵玫瑰.lrc"
        val source = FakeSource(
            lrcFiles = listOf(LocalLrcFile("content://test/girl/$name", name)),
            audioFiles = emptyList(),
            bytesByName = mapOf(name to timed),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("九百九十九朵玫瑰", listOf("邰正宵"), 0L)
        }
        assertNotNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(false, trace.audioFileFound)
        assertEquals(1, trace.fallbackMatchCount)
        assertEquals(name, trace.winnerFile)
        assertEquals(LookupOutcome.FOUND_METADATA, trace.outcome)
        assertEquals(LookupMatchStep.NAME_TITLE_ONLY, trace.matchStep)
        assertEquals(0, trace.indexAudioCount)
        assertEquals(1, trace.rawProbeLrcCount)
        assertEquals(0, trace.rawProbeAudioCount)
    }

    @Test
    fun traditionalNamedIndexHitsSimplifiedTag() {
        // v1.0.24: the v1.0.23 device-trace inputs (title 不再犹豫,
        // artist Beyond, Poweramp) against an index whose entries
        // are named in TRADITIONAL characters. On v1.0.23 code this
        // fetch NO_MATCHed with the exact trace the user screenshotted
        // (audio found NO, fallback 0, probe 0/0); with script
        // folding inside normalize() the same-stem step hits.
        val source = FakeSource(
            lrcFiles = listOf(
                LocalLrcFile("content://test/boy/Beyond - 不再猶豫.lrc", "Beyond - 不再猶豫.lrc"),
                LocalLrcFile("content://test/boy/Beyond - 喜歡你.lrc", "Beyond - 喜歡你.lrc"),
            ),
            audioFiles = listOf(
                LocalAudioFile("content://test/boy/Beyond - 不再猶豫.mp3", "Beyond - 不再猶豫.mp3"),
            ),
            bytesByName = mapOf("Beyond - 不再猶豫.lrc" to fixtureBytes()),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch(
                TrackInfo(
                    title = "不再犹豫",
                    artist = "Beyond",
                    album = "",
                    durationMs = 231_000L,
                    packageName = "com.maxmpz.audioplayer",
                ),
            )
        }
        assertNotNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(true, trace.audioFileFound)
        assertEquals("Beyond - 不再猶豫.lrc", trace.sameStemCandidate)
        assertEquals(true, trace.titleCheckPassed)
        assertEquals("Beyond - 不再猶豫.lrc", trace.winnerFile)
        assertEquals(LookupOutcome.FOUND_SAME_STEM, trace.outcome)
        assertEquals(LookupMatchStep.AUDIO_WITH_ARTIST, trace.matchStep)
        // The raw probe stays RAW: the Traditional names do not
        // contain the Simplified title verbatim — probe 0/0 WITH a
        // hit is the folded-script signature, distinct from the
        // v1.0.23 probe-0/0-with-a-miss shape.
        assertEquals(0, trace.rawProbeLrcCount)
        assertEquals(0, trace.rawProbeAudioCount)
        // The v1.0.24 evidence rows: the title's codepoints, and
        // the closest stored names verbatim with their kinds.
        assertEquals("4E0D 518D 72B9 8C6B", trace.titleCodepoints)
        assertEquals(
            "Beyond - 不再猶豫.lrc",
            trace.closestNames.firstOrNull()?.fileName,
        )
        assertEquals(StoredNameKind.LRC, trace.closestNames.firstOrNull()?.kind)
        assertTrue(
            trace.closestNames.any {
                it.fileName == "Beyond - 不再猶豫.mp3" && it.kind == StoredNameKind.AUDIO
            },
        )
    }

    @Test
    fun simplifiedNamedIndexHitsTraditionalTag() {
        // The mirror direction through the whole provider: the
        // index is Simplified (beyondSource), the tag Traditional.
        val provider = providerFor(beyondSource())

        val document = runBlocking {
            provider.fetch("不再猶豫", listOf("Beyond"), 231_000L)
        }
        assertNotNull(document)

        val trace = provider.lastLookup.value!!
        assertEquals(true, trace.audioFileFound)
        assertEquals("Beyond - 不再犹豫.lrc", trace.winnerFile)
        assertEquals(LookupOutcome.FOUND_SAME_STEM, trace.outcome)
        assertEquals("4E0D 518D 7336 8C6B", trace.titleCodepoints)
    }

    @Test
    fun indexDiagReportsTheWalksDroppedCount() {        // The dropped-row count from the walk that built the served
        // index lands in the diagnostics block — on the device this
        // is the number that proves (or clears) the silent-drop
        // mechanism.
        val source = beyondSource().apply { droppedCount = 3 }
        val provider = providerFor(source)

        runBlocking { provider.fetch("不再犹豫", listOf("Beyond"), 231_000L) }

        val diag = provider.indexDiag.value
        assertEquals(LyricsIndexState.READY, diag.state)
        assertEquals(2, diag.lrcCount)
        assertEquals(1, diag.audioCount)
        assertEquals(3, diag.droppedCount)
    }

    @Test
    fun typoTagTitleHitsViaFuzzyStep() {
        // The v1.0.24 device trace, verbatim (user screenshot
        // 2026-11-13 00:18:34, "不再犹豫", Poweramp): the title
        // codepoints row reads 4E0D 518D 4F18 8C6B — the title
        // Poweramp reports is 不再优豫, with 优 (U+4F18) instead
        // of 犹 (U+72B9): a homophone TYPO in the MP3's tag. The
        // files are named correctly (the closest-names row showed
        // "Beyond - 不再犹豫.lrc"), so the matcher was RIGHT to
        // miss on every exact phase. On v1.0.24 code this fetch is
        // the recorded NO_MATCH; v1.0.25's fuzzy last resort is
        // what resolves it, and the trace names the step.
        val provider = providerFor(beyondSource())

        val document = runBlocking {
            provider.fetch(
                TrackInfo(
                    title = "不再优豫",
                    artist = "Beyond",
                    album = "",
                    durationMs = 231_000L,
                    packageName = "com.maxmpz.audioplayer",
                ),
            )
        }
        assertNotNull(document)
        assertEquals(50, document!!.lines.size)

        val trace = provider.lastLookup.value!!
        // The fuzzy AUDIO phase finds the playing file — but a
        // fuzzy audio hit is a normal audio hit, so its same-stem
        // .lrc still faces the exact title guard, and the guard
        // (correctly, on exact terms) rejects it: the title parts
        // differ by the typo. The trace keeps both facts visible.
        assertEquals(true, trace.audioFileFound)
        assertEquals("Beyond - 不再犹豫.lrc", trace.sameStemCandidate)
        assertEquals(false, trace.titleCheckPassed)
        // The fuzzy NAME phase then returns the same song's .lrc.
        assertEquals(1, trace.fallbackMatchCount)
        assertEquals("Beyond - 不再犹豫.lrc", trace.winnerFile)
        assertEquals(50, trace.parsedLineCount)
        assertEquals(LookupOutcome.FOUND_METADATA, trace.outcome)
        assertEquals(LookupMatchStep.FUZZY, trace.matchStep)
        // The device trace's own evidence rows, verbatim: the
        // mistyped title's codepoints, and a raw probe of 0/0 —
        // no stored name contains the TYPO either. Probe 0/0 with
        // a hit is now also the fuzzy signature.
        assertEquals("4E0D 518D 4F18 8C6B", trace.titleCodepoints)
        assertEquals(0, trace.rawProbeLrcCount)
        assertEquals(0, trace.rawProbeAudioCount)
    }
}
