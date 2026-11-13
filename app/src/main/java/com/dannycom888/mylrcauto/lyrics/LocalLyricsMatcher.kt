package com.dannycom888.mylrcauto.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

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
 * normalization. A file only reaches the bare-title tier when it has no
 * artist part at all — a file whose artist part names someone else does not
 * match on title alone — except when the track reports no artist at all:
 * then there is no artist to contradict, and a file whose title part is
 * this title matches too.
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
    ): IndexedAudioFile? {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return null
        val artistSegments = artists
            .flatMap { splitArtists(it) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        return audioFiles
            .mapNotNull { entry ->
                val stem = entry.normalizedStem
                if (!stem.contains(normalizedTitle)) return@mapNotNull null
                val segment = if (artistSegments.isEmpty()) "" else
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
     * Returns the indexed files that match the track, ordered by preference:
     * files matching an earlier track key come first; files matching the same
     * key keep their index order.
     *
     * When the track reports no artist at all and nothing matched on the
     * keys, files whose title part is this title match too — a file named
     * `"Artist - Title.lrc"` is no longer held to an artist nobody reported.
     * A known artist still rules those files out: with an artist to check
     * against, only its files match.
     */
    fun match(
        title: String,
        artists: List<String>,
        indexed: List<IndexedLrcFile>,
    ): List<IndexedLrcFile> {
        val keys = trackKeys(title, artists)
        val ranked = indexed.mapNotNull { entry ->
            val bestKeyRank = keys.indexOfFirst { key -> key in entry.keys }
            if (bestKeyRank < 0) null else bestKeyRank to entry
        }.sortedBy { it.first }.map { it.second }
        if (ranked.isNotEmpty() || artists.any { it.isNotBlank() }) return ranked
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return emptyList()
        return indexed.filter { entry ->
            val stem = entry.file.fileName.removeSuffix(".lrc").removeSuffix(".LRC")
            val (_, titlePart) = splitArtistTitle(stem) ?: return@filter false
            normalize(titlePart) == normalizedTitle ||
                normalize(stripBracketed(titlePart)) == normalizedTitle
        }
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
     * Decodes `.lrc` bytes as UTF-8 first; when the bytes are not valid UTF-8,
     * decodes them as GBK instead (common for Chinese `.lrc` files saved on
     * Windows). A leading byte-order mark is removed.
     */
    fun decodeBytes(bytes: ByteArray): String {
        val utf8 = Charset.forName("UTF-8")
        val decoded = try {
            utf8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            Charset.forName("GBK").newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }
        return decoded.removePrefix("\uFEFF")
    }

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
