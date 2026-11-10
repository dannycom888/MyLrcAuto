package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LyricsIndexCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(LyricsIndexCache.parse("{\"v\":1}"))
        assertNull(LyricsIndexCache.parse("{\"v\":2,\"folder\":\"x\",\"lrc\":[],\"audio\":[]}"))
        assertNull(LyricsIndexCache.parse("{\"v\":1,\"folder\":\"x\",\"lrc\":[{\"u\":1}],\"audio\":[]}"))
        assertNull(LyricsIndexCache.parse("{\"v\":1,\"folder\":\"x\",\"lrc\":[],\"audio\":[]} trailing"))
    }

    @Test
    fun parseRejectsTruncatedJson() {
        val json = LyricsIndexCache.serialize(folderUri, lrcFiles, audioFiles)
        assertNull(LyricsIndexCache.parse(json.dropLast(5)))
    }
}
