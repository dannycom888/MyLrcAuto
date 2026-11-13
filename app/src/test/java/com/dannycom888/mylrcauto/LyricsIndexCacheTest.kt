package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LyricsIndexCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsIndexCacheTest {

    private val folderUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    private val lrcFiles = listOf(
        LocalLrcFile("content://x/%E9%99%88%E5%B0%8F%E6%98%A5%20-%20%E8%A1%97%E8%A7%92%E7%9A%84%E6%99%9A%E9%A3%8E.lrc", "陈小春 - 街角的晚风.lrc"),
        LocalLrcFile("content://x/quote.lrc", "Artist \"Quoted\" - Song (Live).lrc"),
        LocalLrcFile("content://x/backslash.lrc", "Back\\Slash - Test.lrc"),
    )

    private val audioFiles = listOf(
        LocalAudioFile("content://x/song.mp3", "陈小春 - 街角的晚风.mp3"),
        LocalAudioFile("content://x/other.flac", "Artist - Other.flac"),
    )

    @Test
    fun roundTripPreservesEntries() {
        val json = LyricsIndexCache.serialize(folderUri, lrcFiles, audioFiles)
        // The current format version is 2 (v1 was retired in v1.0.23 —
        // see parseRejectsOlderFormatVersion).
        assertTrue(json.startsWith("{\"v\":2,"))
        val parsed = LyricsIndexCache.parse(json)
        assertEquals(folderUri, parsed?.folderUri)
        assertEquals(lrcFiles, parsed?.lrc)
        assertEquals(audioFiles, parsed?.audio)
    }

    @Test
    fun roundTripHandlesEmptyLists() {
        val json = LyricsIndexCache.serialize(folderUri, emptyList(), emptyList())
        val parsed = LyricsIndexCache.parse(json)
        assertEquals(folderUri, parsed?.folderUri)
        assertEquals(emptyList<LocalLrcFile>(), parsed?.lrc)
        assertEquals(emptyList<LocalAudioFile>(), parsed?.audio)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(LyricsIndexCache.parse(""))
        assertNull(LyricsIndexCache.parse("not json"))
        assertNull(LyricsIndexCache.parse("{\"v\":2}"))
        assertNull(LyricsIndexCache.parse("{\"v\":3,\"folder\":\"x\",\"lrc\":[],\"audio\":[]}"))
        assertNull(LyricsIndexCache.parse("{\"v\":2,\"folder\":\"x\",\"lrc\":[{\"u\":1}],\"audio\":[]}"))
        assertNull(LyricsIndexCache.parse("{\"v\":2,\"folder\":\"x\",\"lrc\":[],\"audio\":[]} trailing"))
    }

    @Test
    fun parseRejectsOlderFormatVersion() {
        // A well-formed v1 document is still rejected: v1 caches were
        // written by the pre-v1.0.23 DocumentFile walk, which could
        // silently drop rows, and the degraded listing must be rebuilt
        // by the hardened walk rather than served again.
        assertNull(
            LyricsIndexCache.parse(
                "{\"v\":1,\"folder\":\"x\"," +
                    "\"lrc\":[{\"u\":\"content://x/a.lrc\",\"n\":\"A - B.lrc\"}]," +
                    "\"audio\":[{\"u\":\"content://x/a.mp3\",\"n\":\"A - B.mp3\"}]}",
            ),
        )
    }

    @Test
    fun parseRejectsTruncatedJson() {
        val json = LyricsIndexCache.serialize(folderUri, lrcFiles, audioFiles)
        assertNull(LyricsIndexCache.parse(json.dropLast(5)))
    }
}
