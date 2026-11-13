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

/** The index half of the diagnostics block: state plus the served `.lrc` count. */
data class LyricsIndexDiag(
    val state: LyricsIndexState,
    /** `.lrc` files in the served index; null when no index is being served. */
    val lrcCount: Int?,
)

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
    LOOKUP_TIME,
    TRACK,
    SOURCE,
    AUDIO_FOUND,
    SAME_STEM,
    TITLE_CHECK,
    FALLBACK_MATCHES,
    WINNER_FILE,
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
}

private const val NO_VALUE = "—"

/**
 * The block's rows, in display order. The two index rows always
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
        LyricsDiagLabel.WINNER_FILE,
        DiagValue.Text(lookup.winnerFile ?: NO_VALUE),
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
