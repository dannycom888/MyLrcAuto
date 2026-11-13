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

    // ---- v1.0.24: script-insensitive normalize -------------------
    //
    // The v1.0.23 device trace (index Ready, 1,092 + 1,092, dropped
    // 0, probe 0/0, NO_MATCH for "不再犹豫" — files the file manager
    // shows as "Beyond - 不再犹豫.lrc/.mp3") narrowed the miss to a
    // codepoint-level difference between the reported title and the
    // stored names. The tests below pin the prime suspect class:
    // Traditional vs Simplified variants on either side.

    @Test
    fun normalizeFoldsTraditionalToSimplified() {
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("不再猶豫"))
        assertEquals("邓丽君", LocalLyricsMatcher.normalize("鄧麗君"))
        assertEquals("这个梦", LocalLyricsMatcher.normalize("這個夢"))
        assertEquals("喜欢你", LocalLyricsMatcher.normalize("喜歡你"))
        // Simplified input is unchanged by the folding.
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("不再犹豫"))
        assertTrue(LocalLyricsMatcher.traditionalFoldPairCount() > 400)
    }

    @Test
    fun normalizeStripsZeroWidthCharacters() {
        // ZWSP inside the title, a BOM at the head, ZWNJ/ZWJ between
        // characters: all invisible, all fatal to an exact compare.
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("不再\u200B犹豫"))
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("\uFEFF不再犹豫"))
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("不\u200C再\u200D犹豫"))
        assertEquals("beyond", LocalLyricsMatcher.normalize("Bey\u200Bond"))
    }

    @Test
    fun traditionalFileNamesMatchSimplifiedTag() {
        // The v1.0.23 trace shape, with the Traditional variant as
        // the codepoint difference: on v1.0.23 code this lookup
        // NO_MATCHed (probe 0/0, audio found NO, fallback 0).
        val lrcIndex = indexedLrc("Beyond - 不再猶豫.lrc", "Beyond - 喜歡你.lrc")
        val audioIndex = indexedAudio("Beyond - 不再猶豫.mp3")

        val audioFile = LocalLyricsMatcher.findAudioFile(
            "不再犹豫", listOf("Beyond"), audioIndex,
        )
        assertEquals("Beyond - 不再猶豫.mp3", audioFile?.file?.fileName)
        val sameStem = LocalLyricsMatcher.sameStemLrc(audioFile!!, lrcIndex)
        assertEquals("Beyond - 不再猶豫.lrc", sameStem?.file?.fileName)
        assertTrue(
            LocalLyricsMatcher.lrcTitleMatches("Beyond - 不再猶豫.lrc", "不再犹豫"),
        )
        val matched = LocalLyricsMatcher.match("不再犹豫", listOf("Beyond"), lrcIndex)
        assertEquals("Beyond - 不再猶豫.lrc", matched.firstOrNull()?.file?.fileName)
    }

    @Test
    fun simplifiedFileNamesMatchTraditionalTag() {
        // The mirror image: files Simplified, the player's tag
        // Traditional — folding is on BOTH sides, so this hits too.
        val lrcIndex = indexedLrc("Beyond - 不再犹豫.lrc")
        val audioIndex = indexedAudio("Beyond - 不再犹豫.mp3")

        val audioFile = LocalLyricsMatcher.findAudioFile(
            "不再猶豫", listOf("Beyond"), audioIndex,
        )
        assertEquals("Beyond - 不再犹豫.mp3", audioFile?.file?.fileName)
        val sameStem = LocalLyricsMatcher.sameStemLrc(audioFile!!, lrcIndex)
        assertEquals("Beyond - 不再犹豫.lrc", sameStem?.file?.fileName)
        assertTrue(
            LocalLyricsMatcher.lrcTitleMatches("Beyond - 不再犹豫.lrc", "不再猶豫"),
        )
        val matched = LocalLyricsMatcher.match("不再猶豫", listOf("Beyond"), lrcIndex)
        assertEquals("Beyond - 不再犹豫.lrc", matched.firstOrNull()?.file?.fileName)
    }

    @Test
    fun traditionalArtistNameMatchesSimplifiedTag() {
        // The artist side folds too: 鄧麗君's file, 邓丽君's tag.
        val matched = LocalLyricsMatcher.match(
            "月亮代表我的心", listOf("邓丽君"),
            indexedLrc("鄧麗君 - 月亮代表我的心.lrc"),
        )
        assertEquals("鄧麗君 - 月亮代表我的心.lrc", matched.firstOrNull()?.file?.fileName)
        // And the reverse direction.
        val reverse = LocalLyricsMatcher.match(
            "月亮代表我的心", listOf("鄧麗君"),
            indexedLrc("邓丽君 - 月亮代表我的心.lrc"),
        )
        assertEquals("邓丽君 - 月亮代表我的心.lrc", reverse.firstOrNull()?.file?.fileName)
    }

    @Test
    fun fullWidthTraditionalFileNameMatchesSimplifiedTag() {
        // Full-width separator AND Traditional artist characters in
        // the file name; the tag is half-width Simplified.
        val matched = LocalLyricsMatcher.match(
            "晴天", listOf("周杰伦"),
            indexedLrc("周杰倫－晴天.lrc"),
        )
        assertEquals("周杰倫－晴天.lrc", matched.firstOrNull()?.file?.fileName)
        // Full-width Latin in the file name folds as before.
        assertEquals(
            "abc123",
            LocalLyricsMatcher.normalize("ＡＢＣ-123"),
        )
    }

    @Test
    fun zeroWidthInTagTitleStillMatches() {
        // The player reports the title with an embedded zero-width
        // space; the files are clean. After stripping, both lookup
        // steps hit exactly as if the character were never there.
        val audioFile = LocalLyricsMatcher.findAudioFile(
            "不再\u200B犹豫", listOf("Beyond"),
            indexedAudio("Beyond - 不再犹豫.mp3"),
        )
        assertEquals("Beyond - 不再犹豫.mp3", audioFile?.file?.fileName)
        val matched = LocalLyricsMatcher.match(
            "不再\u200B犹豫", listOf("Beyond"),
            indexedLrc("Beyond - 不再犹豫.lrc"),
        )
        assertEquals("Beyond - 不再犹豫.lrc", matched.firstOrNull()?.file?.fileName)
    }

    @Test
    fun scriptFoldingKeepsExactEqualityProtection() {
        // The 爱/爱你 protection is an EQUALITY on folded forms, so
        // folding cannot widen it into a substring match — in
        // either script, in either direction.
        assertTrue(
            LocalLyricsMatcher.match(
                "愛", listOf("周杰伦"),
                indexedLrc("爱你.lrc"),
            ).isEmpty(),
        )
        assertTrue(
            LocalLyricsMatcher.match(
                "爱", listOf("周杰伦"),
                indexedLrc("愛你.lrc"),
            ).isEmpty(),
        )
        assertFalse(LocalLyricsMatcher.lrcTitleMatches("愛你.lrc", "爱"))
        // The positive case folds cleanly: 愛你 the tag, 爱你 the file.
        val matched = LocalLyricsMatcher.match(
            "愛你", listOf("王心凌"),
            indexedLrc("王心凌 - 爱你.lrc"),
        )
        assertEquals("王心凌 - 爱你.lrc", matched.firstOrNull()?.file?.fileName)
    }

    @Test
    fun levenshteinAndFuzzyBoundUnits() {
        assertEquals(0, LocalLyricsMatcher.levenshtein("不再犹豫", "不再犹豫"))
        assertEquals(1, LocalLyricsMatcher.levenshtein("不再优豫", "不再犹豫"))
        assertEquals(1, LocalLyricsMatcher.levenshtein("abc", "ab"))
        assertEquals(2, LocalLyricsMatcher.levenshtein("", "ab"))
        assertEquals(3, LocalLyricsMatcher.levenshtein("kitten", "sitting"))
        // The bound: one wrong character up to four characters,
        // two from five on (v1.0.26 widened the two-typo tier
        // from "longer than six" down to five-or-more — the pin
        // for length 6 changed from 1 to 2 accordingly).
        assertEquals(1, LocalLyricsMatcher.fuzzyMaxDistance(1))
        assertEquals(1, LocalLyricsMatcher.fuzzyMaxDistance(4))
        assertEquals(2, LocalLyricsMatcher.fuzzyMaxDistance(5))
        assertEquals(2, LocalLyricsMatcher.fuzzyMaxDistance(6))
        assertEquals(2, LocalLyricsMatcher.fuzzyMaxDistance(7))
    }

    @Test
    fun findAudioFileFuzzyFindsCorrectlyNamedFileForTypoTag() {
        // The v1.0.24 device trace, verbatim (2026-11-13 00:18:34):
        // Poweramp reports the title as 不再优豫 — codepoints
        // 4E0D 518D 4F18 8C6B, 优 for 犹, a homophone TYPO in the
        // MP3's tag — while the files are named correctly. Every
        // containment pass misses (the stem cannot contain a title
        // that is one character off); only the fuzzy phase can
        // pick the file, and it records itself as fuzzy.
        val files = indexedAudio(
            "Beyond - 不再犹豫.mp3",
            "Beyond - 喜欢你.mp3",
        )
        val detailed = LocalLyricsMatcher.findAudioFileDetailed(
            "不再优豫", listOf("Beyond"), files,
        )
        assertEquals("Beyond - 不再犹豫.mp3", detailed?.entry?.file?.fileName)
        assertEquals(true, detailed?.fuzzyMatch)
        assertEquals(false, detailed?.titleOnlyRetry)
        assertEquals(
            "Beyond - 不再犹豫.mp3",
            LocalLyricsMatcher.findAudioFile("不再优豫", listOf("Beyond"), files)
                ?.file?.fileName,
        )
    }

    @Test
    fun matchFuzzyFindsCorrectlyNamedLrcForTypoTag() {
        // The same device case through the name fallback: the key
        // ranking and the exact title-only phase are both RIGHT to
        // miss a mistyped title; the fuzzy phase returns the one
        // file a single substitution away.
        val index = indexedLrc(
            "Beyond - 不再犹豫.lrc",
            "Beyond - 喜欢你.lrc",
        )
        val result = LocalLyricsMatcher.matchDetailed("不再优豫", listOf("Beyond"), index)
        assertEquals(
            listOf("Beyond - 不再犹豫.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
        assertFalse(result.titleOnlyFallback)
        assertEquals(
            "Beyond - 不再犹豫.lrc",
            LocalLyricsMatcher.match("不再优豫", listOf("Beyond"), index)
                .firstOrNull()?.file?.fileName,
        )
    }

    @Test
    fun fuzzyNeverMatchesDifferentLengthTitles() {
        // The 爱/爱你 protection, pinned for the fuzzy phase in
        // both directions: a length difference is an insertion or
        // deletion and is NEVER accepted — so a one-character
        // title cannot fuzzy into a two-character one, nor back.
        val shortTag = LocalLyricsMatcher.matchDetailed(
            "爱", listOf("王心凌"),
            indexedLrc("王心凌 - 爱你.lrc"),
        )
        assertTrue(shortTag.files.isEmpty())
        assertFalse(shortTag.fuzzyMatch)
        val longTag = LocalLyricsMatcher.matchDetailed(
            "爱你", listOf("王心凌"),
            indexedLrc("王心凌 - 爱.lrc"),
        )
        assertTrue(longTag.files.isEmpty())
        assertFalse(longTag.fuzzyMatch)
        // The audio side, both directions: containment cannot
        // rescue these shapes either (the shorter stem does not
        // contain the longer title, and the artist-carrying file
        // for the shorter title is only reachable through fuzzy).
        assertNull(
            LocalLyricsMatcher.findAudioFileDetailed(
                "爱你", listOf("王心凌"),
                indexedAudio("王心凌 - 爱.mp3"),
            ),
        )
        assertNull(
            LocalLyricsMatcher.findAudioFileDetailed(
                "爱你", listOf("王心凌"),
                indexedAudio("爱.mp3"),
            ),
        )
    }

    @Test
    fun fuzzyTieAtMinimumDistanceMatchesNothing() {
        // Two files exactly one substitution from the mistyped
        // tag, both naming the artist: guessing between two
        // near-miss songs is worse than showing none.
        val result = LocalLyricsMatcher.matchDetailed(
            "不再优豫", listOf("Beyond"),
            indexedLrc("Beyond - 不再犹豫.lrc", "Beyond - 不再优虑.lrc"),
        )
        assertTrue(result.files.isEmpty())
        assertFalse(result.fuzzyMatch)
        assertNull(
            LocalLyricsMatcher.findAudioFileDetailed(
                "不再优豫", listOf("Beyond"),
                indexedAudio("Beyond - 不再犹豫.mp3", "Beyond - 不再优虑.mp3"),
            ),
        )
    }

    @Test
    fun fuzzyAcceptsTwoTyposOnlyInLongTitles() {
        // Eight characters, two of them wrong in the tag
        // (梅 for 玫, 归 for 瑰): inside the >6 bound of 2.
        val result = LocalLyricsMatcher.matchDetailed(
            "九百九十九朵梅归", listOf("邰正宵"),
            indexedLrc("邰正宵 - 九百九十九朵玫瑰.lrc"),
        )
        assertEquals(
            listOf("邰正宵 - 九百九十九朵玫瑰.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
        // The same two-substitution distance in a FOUR-character
        // title is over the bound of 1 — a different song, not a
        // typo: 不再忧虑 is two substitutions from 不再优豫.
        assertTrue(
            LocalLyricsMatcher.match(
                "不再优豫", listOf("Beyond"),
                indexedLrc("Beyond - 不再忧虑.lrc"),
            ).isEmpty(),
        )
    }

    @Test
    fun fuzzyRejectsWrongArtistNearTitle() {
        // The title is one substitution away but the file names a
        // DIFFERENT artist: the gate rejects it outright. (The
        // exact title-only phase cannot rescue it either — the
        // tag's typo makes the title parts unequal.)
        val result = LocalLyricsMatcher.matchDetailed(
            "不再优豫", listOf("Beyond"),
            indexedLrc("周杰伦 - 不再犹豫.lrc"),
        )
        assertTrue(result.files.isEmpty())
        assertFalse(result.fuzzyMatch)
        assertNull(
            LocalLyricsMatcher.findAudioFileDetailed(
                "不再优豫", listOf("Beyond"),
                indexedAudio("周杰伦 - 不再犹豫.mp3"),
            ),
        )
    }

    @Test
    fun fuzzyArtistTierOutranksCloserBareTitleFile() {
        // Tier before distance: the artist-matching file is TWO
        // substitutions away, the bare title-only file only ONE —
        // the artist file still wins, because a file that names
        // the track's artist is the stronger claim.
        val result = LocalLyricsMatcher.matchDetailed(
            "九百九十九朵梅归", listOf("邰正宵"),
            indexedLrc(
                "九百九十九朵梅瑰.lrc",
                "邰正宵 - 九百九十九朵玫瑰.lrc",
            ),
        )
        assertEquals(
            listOf("邰正宵 - 九百九十九朵玫瑰.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
    }

    @Test
    fun fuzzyWorksWithoutReportedArtist() {
        // No artist from the player: no gate, one tier — the
        // near-title file still wins on its own.
        val result = LocalLyricsMatcher.matchDetailed(
            "不再优豫", emptyList(),
            indexedLrc("Beyond - 不再犹豫.lrc"),
        )
        assertEquals(
            listOf("Beyond - 不再犹豫.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
    }

    @Test
    fun fuzzyMatchesBracketedTitleVariant() {
        // The file's title part carries a "(Live)" suffix: its
        // bracket-stripped variant is the one a substitution from
        // the tag, and that is enough.
        val result = LocalLyricsMatcher.matchDetailed(
            "不再优豫", listOf("Beyond"),
            indexedLrc("Beyond - 不再犹豫 (Live).lrc"),
        )
        assertEquals(
            listOf("Beyond - 不再犹豫 (Live).lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
    }

    @Test
    fun fuzzyAcceptsTwoTyposInSixCharTitle() {
        // v1.0.26, the 因为爱所以爱 shape: a SIX-character title
        // two substitutions from the stored name's title part
        // （爱→情 and the final 爱→受）. Under the v1.0.25 bound
        // (1 at length 6) this missed; the widened bound (2 from
        // length 5) accepts it — on the name side and the audio
        // side alike.
        val result = LocalLyricsMatcher.matchDetailed(
            "因为爱所以爱", listOf("谢霆锋"),
            indexedLrc("谢霆锋 - 因为情所以受.lrc"),
        )
        assertEquals(
            listOf("谢霆锋 - 因为情所以受.lrc"),
            result.files.map { it.file.fileName },
        )
        assertTrue(result.fuzzyMatch)
        val audio = LocalLyricsMatcher.findAudioFileDetailed(
            "因为爱所以爱", listOf("谢霆锋"),
            indexedAudio("谢霆锋 - 因为情所以受.mp3"),
        )
        assertEquals("谢霆锋 - 因为情所以受.mp3", audio?.entry?.file?.fileName)
        assertEquals(true, audio?.fuzzyMatch)
    }

    @Test
    fun fuzzyRejectsTwoTyposInFourCharTitle() {
        // The other half of the widened bound: a FOUR-character
        // title still allows only ONE wrong character, so two
        // substitutions （阔→润， 空→宫） stay a miss.
        val result = LocalLyricsMatcher.matchDetailed(
            "海阔天空", listOf("Beyond"),
            indexedLrc("Beyond - 海润天宫.lrc"),
        )
        assertTrue(result.files.isEmpty())
        assertFalse(result.fuzzyMatch)
        assertNull(
            LocalLyricsMatcher.findAudioFileDetailed(
                "海阔天空", listOf("Beyond"),
                indexedAudio("Beyond - 海润天宫.mp3"),
            ),
        )
    }

    @Test
    fun compatibilityIdeographFileNamesMatchPlainTag() {
        // v1.0.26: the file names encode 不 as the CJK
        // COMPATIBILITY IDEOGRAPH U+F967 — the same glyph as
        // U+4E0D, twice-encoded. It is neither Traditional nor
        // Simplified, so the 477-pair table folds it to itself
        // and (pre-NFKC) normalize() keeps it as a different
        // letter: the raw name does not even CONTAIN the plain
        // title (the raw-probe 0/0 shape), and every exact phase
        // missed. NFKC at the start of normalize() folds U+F967
        // to U+4E0D, so the same lookups now hit exactly.
        val lrcName = "Beyond - \uF967再犹豫.lrc"
        val audioName = "Beyond - \uF967再犹豫.mp3"
        assertFalse(lrcName.contains("不再犹豫"))
        assertEquals("不再犹豫", LocalLyricsMatcher.normalize("\uF967再犹豫"))
        val lrcIndex = indexedLrc(lrcName)
        val audioIndex = indexedAudio(audioName)

        val audioFile = LocalLyricsMatcher.findAudioFile(
            "不再犹豫", listOf("Beyond"), audioIndex,
        )
        assertEquals(audioName, audioFile?.file?.fileName)
        val sameStem = LocalLyricsMatcher.sameStemLrc(audioFile!!, lrcIndex)
        assertEquals(lrcName, sameStem?.file?.fileName)
        assertTrue(LocalLyricsMatcher.lrcTitleMatches(lrcName, "不再犹豫"))
        val matched = LocalLyricsMatcher.match("不再犹豫", listOf("Beyond"), lrcIndex)
        assertEquals(lrcName, matched.firstOrNull()?.file?.fileName)
    }

    @Test
    fun exactPhasesDoNotReportFuzzy() {
        // An ordinary exact title-only fallback (v1.0.23 shape)
        // keeps its own flag and does not claim the fuzzy step.
        val result = LocalLyricsMatcher.matchDetailed(
            "晴天", listOf("周杰伦"),
            indexedLrc("林俊杰 - 晴天.lrc"),
        )
        assertTrue(result.titleOnlyFallback)
        assertFalse(result.fuzzyMatch)
    }
}
