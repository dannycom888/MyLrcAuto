package com.dannycom888.mylrcauto.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Pure filename matching for local `.lrc` files.
 *
 * Ported from the local-only Melisma build's `LocalFolderMatcher` (same
 * rules, adapted to this app's file types): file names are matched against
 * the playing track with two preference tiers:
 * 1. `"Artist - Title.lrc"` (the artist part must be one of the track's artists).
 * 2. `"Title.lrc"`.
 *
 * Matching is done on a normalized form: full-width characters are folded to
 * half-width, the text is lower-cased, and every character that is not a letter
 * or digit is dropped, so `"周杰伦 - 晴天.lrc"`, `"周杰伦-晴天.lrc"` and
 * `"周杰伦 晴天.lrc"` all match the same track. Parenthesised or bracketed
 * version suffixes in the file name (for example `"晴天 (Live).lrc"`) are also
 * tried stripped, so they still match the plain title.
 *
 * Multi-artist credits are split on both sides: a file named
 * `"庄心妍_祁隆 - 一万个舍不得.lrc"` matches a track whose only reported
 * artist is `"庄心妍"` — and vice versa, a track credited to
 * `"庄心妍_祁隆"` matches `"庄心妍 - 一万个舍不得.lrc"`. The artist part is
 * split on `_`, `、`, `&`, `,`, `+`, `/`, `feat.`/`ft.`, and the Chinese
 * `与`/`和`; the title part must still equal the track title after
 * normalization. The key tiers stay strictly ordered — a file that names
 * the track's artist always wins — but since v1.0.23 the title-part tier
 * is a fallback for ANY empty key ranking, not only for artist-less
 * tracks: an exact title-part match (never a substring) is accepted when
 * nothing matched on the keys, so one wrong artist token from the player
 * cannot zero the whole lookup.
 *
 * This object is pure Kotlin with no Android dependency so the rules are
 * directly unit-testable on the JVM.
 */
object LocalLyricsMatcher {

    /** One indexed `.lrc` file with its precomputed match keys. */
    data class IndexedLrcFile(
        val file: LocalLrcFile,
        val keys: Set<String>,
    )

    /** One indexed audio file with its precomputed normalized stem. */
    data class IndexedAudioFile(
        val file: LocalAudioFile,
        val normalizedStem: String,
    )

    /**
     * Folds full-width ASCII and the full-width space to half-width. This is
     * the first step of [normalize] and is also used before splitting file
     * names, so full-width separators (for example `－` or `＆`) behave like
     * their half-width forms.
     */
    fun foldFullWidth(value: String): String {
        val folded = StringBuilder(value.length)
        for (char in value) {
            folded.append(
                when {
                    char == '\u3000' -> ' '
                    char in '\uFF01'..'\uFF5E' -> (char.code - 0xFEE0).toChar()
                    else -> char
                },
            )
        }
        return folded.toString()
    }

    /**
     * Normalizes one string for comparison: full-width characters are folded
     * to half-width, the text is lower-cased, and everything that is not a
     * letter or digit is removed.
     */
    fun normalize(value: String): String {
        val folded = StringBuilder(value.length)
        for (char in foldFullWidth(value)) {
            if (char.isLetterOrDigit()) {
                folded.append(char.lowercaseChar())
            }
        }
        return folded.toString()
    }

    /**
     * Separators between individual artists in one credit. `feat.`/`ft.` only
     * split when they are not part of another word, so an artist such as
     * "Taylor Swift" is not cut at the "ft" in "Swift".
     */
    private val ARTIST_SEPARATORS = Regex(
        """(?i)(?<![A-Za-z])(?:feat\.?|ft\.?)(?![A-Za-z])|[_、&,，+/与和]""",
    )

    /**
     * Splits one artist credit (a file-name artist part or a track artist)
     * into individual artists. Empty segments are dropped.
     */
    fun splitArtists(value: String): List<String> {
        return foldFullWidth(value)
            .split(ARTIST_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalize(it).isNotEmpty() }
    }

    /**
     * Splits a file-name stem into its artist part and title part on the
     * first `" - "` (a bare `"-"` without spaces is also accepted). Returns
     * null when the stem has no usable separator. The spaced separator is
     * tried first so a hyphenated artist name such as `"TF-Boys"` is not
     * cut apart.
     */
    fun splitArtistTitle(stem: String): Pair<String, String>? {
        val folded = foldFullWidth(stem)
        val spaced = Regex("\\s+-\\s+").find(folded)
        if (spaced != null) {
            val artist = folded.substring(0, spaced.range.first).trim()
            val title = folded.substring(spaced.range.last + 1).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
            return null
        }
        val bare = folded.indexOf('-')
        if (bare >= 0) {
            val artist = folded.substring(0, bare).trim()
            val title = folded.substring(bare + 1).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
        }
        return null
    }

    /** Removes `(...)` and `[...]` segments, used for version suffixes such as `(Live)`. */
    fun stripBracketed(value: String): String {
        return value
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
    }

    /**
     * Match keys for one `.lrc` file name, without the extension.
     *
     * Besides the normalized whole stem (and its bracket-stripped form), a
     * file with an artist part contributes one `"Artist - Title"` key per
     * individual artist in the credit, so `"庄心妍_祁隆 - 一万个舍不得.lrc"`
     * also matches a track credited only to `"庄心妍"`. A file with no
     * artist part contributes only its title keys.
     */
    fun fileKeys(fileName: String): Set<String> {
        val baseName = fileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val keys = mutableSetOf(normalize(baseName))
        val stripped = normalize(stripBracketed(baseName))
        if (stripped.isNotEmpty()) keys += stripped
        val (artistPart, titlePart) = splitArtistTitle(baseName) ?: return keys
        val titleVariants = linkedSetOf(titlePart, stripBracketed(titlePart))
            .filter { normalize(it).isNotEmpty() }
        for (segment in splitArtists(artistPart)) {
            for (variant in titleVariants) {
                val key = normalize("$segment - $variant")
                if (key.isNotEmpty()) keys += key
            }
        }
        return keys
    }

    /**
     * Ordered match keys for a track: `"Artist - Title"` for each artist (the
     * raw credit first, then each individual artist in a multi-artist credit)
     * first, then the bare title. Earlier keys are preferred over later ones.
     */
    fun trackKeys(title: String, artists: List<String>): List<String> {
        val keys = linkedSetOf<String>()
        for (artist in artists) {
            keys += artistTitleKeys(artist, title)
        }
        keys += normalize(title)
        return keys.toList()
    }

    /**
     * `"Artist - Title"` keys for one artist: the raw credit first, then each
     * individual artist in the credit.
     */
    fun artistTitleKeys(artist: String, title: String): List<String> {
        val keys = linkedSetOf<String>()
        keys += normalize("$artist - $title")
        for (segment in splitArtists(artist)) {
            keys += normalize("$segment - $title")
        }
        return keys.toList()
    }

    /**
     * Number of artist-tier keys in [trackKeys]: every key except the trailing
     * bare-title key. Used to tell whether a match is an artist-title match.
     */
    fun artistKeyCount(title: String, artists: List<String>): Int {
        val seen = linkedSetOf<String>()
        var count = 0
        for (artist in artists) {
            for (key in artistTitleKeys(artist, title)) {
                if (seen.add(key)) count++
            }
        }
        return count
    }

    /** Normalized stem of a file name: the name without its extension, normalized. */
    fun fileStem(fileName: String): String {
        return normalize(fileName.substringBeforeLast('.', fileName))
    }

    /**
     * True when the `.lrc` file name has exactly the audio file's stem — the
     * pairing rule Poweramp uses. Compares file names only, so it is usable
     * from either side of the match.
     */
    fun sameStemLrc(audioFileName: String, lrcFileName: String): Boolean {
        return fileStem(audioFileName) == fileStem(lrcFileName)
    }

    /**
     * A [findAudioFileDetailed] result: the file that was picked and
     * whether it came from the title-only retry (see there).
     */
    data class AudioFileMatch(
        val entry: IndexedAudioFile,
        /** True when the title+artist pass found nothing and the title-only retry picked this file. */
        val titleOnlyRetry: Boolean,
    )

    /**
     * Finds the audio file that is playing: its normalized stem must contain
     * the normalized track title and at least one normalized artist segment
     * (an individual artist from a multi-artist credit). When the track
     * reports no artist at all, the title alone has to do — the pairing rule
     * still applies through the same-stem `.lrc` lookup, and the residue
     * tie-break still prefers the least extra text. When several files
     * qualify, the one with the least extra text besides the title and the
     * matched artist wins; ties keep index order.
     */
    fun findAudioFile(
        title: String,
        artists: List<String>,
        audioFiles: List<IndexedAudioFile>,
    ): IndexedAudioFile? = findAudioFileDetailed(title, artists, audioFiles)?.entry

    /**
     * [findAudioFile] with the pass that produced the hit recorded, for
     * the lookup trace.
     *
     * v1.0.23 relaxation: when the track reports artists but NO stem
     * contains one of them alongside the title, the search retries on
     * the title alone (residue-ranked the same way). The artist pass
     * stays strictly first, so a file that names the artist always
     * beats a title-only file; the retry only fires when the artist
     * pass came back empty — the shape the 2026-11-12 device traces
     * showed (audio found: NO for files whose stems carry the title).
     * The pick stays safe for the step that consumes it: the same-stem
     * `.lrc` still has to pass the exact title check
     * ([lrcTitleMatches]) before anything is shown.
     */
    fun findAudioFileDetailed(
        title: String,
        artists: List<String>,
        audioFiles: List<IndexedAudioFile>,
    ): AudioFileMatch? {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return null
        val artistSegments = artists
            .flatMap { splitArtists(it) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val primary = bestAudioMatch(
            normalizedTitle = normalizedTitle,
            artistSegments = artistSegments,
            audioFiles = audioFiles,
            requireArtist = artistSegments.isNotEmpty(),
        )
        if (primary != null) return AudioFileMatch(primary, titleOnlyRetry = false)
        if (artistSegments.isEmpty()) return null
        val retry = bestAudioMatch(
            normalizedTitle = normalizedTitle,
            artistSegments = artistSegments,
            audioFiles = audioFiles,
            requireArtist = false,
        )
        return retry?.let { AudioFileMatch(it, titleOnlyRetry = true) }
    }

    /**
     * The residue-ranked containment search behind [findAudioFile]:
     * stems containing the title (and, when [requireArtist], one of
     * the artist segments), least extra text first.
     */
    private fun bestAudioMatch(
        normalizedTitle: String,
        artistSegments: Set<String>,
        audioFiles: List<IndexedAudioFile>,
        requireArtist: Boolean,
    ): IndexedAudioFile? {
        return audioFiles
            .mapNotNull { entry ->
                val stem = entry.normalizedStem
                if (!stem.contains(normalizedTitle)) return@mapNotNull null
                val segment = if (!requireArtist) "" else
                    artistSegments.firstOrNull { stem.contains(it) }
                        ?: return@mapNotNull null
                val residue = (stem.length - normalizedTitle.length - segment.length).coerceAtLeast(0)
                residue to entry
            }
            .minByOrNull { it.first }
            ?.second
    }

    /**
     * True when the `.lrc` file's title part is the track's title — the
     * guard for the audio-anchored lookup ([LocalLyricsProvider] step 1).
     * [findAudioFile] matches the *audio* file by substring, so it can
     * pick a longer file ("爱你.mp3") for a shorter title ("爱"); the
     * same-stem `.lrc` then has the wrong title too, and returning it
     * would show the wrong song's lyrics. This check compares the title
     * parts after normalization with bracketed suffixes stripped, so
     * "晴天 (Live).lrc" still matches "晴天" while "爱你.lrc" does not
     * match "爱". A file with an artist part contributes only its title
     * part; a file with no separator is compared whole.
     */
    fun lrcTitleMatches(lrcFileName: String, title: String): Boolean {
        val stem = lrcFileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val titlePart = splitArtistTitle(stem)?.second ?: stem
        return normalize(stripBracketed(titlePart)) == normalize(stripBracketed(title))
    }

    /**
     * The `.lrc` file whose stem is exactly the audio file's stem — the
     * pairing rule Poweramp uses. Returns null when no indexed `.lrc` has
     * that stem.
     */
    fun sameStemLrc(
        audioFile: IndexedAudioFile,
        lrcIndex: List<IndexedLrcFile>,
    ): IndexedLrcFile? {
        return lrcIndex.firstOrNull { entry ->
            fileStem(entry.file.fileName) == audioFile.normalizedStem
        }
    }

    /**
     * A [matchDetailed] result: the matched files, and whether they
     * came from the title-only fallback phase rather than the key
     * ranking.
     */
    data class NameMatchResult(
        val files: List<IndexedLrcFile>,
        /** True when the key ranking matched nothing and the title-only phase produced these files. */
        val titleOnlyFallback: Boolean,
    )

    /**
     * Returns the indexed files that match the track, ordered by preference:
     * files matching an earlier track key come first; files matching the same
     * key keep their index order.
     *
     * When the key ranking matches nothing, a title-only phase runs:
     * files whose TITLE PART is exactly the track title (normalized,
     * bracketed suffixes stripped — exact equality, never a substring,
     * so "爱" still does not match "爱你.lrc"). Until v1.0.23 that
     * phase only ran when the track reported no artist at all, which
     * made the fallback all-or-nothing on the artist string: one
     * wrong or extra artist token from the player zeroed it even when
     * an exact-title file was indexed. It now runs whenever the key
     * ranking is empty — the ranking stays strictly first, so a file
     * that names the track's artist always beats a title-only file.
     */
    fun match(
        title: String,
        artists: List<String>,
        indexed: List<IndexedLrcFile>,
    ): List<IndexedLrcFile> = matchDetailed(title, artists, indexed).files

    /** [match] with the producing phase recorded, for the lookup trace. */
    fun matchDetailed(
        title: String,
        artists: List<String>,
        indexed: List<IndexedLrcFile>,
    ): NameMatchResult {
        val keys = trackKeys(title, artists)
        val ranked = indexed.mapNotNull { entry ->
            val bestKeyRank = keys.indexOfFirst { key -> key in entry.keys }
            if (bestKeyRank < 0) null else bestKeyRank to entry
        }.sortedBy { it.first }.map { it.second }
        if (ranked.isNotEmpty()) return NameMatchResult(ranked, titleOnlyFallback = false)
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return NameMatchResult(emptyList(), titleOnlyFallback = false)
        val byTitlePart = indexed.filter { entry -> titlePartMatches(entry, normalizedTitle) }
        return NameMatchResult(byTitlePart, titleOnlyFallback = byTitlePart.isNotEmpty())
    }

    /**
     * True when the file's title part is exactly [normalizedTitle]
     * (the caller passes the already-normalized track title), with
     * bracketed version suffixes stripped — the exact-equality check
     * behind the title-only phase of [match]. A file with no
     * artist/title separator never qualifies here: its whole stem is
     * its title, and the key ranking already tried that.
     */
    private fun titlePartMatches(entry: IndexedLrcFile, normalizedTitle: String): Boolean {
        val stem = entry.file.fileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val (_, titlePart) = splitArtistTitle(stem) ?: return false
        return normalize(titlePart) == normalizedTitle ||
            normalize(stripBracketed(titlePart)) == normalizedTitle
    }

    /**
     * Picks the winner among matched files that were parsed successfully.
     *
     * Files matching an earlier track key win over files matching a later key
     * ("Artist - Title" beats title-only). Within the same key tier, when the
     * track duration is known, the file whose last lyric timestamp is closest
     * to the track duration wins; when the duration is unknown, the first file
     * in match order wins.
     *
     * @param parsed each entry is a matched file with its parsed last-line
     *   timestamp in milliseconds (null when the file has no timed lines).
     */
    fun pickWinner(
        title: String,
        artists: List<String>,
        durationMs: Long,
        parsed: List<Pair<IndexedLrcFile, Long?>>,
    ): IndexedLrcFile? {
        if (parsed.isEmpty()) return null
        val keys = trackKeys(title, artists)
        val bestRank = parsed.minOf { (file, _) -> keyRank(keys, file) }
        val tier = parsed.filter { (file, _) -> keyRank(keys, file) == bestRank }
        if (durationMs <= 0L) return tier.first().first
        return tier.minByOrNull { (_, lastLineMs) ->
            val last = lastLineMs ?: Long.MAX_VALUE
            if (last == Long.MAX_VALUE) Long.MAX_VALUE else kotlin.math.abs(last - durationMs)
        }?.first
    }

    /**
     * Where a file sits in the track's key order. A file that matches no key
     * — one that only reached the match through the no-artist title-part
     * fallback — ranks last, never first: without this, `-1` would beat a
     * genuine title-only match.
     */
    private fun keyRank(keys: List<String>, file: IndexedLrcFile): Int {
        val rank = keys.indexOfFirst { key -> key in file.keys }
        return if (rank < 0) Int.MAX_VALUE else rank
    }

    /**
     * Decodes `.lrc` bytes to text.
     *
     * Order: a UTF-16 byte-order mark first (FF FE → little-endian,
     * FE FF → big-endian) — a Windows editor's "Unicode" save is the
     * common source, and players that sniff the mark (Poweramp) show
     * such a file's lyrics fine. Then strict UTF-8, then GBK (common
     * for Chinese `.lrc` files saved on Windows). A leading UTF-8
     * byte-order mark is removed.
     *
     * The mark matters because a UTF-16 file run through the UTF-8 /
     * GBK path does not fail — it decodes into NUL-interleaved
     * garbage (every ASCII byte gains a NUL partner; NUL is a valid
     * byte in both decodings, so no exception ever routes to a
     * fallback), no time tag ever matches, and the file reads as
     * untimed. The same tell catches mark-less UTF-16: real `.lrc`
     * text never contains NUL, so a decode that has them is retried
     * as UTF-16 (little-endian first, the common save) and the clean
     * decode wins.
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return decodeUtf16(bytes, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return decodeUtf16(bytes, Charsets.UTF_16BE)
        }
        // Every strict attempt is failure-tolerant: a candidate that
        // does not decode cleanly is skipped, never thrown — the
        // first version of this chain let the GBK fallback itself
        // throw on mark-less UTF-16 bytes (malformed as GBK too),
        // which escaped as an exception instead of reaching the
        // UTF-16 retry below.
        val utf8Text = strictDecode(bytes, Charsets.UTF_8)
        if (utf8Text != null && utf8Text.indexOf('\u0000') < 0) {
            return utf8Text.removePrefix("\uFEFF")
        }
        val gbkText = strictDecode(bytes, Charset.forName("GBK"))
        if (gbkText != null && gbkText.indexOf('\u0000') < 0) {
            return gbkText.removePrefix("\uFEFF")
        }
        // A decode that "succeeded" but is full of NULs was UTF-16
        // without a mark (or bytes neither UTF-8 nor GBK fits). The
        // timestamps every .lrc carries are ASCII, so the GBK read
        // of UTF-16 bytes is NUL-riddled too and never wins above;
        // the clean UTF-16 retry does.
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val retried = strictDecode(bytes, charset) ?: continue
            if (retried.indexOf('\u0000') < 0) return retried.removePrefix("\uFEFF")
        }
        // Nothing decoded cleanly: a lossy UTF-8 read rather than an
        // exception — a corrupt file parses to no timed lines and is
        // simply not shown.
        return utf8Text?.removePrefix("\uFEFF") ?: String(bytes, Charsets.UTF_8)
    }

    /** Strictly decodes, or null when the bytes are not valid in [charset]. */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    /** Decodes UTF-16 bytes in the given endianness, dropping the decoded mark. */
    private fun decodeUtf16(bytes: ByteArray, charset: Charset): String =
        String(bytes, charset).removePrefix("\uFEFF")

    /**
     * Parses `.lrc` bytes into a [LyricsDocument], or null when the file has
     * no timed lines. Untimed files are not shown: a timed file that failed
     * to parse must not be shown raw either.
     */
    fun parseBytes(bytes: ByteArray, name: String): LyricsDocument? {
        val lines = LrcParser.parse(decodeBytes(bytes))
        if (lines.isEmpty()) return null
        return LyricsDocument(lines = lines, sourceName = name)
    }

    /**
     * Audio-anchored match: the `.lrc` whose stem is exactly the playing
     * audio file's stem. Returns null when there is no audio file or no
     * same-stem `.lrc`. The duration tie-break needs parsed files, so it
     * lives in the provider ([pickWinner]), not here.
     */
    fun matchLrc(
        lrcFiles: List<LocalLrcFile>,
        audioFile: LocalAudioFile?,
    ): LocalLrcFile? {
        if (audioFile == null) return null
        val indexedAudio = IndexedAudioFile(audioFile, fileStem(audioFile.fileName))
        val indexedLrc = lrcFiles.map { IndexedLrcFile(it, fileKeys(it.fileName)) }
        return sameStemLrc(indexedAudio, indexedLrc)?.file
    }

    /**
     * Metadata match: the best `.lrc` for a track by title and artists.
     * Returns the first file in match order (best key tier, index order);
     * the duration tie-break needs parsed files, so it lives in the provider
     * ([pickWinner]), not here.
     */
    fun matchLrc(
        lrcFiles: List<LocalLrcFile>,
        title: String,
        artists: List<String>,
        durationMs: Long,
    ): LocalLrcFile? {
        val indexed = lrcFiles.map { IndexedLrcFile(it, fileKeys(it.fileName)) }
        return match(title, artists, indexed).firstOrNull()?.file
    }
}
