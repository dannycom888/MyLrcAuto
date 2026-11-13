package com.dannycom888.mylrcauto

import android.content.ContextWrapper
import com.dannycom888.mylrcauto.lyrics.FolderWalk
import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LocalLyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The provider's full lookup chain against a fake folder source.
 *
 * The provider's [android.content.Context] is only ever touched for
 * the on-disk index cache (its filesDir), and only when a folder URI
 * is configured — these tests pass `folderUri = { null }`, so the
 * context is stored and never dereferenced, and a bare
 * [ContextWrapper] stands in for it.
 */
class LocalLyricsProviderTest {

    /** The user's real "Beyond - 不再犹豫.lrc", byte-identical (UTF-8, CRLF, 50 timed lines). */
    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fixtures/beyond-bu-zai-you-yu.lrc")!!
            .readBytes()

    /** A folder source over in-memory entries, keyed by bare file name for reads. */
    private class FakeSource(
        var lrcFiles: List<LocalLrcFile>,
        var audioFiles: List<LocalAudioFile>,
        var bytesByName: Map<String, ByteArray>,
    ) : LocalLyricsSource {
        override fun isConfigured(): Boolean = true

        override suspend fun listAll(): FolderWalk =
            FolderWalk(lrcFiles, audioFiles, droppedCount = 0)

        override suspend fun readBytes(file: LocalLrcFile): ByteArray? =
            bytesByName[file.fileName]
    }

    private fun lrc(folder: String, name: String) = LocalLrcFile(
        uriString = "content://test/$folder/$name",
        fileName = name,
    )

    private fun audio(folder: String, name: String) = LocalAudioFile(
        uriString = "content://test/$folder/$name",
        fileName = name,
    )

    private fun providerFor(source: LocalLyricsSource): LocalLyricsProvider {
        val provider = LocalLyricsProvider(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Unconfined,
            hasFolder = { true },
            folderUri = { null },
            backgroundScope = null,
        )
        provider.attach(source)
        return provider
    }

    @Test
    fun fetchFindsSubfolderSameStemBeyondBuZaiYouYu() {
        // User report 2026-11-12: "Beyond - 不再犹豫.lrc" sits in the
        // Music/music chinese boy SUBFOLDER next to "Beyond -
        // 不再犹豫.mp3" (same stem, same folder); Poweramp shows the
        // lyrics, My LRC Auto showed none, and switching tracks away
        // and back did not help. The uploaded file itself was verified
        // good (UTF-8, CRLF, every line timed) — the fixture is that
        // file, byte-identical. This drives the provider's REAL fetch
        // (index build → audio-anchored find → same-stem .lrc →
        // v1.0.19 title verification → read + parse) with the pair
        // indexed among decoys as if from the sibling subfolders.
        val small = "[00:01.00]占位\n[00:05.00]歌词".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            lrcFiles = listOf(
                lrc("music chinese boy", "Beyond - 不再犹豫.lrc"),
                lrc("music chinese boy", "Beyond - 喜欢你.lrc"),
                lrc("music chinese boy", "汪峰 - 北京北京.lrc"),
                lrc("music chinese girl", "不再犹豫 (Live).lrc"),
            ),
            audioFiles = listOf(
                audio("music chinese boy", "Beyond - 不再犹豫.mp3"),
                audio("music chinese boy", "Beyond - 喜欢你.mp3"),
                audio("music chinese boy", "汪峰 - 北京北京.mp3"),
            ),
            bytesByName = mapOf(
                "Beyond - 不再犹豫.lrc" to fixtureBytes(),
                "Beyond - 喜欢你.lrc" to small,
                "汪峰 - 北京北京.lrc" to small,
                "不再犹豫 (Live).lrc" to small,
            ),
        )
        val provider = providerFor(source)

        val document = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }

        assertNotNull("the same-stem subfolder .lrc must be found", document)
        assertEquals("Beyond - 不再犹豫.lrc", document!!.sourceName)
        // The real file: 50 timed lines, CRLF endings — the parse
        // must survive both the count and the line endings.
        assertEquals(50, document.lines.size)
        assertEquals(0L, document.lines.first().timeMs)
        assertEquals("不再犹豫 - Beyond", document.lines.first().text)
        assertEquals(1_600L, document.lines[1].timeMs)
        assertEquals(231_210L, document.lines.last().timeMs)
        assertEquals("终可见", document.lines.last().text)
    }

    @Test
    fun indexVersionBumpsWhenAFreshWalkReplacesTheIndex() {
        // The signal the app uses to re-run the current track's
        // lookup: a lookup answered by an older index (here, one
        // built before the file existed in the folder) must be
        // re-runnable once a fresh walk replaces that index.
        val source = FakeSource(
            lrcFiles = listOf(lrc("music chinese boy", "Beyond - 喜欢你.lrc")),
            audioFiles = listOf(audio("music chinese boy", "Beyond - 不再犹豫.mp3")),
            bytesByName = mapOf(
                "Beyond - 喜欢你.lrc" to
                    "[00:01.00]喜欢你".toByteArray(Charsets.UTF_8),
            ),
        )
        val provider = providerFor(source)
        assertEquals(0L, provider.indexVersion.value)

        // First lookup: the index is walked (version bumps) and the
        // track is missed — this walk predates the file.
        val missed = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertEquals(null, missed)
        val afterFirstWalk = provider.indexVersion.value
        assertEquals(1L, afterFirstWalk)

        // The file arrives in the folder; a rescan rebuilds the
        // index (another bump), and the same lookup now lands.
        source.lrcFiles = listOf(lrc("music chinese boy", "Beyond - 不再犹豫.lrc"))
        source.bytesByName = mapOf("Beyond - 不再犹豫.lrc" to fixtureBytes())
        runBlocking { provider.refreshIndex() }
        assertEquals(2L, provider.indexVersion.value)

        val found = runBlocking {
            provider.fetch("不再犹豫", listOf("Beyond"), 231_000L)
        }
        assertNotNull(found)
        assertEquals(50, found!!.lines.size)
    }
}
