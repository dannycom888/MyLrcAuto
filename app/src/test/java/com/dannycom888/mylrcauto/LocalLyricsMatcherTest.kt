package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.LocalAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLrcFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedLrcFile
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricsMatcherTest {

    private fun lrc(name: String) = LocalLrcFile(uriString = "content://x/$name", fileName = name)
    private fun audio(name: String) = LocalAudioFile(uriString = "content://x/$name", fileName = name)

    private fun indexedLrc(vararg names: String): List<IndexedLrcFile> =
        names.map { IndexedLrcFile(lrc(it), LocalLyricsMatcher.fileKeys(it)) }

    private fun indexedAudio(vararg names: String): List<IndexedAudioFile> =
        names.map { IndexedAudioFile(audio(it), LocalLyricsMatcher.fileStem(it)) }

    @Test
    fun normalizeFoldsFullWidthAndDropsPunctuation() {
        assertEquals("abc123", LocalLyricsMatcher.normalize("ＡＢＣ-123"))
        assertEquals("晴天live", LocalLyricsMatcher.normalize("晴天 (Live)"))
        assertEquals("晴天", LocalLyricsMatcher.normalize(LocalLyricsMatcher.stripBracketed("晴天 (Live)")))
        assertEquals("hello", LocalLyricsMatcher.normalize("  Hello! "))
    }

    @Test
    fun splitArtistsHandlesSeparators() {
        assertEquals(listOf("庄心妍", "祁隆"), LocalLyricsMatcher.splitArtists("庄心妍_祁隆"))
        assertEquals(listOf("A", "B"), LocalLyricsMatcher.splitArtists("A feat. B"))
        assertEquals(listOf("Taylor Swift"), LocalLyricsMatcher.splitArtists("Taylor Swift"))
        assertEquals(listOf("张三", "李四"), LocalLyricsMatcher.splitArtists("张三与李四"))
    }

    @Test
    fun splitArtistTitlePrefersSpacedSeparator() {
        assertEquals(
            "TF-Boys" to "青春修炼手册",
            LocalLyricsMatcher.splitArtistTitle("TF-Boys - 青春修炼手册"),
        )
        assertEquals(
            "庄心妍" to "一万个舍不得",
            LocalLyricsMatcher.splitArtistTitle("庄心妍 - 一万个舍不得"),
        )
        assertNull(LocalLyricsMatcher.splitArtistTitle("晴天"))
    }

    @Test
    fun fileKeysIncludeEachArtistSegment() {
        val keys = LocalLyricsMatcher.fileKeys("庄心妍_祁隆 - 一万个舍不得.lrc")
        assertTrue("庄心妍一万个舍不得" in keys)
        assertTrue("祁隆一万个舍不得" in keys)
    }

    @Test
    fun sameStemComparesFileNames() {
        assertTrue(
            LocalLyricsMatcher.sameStemLrc(
                "庄心妍_祁隆 - 一万个舍不得.mp3",
                "庄心妍_祁隆 - 一万个舍不得.lrc",
            ),
        )
        assertFalse(
            LocalLyricsMatcher.sameStemLrc(
                "庄心妍 - 一万个舍不得.mp3",
                "祁隆 - 一万个舍不得.lrc",
            ),
        )
    }

    @Test
    fun findAudioFileRequiresTitleAndArtist() {
        val files = indexedAudio(
            "庄心妍_祁隆 - 一万个舍不得.mp3",
            "一万个舍不得.mp3",
        )
        val found = LocalLyricsMatcher.findAudioFile(
            "一万个舍不得", listOf("庄心妍"), files,
        )
        assertEquals("庄心妍_祁隆 - 一万个舍不得.mp3", found?.file?.fileName)

        // Title-only file does not qualify when an artist was reported.
        val titleOnly = LocalLyricsMatcher.findAudioFile(
            "一万个舍不得", listOf("庄心妍"),
            indexedAudio("一万个舍不得.mp3"),
        )
        assertNull(titleOnly)

        // No artist reported: title alone is enough.
        val noArtist = LocalLyricsMatcher.findAudioFile(
            "一万个舍不得", emptyList(),
            indexedAudio("一万个舍不得.mp3"),
        )
        assertEquals("一万个舍不得.mp3", noArtist?.file?.fileName)
    }

    @Test
    fun sameStemLrcFindsIdenticalStem() {
        val audio = indexedAudio("庄心妍_祁隆 - 一万个舍不得.mp3").first()
        val index = indexedLrc(
            "庄心妍 - 一万个舍不得.lrc",
            "庄心妍_祁隆 - 一万个舍不得.lrc",
        )
        assertEquals(
            "庄心妍_祁隆 - 一万个舍不得.lrc",
            LocalLyricsMatcher.sameStemLrc(audio, index)?.file?.fileName,
        )
        assertNull(
            LocalLyricsMatcher.sameStemLrc(audio, indexedLrc("其他歌.lrc")),
        )
    }

    @Test
    fun matchPrefersArtistTitleOverTitleOnly() {
        val ranked = LocalLyricsMatcher.match(
            "晴天", listOf("周杰伦"),
            indexedLrc("晴天.lrc", "周杰伦 - 晴天.lrc"),
        )
        assertEquals("周杰伦 - 晴天.lrc", ranked.first().file.fileName)
    }

    @Test
    fun matchRejectsWrongArtist() {
        val ranked = LocalLyricsMatcher.match(
            "晴天", listOf("周杰伦"),
            indexedLrc("林俊杰 - 晴天.lrc"),
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun matchFallsBackToTitlePartWhenNoArtistReported() {
        val ranked = LocalLyricsMatcher.match(
            "晴天", emptyList(),
            indexedLrc("周杰伦 - 晴天.lrc"),
        )
        assertEquals("周杰伦 - 晴天.lrc", ranked.first().file.fileName)
    }

    @Test
    fun pickWinnerUsesDurationTieBreak() {
        val index = indexedLrc("周杰伦 - 晴天.lrc", "晴天.lrc")
        val parsed = listOf(
            index[0] to 200_000L,
            index[1] to 240_000L,
        )
        val winner = LocalLyricsMatcher.pickWinner(
            "晴天", listOf("周杰伦"), 245_000L, parsed,
        )
        // Same key tier for both here is false: artist-title ranks first, so
        // the artist-title file wins regardless of duration.
        assertEquals("周杰伦 - 晴天.lrc", winner?.file?.fileName)

        val titleOnlyIndex = indexedLrc("晴天 (Live).lrc", "晴天.lrc")
        val titleOnly = listOf(titleOnlyIndex[0] to 200_000L, titleOnlyIndex[1] to 240_000L)
        val durationWinner = LocalLyricsMatcher.pickWinner(
            "晴天", listOf("周杰伦"), 245_000L, titleOnly,
        )
        assertEquals("晴天.lrc", durationWinner?.file?.fileName)
    }

    @Test
    fun decodeBytesHandlesUtf8GbkAndBom() {
        val utf8 = "[00:01.00]晴天".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]晴天", LocalLyricsMatcher.decodeBytes(utf8))

        val gbk = "[00:01.00]中文歌词".toByteArray(Charset.forName("GBK"))
        assertEquals("[00:01.00]中文歌词", LocalLyricsMatcher.decodeBytes(gbk))

        val bom = "\uFEFF[00:01.00]晴天".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]晴天", LocalLyricsMatcher.decodeBytes(bom))
    }

    @Test
    fun parseBytesReturnsDocumentOrNull() {
        val doc = LocalLyricsMatcher.parseBytes(
            "[00:01.00]第一行\n[00:05.00]第二行".toByteArray(Charsets.UTF_8),
            "晴天.lrc",
        )
        assertEquals("晴天.lrc", doc?.sourceName)
        assertEquals(2, doc?.lines?.size)
        assertEquals(1_000L, doc?.lines?.first()?.timeMs)

        val untimed = LocalLyricsMatcher.parseBytes(
            "没有时间标签".toByteArray(Charsets.UTF_8),
            "plain.lrc",
        )
        assertNull(untimed)
    }

    @Test
    fun matchLrcAudioAnchored() {
        val files = listOf(lrc("庄心妍 - 一万个舍不得.lrc"), lrc("其他.lrc"))
        assertEquals(
            "庄心妍 - 一万个舍不得.lrc",
            LocalLyricsMatcher.matchLrc(files, audio("庄心妍 - 一万个舍不得.mp3"))?.fileName,
        )
        assertNull(LocalLyricsMatcher.matchLrc(files, null))
        assertNull(
            LocalLyricsMatcher.matchLrc(files, audio("不存在的歌.mp3")),
        )
    }

    @Test
    fun matchLrcMetadata() {
        val files = listOf(lrc("晴天.lrc"), lrc("周杰伦 - 晴天.lrc"))
        assertEquals(
            "周杰伦 - 晴天.lrc",
            LocalLyricsMatcher.matchLrc(files, "晴天", listOf("周杰伦"), 240_000L)?.fileName,
        )
    }
}
