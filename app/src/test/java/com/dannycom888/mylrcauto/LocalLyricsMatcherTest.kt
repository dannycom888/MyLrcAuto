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
    fun findAudioFilePrefersArtistThenRetriesTitleOnly() {
        val files = indexedAudio(
            "庄心妍_祁隆 - 一万个舍不得.mp3",
            "一万个舍不得.mp3",
        )
        val found = LocalLyricsMatcher.findAudioFile(
            "一万个舍不得", listOf("庄心妍"), files,
        )
        assertEquals("庄心妍_祁隆 - 一万个舍不得.mp3", found?.file?.fileName)

        // The artist pass wins when it can: no retry is recorded.
        val detailed = LocalLyricsMatcher.findAudioFileDetailed(
            "一万个舍不得", listOf("庄心妍"), files,
        )
        assertEquals("庄心妍_祁隆 - 一万个舍不得.mp3", detailed?.entry?.file?.fileName)
        assertEquals(false, detailed?.titleOnlyRetry)

        // v1.0.23: a title-only file DOES qualify when the artist
        // pass finds nothing — the retry is what the trace records
        // (before, this lookup returned null and the song showed no
        // lyrics even though its file was indexed).
        val titleOnly = LocalLyricsMatcher.findAudioFileDetailed(
            "一万个舍不得", listOf("庄心妍"),
            indexedAudio("一万个舍不得.mp3"),
        )
        assertEquals("一万个舍不得.mp3", titleOnly?.entry?.file?.fileName)
        assertEquals(true, titleOnly?.titleOnlyRetry)

        // No artist reported: title alone is enough, and that is the
        // primary pass, not a retry.
        val noArtist = LocalLyricsMatcher.findAudioFileDetailed(
            "一万个舍不得", emptyList(),
            indexedAudio("一万个舍不得.mp3"),
        )
        assertEquals("一万个舍不得.mp3", noArtist?.entry?.file?.fileName)
        assertEquals(false, noArtist?.titleOnlyRetry)
    }

    @Test
    fun findAudioFileRetryKeepsResidueRanking() {
        // Both files carry the title and neither carries the artist:
        // the retry still prefers the least extra text.
        val detailed = LocalLyricsMatcher.findAudioFileDetailed(
            "九百九十九朵玫瑰", listOf("邰正宵"),
            indexedAudio(
                "01. 九百九十九朵玫瑰 (Live).mp3",
                "九百九十九朵玫瑰.mp3",
            ),
        )
        assertEquals("九百九十九朵玫瑰.mp3", detailed?.entry?.file?.fileName)
        assertEquals(true, detailed?.titleOnlyRetry)
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
    fun matchFallsBackToTitlePartForAnotherArtistsFile() {
        // v1.0.23: the key ranking still rejects the wrong artist —
        // but the lookup no longer ends there. The title-only phase
        // accepts the file on its exact title part (the pre-v1.0.23
        // behaviour returned nothing here, which is one of the
        // shapes behind the 2026-11-12 NO_MATCH traces).
        val result = LocalLyricsMatcher.matchDetailed(
            "晴天", listOf("周杰伦"),
            indexedLrc("林俊杰 - 晴天.lrc"),
        )
        assertEquals(
            listOf("林俊杰 - 晴天.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.titleOnlyFallback)
    }

    @Test
    fun matchTitleOnlyPhaseKeepsExactTitleEquality() {
        // The 爱/爱你 anti-substring protection survives the
        // fallback: the title part must EQUAL the track title after
        // normalization — a longer title that merely starts with it
        // never matches, with or without an artist part on the file.
        assertTrue(
            LocalLyricsMatcher.match(
                "爱", listOf("周杰伦"),
                indexedLrc("周杰伦 - 爱你.lrc"),
            ).isEmpty(),
        )
        assertTrue(
            LocalLyricsMatcher.match(
                "爱", listOf("周杰伦"),
                indexedLrc("爱你.lrc"),
            ).isEmpty(),
        )
    }

    @Test
    fun matchAcceptsBareDashArtistTitleNaming() {
        // "邰正宵-九百九十九朵玫瑰" (no spaces around the dash): the
        // normalized key ranking hits directly — no fallback needed.
        val result = LocalLyricsMatcher.matchDetailed(
            "九百九十九朵玫瑰", listOf("邰正宵"),
            indexedLrc("邰正宵-九百九十九朵玫瑰.lrc"),
        )
        assertEquals(
            listOf("邰正宵-九百九十九朵玫瑰.lrc"),
            result.files.map { it.file.fileName },
        )
        assertFalse(result.titleOnlyFallback)
    }

    @Test
    fun matchTrackNumberPrefixFallsBackToTitlePart() {
        // A track-number prefix ("01. ") poisons every key the file
        // contributes, so only the title-only phase can reach it.
        val result = LocalLyricsMatcher.matchDetailed(
            "九百九十九朵玫瑰", listOf("邰正宵"),
            indexedLrc("01. 邰正宵 - 九百九十九朵玫瑰.lrc"),
        )
        assertEquals(
            listOf("01. 邰正宵 - 九百九十九朵玫瑰.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.titleOnlyFallback)
    }

    @Test
    fun matchBareTitleFileStillRanksWithoutFallback() {
        // A bare "Title.lrc" matches on the track's bare-title key
        // itself — ranked, not a fallback product.
        val result = LocalLyricsMatcher.matchDetailed(
            "九百九十九朵玫瑰", listOf("邰正宵"),
            indexedLrc("九百九十九朵玫瑰.lrc"),
        )
        assertEquals(
            listOf("九百九十九朵玫瑰.lrc"),
            result.files.map { it.file.fileName },
        )
        assertFalse(result.titleOnlyFallback)
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
    fun decodeBytesHandlesUtf16WithBom() {
        // A .lrc saved as UTF-16 (the Windows Notepad "Unicode" save)
        // carries a byte-order mark. Run through a UTF-8/GBK decode it
        // comes out as NUL-interleaved garbage, no time tag ever
        // matches, and the file reads as untimed — while a player that
        // sniffs the BOM (Poweramp) shows its lyrics fine. The decode
        // must honour the mark, both endiannesses.
        val text = "[00:01.00]只想靠两手\n[00:05.00]向理想挥手"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, LocalLyricsMatcher.decodeBytes(le))

        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, LocalLyricsMatcher.decodeBytes(be))
    }

    @Test
    fun decodeBytesHandlesUtf16LeWithoutBom() {
        // UTF-16LE saved without a mark: the UTF-8 decode "succeeds"
        // (NUL is a valid UTF-8 byte), so the GBK fallback never runs
        // and the text stays NUL-interleaved. The NULs are the tell —
        // no real .lrc text contains them — so the decode retries as
        // UTF-16 and keeps the clean result.
        val text = "[00:01.00]只想靠两手"
        val le = text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, LocalLyricsMatcher.decodeBytes(le))
    }

    @Test
    fun parseBytesReadsUtf16Lrc() {
        // End to end: a UTF-16LE+BOM .lrc parses to timed lines, the
        // same answer the UTF-8 original gives.
        val text = "[00:01.00]只想靠两手\n[00:05.50]向理想挥手"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        val doc = LocalLyricsMatcher.parseBytes(bytes, "Beyond - 不再犹豫.lrc")
        assertEquals(2, doc?.lines?.size)
        assertEquals(1_000L, doc?.lines?.first()?.timeMs)
        assertEquals("只想靠两手", doc?.lines?.first()?.text)
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
    fun lrcTitleMatchesRejectsSubstringTitle() {
        // v1.0.19: the audio-anchored lookup used to return the
        // same-stem .lrc without checking its title — "爱" could pick
        // up "爱你.lrc" when the exact "爱" audio file was missing
        // from the folder. A substring title is not a match.
        assertFalse(LocalLyricsMatcher.lrcTitleMatches("爱你.lrc", "爱"))
        assertFalse(LocalLyricsMatcher.lrcTitleMatches("周杰伦 - 晴天.lrc", "晴"))
    }

    @Test
    fun lrcTitleMatchesAcceptsLiveSuffix() {
        // A "(Live)" / "[Live]" suffix still passes: the bracketed
        // part is stripped before comparing.
        assertTrue(LocalLyricsMatcher.lrcTitleMatches("晴天 (Live).lrc", "晴天"))
        assertTrue(LocalLyricsMatcher.lrcTitleMatches("Artist - Title (Live).lrc", "Title"))
        assertTrue(LocalLyricsMatcher.lrcTitleMatches("晴天 [Live].lrc", "晴天"))
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
    fun subfolderSameStemBeyondBuZaiYouYu() {
        // User report 2026-11-12: "Beyond - 不再犹豫.lrc" sits in the
        // Music/music chinese boy SUBFOLDER next to "Beyond -
        // 不再犹豫.mp3" — same stem, same folder — Poweramp shows the
        // lyrics, My LRC Auto showed none, and switching tracks away
        // and back did not help. The index is name-flat: the walk
        // recurses into subfolders and stores bare file names, so a
        // subfolder pair is indistinguishable from a root pair here —
        // which is the point: if this pipeline finds the file, the
        // miss cannot be in the matching, and subfolder coverage is
        // purely a property of the walk. The entries are indexed
        // exactly as LocalLyricsProvider.walkFolder indexes them,
        // with decoys as if from the sibling subfolders.
        val lrcIndex = indexedLrc(
            "Beyond - 不再犹豫.lrc",
            "Beyond - 喜欢你.lrc",
            "汪峰 - 北京北京.lrc",
            "不再犹豫 (Live).lrc",
        )
        val audioIndex = indexedAudio(
            "Beyond - 不再犹豫.mp3",
            "Beyond - 喜欢你.mp3",
            "汪峰 - 北京北京.mp3",
        )

        // Provider step 1, in its own order: find the audio file,
        // take the same-stem .lrc, verify its title (v1.0.19's guard
        // must NOT reject this file — its title part is the title).
        val audioFile = LocalLyricsMatcher.findAudioFile(
            "不再犹豫", listOf("Beyond"), audioIndex,
        )
        assertEquals("Beyond - 不再犹豫.mp3", audioFile?.file?.fileName)
        val sameStem = LocalLyricsMatcher.sameStemLrc(audioFile!!, lrcIndex)
        assertEquals("Beyond - 不再犹豫.lrc", sameStem?.file?.fileName)
        assertTrue(
            LocalLyricsMatcher.lrcTitleMatches("Beyond - 不再犹豫.lrc", "不再犹豫"),
        )

        // Provider step 2 (the metadata fallback) would find it too:
        // it ranks first, ahead of the "(Live)" title-only decoy.
        val matched = LocalLyricsMatcher.match("不再犹豫", listOf("Beyond"), lrcIndex)
        assertEquals("Beyond - 不再犹豫.lrc", matched.firstOrNull()?.file?.fileName)
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
