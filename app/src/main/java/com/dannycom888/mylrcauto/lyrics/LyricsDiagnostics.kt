package com.dannycom888.mylrcauto.lyrics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lyrics lookup diagnostics — the phone Settings block that shows
 * WHY the last lookup came back empty (v1.0.22).
 *
 * Three phone-side misses (2026-11-12: "不再犹豫" / Beyond,
 * "爱你" / 王心凌, "九百九十九朵玫瑰" / 邰正宵) survived a
 * complete, healthy 1,092-file index and repeated rescans, and
 * every screenshot looked the same — the phone renders a missed
 * lookup ([LyricsState.None]) as a literally empty area and the
 * provider's failure reason was never shown anywhere. So three
 * rounds of diagnosis had to guess which of `fetch()`'s exit
 * paths the device was taking. This record ends the guessing:
 * every lookup stores its INPUTS (title/artist exactly as the
 * player reported them, source package) and its STEP-BY-STEP
 * outcome, and Settings shows the latest one — one screenshot
 * identifies the failing step.
 *
 * The types, the index-state derivation and the row formatting
 * here are pure and unit-tested; [LocalLyricsProvider] only
 * records into them, and the Settings composable only maps the
 * enum labels/values to string resources.
 */

/** Where the served file index stands. */
enum class LyricsIndexState {
    /** No lyrics folder picked. */
    NO_FOLDER,

    /** A folder walk (or background rebuild) is running, or none has completed yet. */
    BUILDING,

    /** An index is built and serving lookups. */
    READY,

    /** The folder is configured but the last build failed — nothing is being served. */
    UNREADABLE,
}

/**
 * Derives [LyricsIndexState] from the provider's raw facts.
 * Precedence: no folder wins outright; a walk in flight is
 * BUILDING even over an older index (its replacement is what is
 * landing); a held index is READY even when the last rebuild
 * failed (lookups are still being answered); an error with no
 * index is UNREADABLE; anything else (folder picked, nothing
 * attempted yet) is BUILDING.
 */
fun lyricsIndexState(
    configured: Boolean,
    walkInFlight: Boolean,
    lastError: Boolean,
    hasIndex: Boolean,
): LyricsIndexState = when {
    !configured -> LyricsIndexState.NO_FOLDER
    walkInFlight -> LyricsIndexState.BUILDING
    hasIndex -> LyricsIndexState.READY
    lastError -> LyricsIndexState.UNREADABLE
    else -> LyricsIndexState.BUILDING
}

/**
 * The index half of the diagnostics block: state plus the served
 * counts. The `.lrc` count alone could not explain the 2026-11-12
 * traces (index "ready", 1,092 .lrc — yet the audio-anchored step
 * found no audio file), so v1.0.23 also shows how many AUDIO files
 * the index holds and how many listing rows the last scan dropped
 * as unclassifiable (see [FolderWalk]): together they separate
 * "the entries never reached the index" from "the entries are
 * there but the keys miss".
 */
data class LyricsIndexDiag(
    val state: LyricsIndexState,
    /** `.lrc` files in the served index; null when no index is being served. */
    val lrcCount: Int?,
    /** Audio files in the served index; null when no index is being served. */
    val audioCount: Int? = null,
    /**
     * Rows the last folder walk could not classify and did not
     * index; null when the served index came from the disk cache
     * (the walk that built it is not this process's to report) or
     * no walk has run.
     */
    val droppedCount: Int? = null,
)

/**
 * Which step of the lookup produced the returned document — in
 * particular WHICH relaxation, when a relaxation did it (v1.0.23).
 * A miss has no producing step (null in the trace).
 */
enum class LookupMatchStep {
    /** Step 1: audio file found with title+artist, same-stem `.lrc` used. */
    AUDIO_WITH_ARTIST,

    /** Step 1: audio file found by the title-only retry, same-stem `.lrc` used. */
    AUDIO_TITLE_ONLY,

    /** Step 2: the key ranking (artist + title keys) matched. */
    NAME_WITH_ARTIST,

    /** Step 2: the key ranking matched nothing; the title-only phase matched. */
    NAME_TITLE_ONLY,

    /**
     * The v1.0.25 fuzzy last resort matched: every exact phase on
     * the producing step's side missed, and a same-length title
     * part within the Levenshtein bound won. Which side produced
     * it stays readable from the outcome (FOUND_SAME_STEM vs
     * FOUND_METADATA).
     */
    FUZZY,
}

/**
 * How many indexed file names contain the track title VERBATIM —
 * the raw probe (v1.0.23). No normalization, no key logic: the raw
 * reported title as a plain substring of the raw indexed file name,
 * counted separately for the `.lrc` list and the audio list.
 *
 * This is the discriminator the normalized diagnostics could not
 * provide: probe 0/0 against a "ready" index means the entries
 * never reached the served index (walk/cache), while a positive
 * probe with a NOT_FOUND outcome means the entries ARE there and
 * the match keys are what miss.
 */
data class RawTitleProbe(
    val lrcCount: Int,
    val audioCount: Int,
)

/** Computes [RawTitleProbe] for one lookup. An empty title probes as 0/0. */
fun rawTitleProbe(
    rawTitle: String,
    lrcFileNames: List<String>,
    audioFileNames: List<String>,
): RawTitleProbe {
    if (rawTitle.isEmpty()) return RawTitleProbe(lrcCount = 0, audioCount = 0)
    return RawTitleProbe(
        lrcCount = lrcFileNames.count { it.contains(rawTitle) },
        audioCount = audioFileNames.count { it.contains(rawTitle) },
    )
}

/** Which stored list one of the closest stored names came from (v1.0.24). */
enum class StoredNameKind {
    LRC,
    AUDIO,
}

/** One stored file name shown beside a missed lookup: the name verbatim, and its list. */
data class ClosestStoredName(
    val fileName: String,
    val kind: StoredNameKind,
)

/**
 * The received title as space-separated uppercase hex codepoints,
 * e.g. "4E0D 518D 72B9 8C6B" for 不再犹豫 (v1.0.24).
 *
 * The v1.0.23 trace narrowed the phone misses to a CODEPOINT-level
 * difference between the reported title and the stored file names:
 * the two strings render identically, so the Settings block showed
 * nothing to compare. The codepoints make the invisible visible —
 * one differing position names the exact character variant (a
 * Traditional form the folding table does not know, a zero-width
 * character, an unexpected padding codepoint). Code POINTS, not
 * UTF-16 units, so a supplementary-plane character is one entry.
 */
fun titleCodepointsHex(title: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < title.length) {
        val codePoint = title.codePointAt(index)
        if (out.isNotEmpty()) out.append(' ')
        out.append("%04X".format(codePoint))
        index += Character.charCount(codePoint)
    }
    return out.toString()
}

/**
 * Length of the longest common substring of [a] and [b] — the
 * similarity measure behind [closestStoredFileNames]. Plain
 * dynamic programming over UTF-16 chars; both inputs are short
 * (a title, a file name), so the quadratic table is never a cost.
 */
fun longestCommonSubstringLength(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var best = 0
    var previous = IntArray(b.length + 1)
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        for (j in 1..b.length) {
            if (a[i - 1] == b[j - 1]) {
                current[j] = previous[j - 1] + 1
                if (current[j] > best) best = current[j]
            }
        }
        previous = current
    }
    return best
}

/**
 * Up to [limit] stored file names closest to the RAW reported
 * title (v1.0.24): both lists ranked together by
 * [longestCommonSubstringLength] against the title, descending;
 * ties keep list order (`.lrc` entries before audio entries, then
 * index order — the sort is stable). Names sharing not even one
 * character with the title are excluded: they carry no comparison
 * value. Like the raw probe this is deliberately UN-normalized —
 * it exists to show the near-miss names VERBATIM next to the
 * title's codepoints, so the exact differing character can be read
 * off one screenshot when the script folding still misses.
 */
fun closestStoredFileNames(
    rawTitle: String,
    lrcFileNames: List<String>,
    audioFileNames: List<String>,
    limit: Int = 3,
): List<ClosestStoredName> {
    if (rawTitle.isEmpty() || limit <= 0) return emptyList()
    val candidates =
        lrcFileNames.map { ClosestStoredName(it, StoredNameKind.LRC) } +
            audioFileNames.map { ClosestStoredName(it, StoredNameKind.AUDIO) }
    return candidates
        .map { name -> name to longestCommonSubstringLength(rawTitle, name.fileName) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}

/** How one lookup ended. */
enum class LookupOutcome {
    /** Step 1: the playing track's own audio file → its same-stem `.lrc`. */
    FOUND_SAME_STEM,

    /** Step 2: matched by track title/artist against `.lrc` file names. */
    FOUND_METADATA,

    /** No indexed `.lrc` matched the track's title/artist keys at all. */
    NOT_FOUND,

    /** Files matched, but every one decoded to zero timed lines. */
    PARSE_EMPTY,

    /** Files matched, but every one failed to open/read. */
    READ_FAILED,

    /** The lookup never reached matching — no usable index (folder unreadable / not picked). */
    ERROR,
}

/**
 * One lookup, as it actually ran: the inputs verbatim and what
 * each step of `LocalLyricsProvider.fetch` did with them.
 *
 * Steps that were never reached keep their nulls, which the
 * Settings rows render as "—": e.g. a same-stem hit never runs
 * the name fallback, so [fallbackMatchCount] stays null.
 */
data class LookupTrace(
    /** Wall-clock time the lookup started, epoch milliseconds. */
    val timestampMs: Long,
    /** Track title exactly as the player reported it — never normalized. */
    val title: String,
    /** Track artist(s) exactly as reported (", "-joined when the caller had several). */
    val artist: String,
    /** Package of the player that reported the track; "" when the caller did not say. */
    val sourcePackage: String,
    /** `.lrc` count of the index this lookup ran against; null when there was no index. */
    val indexLrcCount: Int?,
    /** Step 1: an audio file matching title+artist was found in the folder. */
    val audioFileFound: Boolean,
    /** Step 1: the same-stem `.lrc` candidate's file name; null when there was none. */
    val sameStemCandidate: String?,
    /** Step 1: the v1.0.19 title guard's verdict on the candidate; null when no candidate. */
    val titleCheckPassed: Boolean?,
    /** Step 2: how many indexed files the name fallback matched; null when never reached. */
    val fallbackMatchCount: Int?,
    /** The file whose lyrics were returned; null when nothing was returned. */
    val winnerFile: String?,
    /** Timed lines in the returned document; null when nothing was returned. */
    val parsedLineCount: Int?,
    /** Matched files (either step) whose bytes could not be read at all. */
    val readFailedCount: Int,
    /** Matched files (either step) that read fine but held zero timed lines. */
    val parseEmptyCount: Int,
    val outcome: LookupOutcome,
    /** Audio files in the index this lookup ran against; null when there was no index. */
    val indexAudioCount: Int? = null,
    /** Raw probe, `.lrc` side: indexed `.lrc` names containing the raw title; null when no index. */
    val rawProbeLrcCount: Int? = null,
    /** Raw probe, audio side: indexed audio names containing the raw title; null when no index. */
    val rawProbeAudioCount: Int? = null,
    /** The step that produced the returned document; null when nothing was returned. */
    val matchStep: LookupMatchStep? = null,
    /**
     * The received [title] as hex codepoints (v1.0.24, see
     * [titleCodepointsHex]); "" when the trace was built without
     * one (the Settings row renders "—").
     */
    val titleCodepoints: String = "",
    /**
     * The stored file names closest to the raw title (v1.0.24, see
     * [closestStoredFileNames]); empty when there was no index or
     * the trace was built without them.
     */
    val closestNames: List<ClosestStoredName> = emptyList(),
)

/** `yyyy-MM-dd HH:mm:ss` in the device's locale/timezone — what the user reads in a screenshot. */
fun formatLyricsDiagTimestamp(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

/** One row of the Settings lyrics-diagnostics block: a label and how to render its value. */
data class LyricsDiagRow(
    val label: LyricsDiagLabel,
    val value: DiagValue,
)

/** Row labels; the composable maps each to a string resource. */
enum class LyricsDiagLabel {
    INDEX_STATE,
    INDEX_COUNT,
    INDEX_AUDIO_COUNT,
    INDEX_DROPPED,
    LOOKUP_TIME,
    TRACK,
    TITLE_CODEPOINTS,
    SOURCE,
    AUDIO_FOUND,
    SAME_STEM,
    TITLE_CHECK,
    FALLBACK_MATCHES,
    RAW_PROBE,
    CLOSEST_NAMES,
    WINNER_FILE,
    MATCH_STEP,
    PARSED_LINES,
    READ_ISSUES,
    OUTCOME,
}

/** A row value: raw text, a yes/no, or an enum the composable localizes. */
sealed interface DiagValue {
    data class Text(val text: String) : DiagValue
    data class YesNo(val value: Boolean) : DiagValue
    data class IndexState(val state: LyricsIndexState) : DiagValue
    data class Outcome(val outcome: LookupOutcome) : DiagValue
    data class MatchStep(val step: LookupMatchStep) : DiagValue
}

private const val NO_VALUE = "—"

/**
 * The block's rows, in display order. The four index rows always
 * appear; the lookup rows appear once a lookup has been recorded.
 * Pure: all localization happens in the composable via
 * [LyricsDiagLabel] / [DiagValue].
 */
fun lyricsDiagRows(index: LyricsIndexDiag, lookup: LookupTrace?): List<LyricsDiagRow> {
    val rows = mutableListOf(
        LyricsDiagRow(
            LyricsDiagLabel.INDEX_STATE,
            DiagValue.IndexState(index.state),
        ),
        LyricsDiagRow(
            LyricsDiagLabel.INDEX_COUNT,
            DiagValue.Text(index.lrcCount?.toString() ?: NO_VALUE),
        ),
        LyricsDiagRow(
            LyricsDiagLabel.INDEX_AUDIO_COUNT,
            DiagValue.Text(index.audioCount?.toString() ?: NO_VALUE),
        ),
        LyricsDiagRow(
            LyricsDiagLabel.INDEX_DROPPED,
            DiagValue.Text(index.droppedCount?.toString() ?: NO_VALUE),
        ),
    )
    if (lookup == null) return rows
    val trackText = listOf(lookup.title, lookup.artist)
        .filter { it.isNotEmpty() }
        .joinToString(" — ")
        .ifEmpty { NO_VALUE }
    rows += LyricsDiagRow(
        LyricsDiagLabel.LOOKUP_TIME,
        DiagValue.Text(formatLyricsDiagTimestamp(lookup.timestampMs)),
    )
    rows += LyricsDiagRow(LyricsDiagLabel.TRACK, DiagValue.Text(trackText))
    rows += LyricsDiagRow(
        LyricsDiagLabel.TITLE_CODEPOINTS,
        DiagValue.Text(lookup.titleCodepoints.ifEmpty { NO_VALUE }),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.SOURCE,
        DiagValue.Text(lookup.sourcePackage.ifEmpty { NO_VALUE }),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.AUDIO_FOUND,
        if (lookup.indexLrcCount == null) {
            DiagValue.Text(NO_VALUE)
        } else {
            DiagValue.YesNo(lookup.audioFileFound)
        },
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.SAME_STEM,
        DiagValue.Text(lookup.sameStemCandidate ?: NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.TITLE_CHECK,
        lookup.titleCheckPassed?.let { DiagValue.YesNo(it) } ?: DiagValue.Text(NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.FALLBACK_MATCHES,
        DiagValue.Text(lookup.fallbackMatchCount?.toString() ?: NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.RAW_PROBE,
        DiagValue.Text(
            if (lookup.rawProbeAudioCount == null && lookup.rawProbeLrcCount == null) {
                NO_VALUE
            } else {
                "${lookup.rawProbeAudioCount ?: NO_VALUE}/${lookup.rawProbeLrcCount ?: NO_VALUE}"
            },
        ),
    )
    // v1.0.24: the closest stored names, verbatim, one row each —
    // beside the title's codepoints they show the exact character
    // a still-missing lookup differs by. The kind rides in the
    // text as （音频）/（歌词）so the row needs no new value type.
    if (lookup.closestNames.isEmpty()) {
        rows += LyricsDiagRow(
            LyricsDiagLabel.CLOSEST_NAMES,
            DiagValue.Text(NO_VALUE),
        )
    } else {
        for (name in lookup.closestNames) {
            rows += LyricsDiagRow(
                LyricsDiagLabel.CLOSEST_NAMES,
                DiagValue.Text(
                    name.fileName + when (name.kind) {
                        StoredNameKind.AUDIO -> "（音频）"
                        StoredNameKind.LRC -> "（歌词）"
                    },
                ),
            )
        }
    }
    rows += LyricsDiagRow(
        LyricsDiagLabel.WINNER_FILE,
        DiagValue.Text(lookup.winnerFile ?: NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.MATCH_STEP,
        lookup.matchStep?.let { DiagValue.MatchStep(it) } ?: DiagValue.Text(NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.PARSED_LINES,
        DiagValue.Text(lookup.parsedLineCount?.toString() ?: NO_VALUE),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.READ_ISSUES,
        DiagValue.Text("${lookup.readFailedCount}/${lookup.parseEmptyCount}"),
    )
    rows += LyricsDiagRow(
        LyricsDiagLabel.OUTCOME,
        DiagValue.Outcome(lookup.outcome),
    )
    return rows
}
