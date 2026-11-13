package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.DiagValue
import com.dannycom888.mylrcauto.lyrics.LookupOutcome
import com.dannycom888.mylrcauto.lyrics.LookupTrace
import com.dannycom888.mylrcauto.lyrics.LyricsDiagLabel
import com.dannycom888.mylrcauto.lyrics.LyricsIndexDiag
import com.dannycom888.mylrcauto.lyrics.LyricsIndexState
import com.dannycom888.mylrcauto.lyrics.lyricsDiagRows
import com.dannycom888.mylrcauto.lyrics.lyricsIndexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.0.22 lyrics diagnostics: the index-state derivation and
 * the Settings row formatting. Pure — the composable only maps
 * the labels and enum values to string resources, so everything
 * the user sees in the block is decided (and pinned) here.
 */
class LyricsDiagnosticsTest {

    @Test
    fun indexStateNoFolderWinsOutright() {
        assertEquals(
            LyricsIndexState.NO_FOLDER,
            lyricsIndexState(configured = false, walkInFlight = true, lastError = true, hasIndex = true),
        )
        assertEquals(
            LyricsIndexState.NO_FOLDER,
            lyricsIndexState(configured = false, walkInFlight = false, lastError = false, hasIndex = false),
        )
    }

    @Test
    fun indexStateWalkInFlightIsBuildingEvenOverAHeldIndex() {
        assertEquals(
            LyricsIndexState.BUILDING,
            lyricsIndexState(configured = true, walkInFlight = true, lastError = false, hasIndex = true),
        )
    }

    @Test
    fun indexStateHeldIndexIsReadyEvenWhenTheLastRebuildFailed() {
        assertEquals(
            LyricsIndexState.READY,
            lyricsIndexState(configured = true, walkInFlight = false, lastError = true, hasIndex = true),
        )
        assertEquals(
            LyricsIndexState.READY,
            lyricsIndexState(configured = true, walkInFlight = false, lastError = false, hasIndex = true),
        )
    }

    @Test
    fun indexStateErrorWithNoIndexIsUnreadable() {
        assertEquals(
            LyricsIndexState.UNREADABLE,
            lyricsIndexState(configured = true, walkInFlight = false, lastError = true, hasIndex = false),
        )
    }

    @Test
    fun indexStateNothingAttemptedYetIsBuilding() {
        assertEquals(
            LyricsIndexState.BUILDING,
            lyricsIndexState(configured = true, walkInFlight = false, lastError = false, hasIndex = false),
        )
    }

    private fun trace(
        outcome: LookupOutcome,
        audioFileFound: Boolean = true,
        sameStemCandidate: String? = "Beyond - 不再犹豫.lrc",
        titleCheckPassed: Boolean? = true,
        fallbackMatchCount: Int? = null,
        winnerFile: String? = "Beyond - 不再犹豫.lrc",
        parsedLineCount: Int? = 50,
        readFailedCount: Int = 0,
        parseEmptyCount: Int = 0,
        indexLrcCount: Int? = 1092,
    ) = LookupTrace(
        timestampMs = 1_763_000_000_000L,
        title = "不再犹豫",
        artist = "Beyond",
        sourcePackage = "com.maxmpz.audioplayer",
        indexLrcCount = indexLrcCount,
        audioFileFound = audioFileFound,
        sameStemCandidate = sameStemCandidate,
        titleCheckPassed = titleCheckPassed,
        fallbackMatchCount = fallbackMatchCount,
        winnerFile = winnerFile,
        parsedLineCount = parsedLineCount,
        readFailedCount = readFailedCount,
        parseEmptyCount = parseEmptyCount,
        outcome = outcome,
    )

    private fun rowsByLabel(
        index: LyricsIndexDiag,
        lookup: LookupTrace?,
    ): Map<LyricsDiagLabel, DiagValue> =
        lyricsDiagRows(index, lookup).associate { it.label to it.value }

    @Test
    fun rowsWithoutALookupAreJustTheIndexRows() {
        val rows = lyricsDiagRows(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            lookup = null,
        )
        assertEquals(2, rows.size)
        assertEquals(LyricsDiagLabel.INDEX_STATE, rows[0].label)
        assertEquals(DiagValue.IndexState(LyricsIndexState.READY), rows[0].value)
        assertEquals(LyricsDiagLabel.INDEX_COUNT, rows[1].label)
        assertEquals(DiagValue.Text("1092"), rows[1].value)
    }

    @Test
    fun rowsForASameStemHitShowInputsAndEveryStep() {
        val rows = lyricsDiagRows(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(LookupOutcome.FOUND_SAME_STEM),
        )
        // Index rows first, then the lookup rows in display order.
        assertEquals(
            listOf(
                LyricsDiagLabel.INDEX_STATE,
                LyricsDiagLabel.INDEX_COUNT,
                LyricsDiagLabel.LOOKUP_TIME,
                LyricsDiagLabel.TRACK,
                LyricsDiagLabel.SOURCE,
                LyricsDiagLabel.AUDIO_FOUND,
                LyricsDiagLabel.SAME_STEM,
                LyricsDiagLabel.TITLE_CHECK,
                LyricsDiagLabel.FALLBACK_MATCHES,
                LyricsDiagLabel.WINNER_FILE,
                LyricsDiagLabel.PARSED_LINES,
                LyricsDiagLabel.READ_ISSUES,
                LyricsDiagLabel.OUTCOME,
            ),
            rows.map { it.label },
        )
        val byLabel = rowsByLabel(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(LookupOutcome.FOUND_SAME_STEM),
        )
        val time = (byLabel.getValue(LyricsDiagLabel.LOOKUP_TIME) as DiagValue.Text).text
        assertTrue(
            "timestamp row is a formatted time, was: $time",
            time.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")),
        )
        // The inputs are verbatim — this is how a wrong title or
        // artist from the player becomes visible in a screenshot.
        assertEquals(DiagValue.Text("不再犹豫 — Beyond"), byLabel[LyricsDiagLabel.TRACK])
        assertEquals(
            DiagValue.Text("com.maxmpz.audioplayer"),
            byLabel[LyricsDiagLabel.SOURCE],
        )
        assertEquals(DiagValue.YesNo(true), byLabel[LyricsDiagLabel.AUDIO_FOUND])
        assertEquals(
            DiagValue.Text("Beyond - 不再犹豫.lrc"),
            byLabel[LyricsDiagLabel.SAME_STEM],
        )
        assertEquals(DiagValue.YesNo(true), byLabel[LyricsDiagLabel.TITLE_CHECK])
        // The fallback never ran on a same-stem hit: "—", not 0.
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.FALLBACK_MATCHES])
        assertEquals(
            DiagValue.Text("Beyond - 不再犹豫.lrc"),
            byLabel[LyricsDiagLabel.WINNER_FILE],
        )
        assertEquals(DiagValue.Text("50"), byLabel[LyricsDiagLabel.PARSED_LINES])
        assertEquals(DiagValue.Text("0/0"), byLabel[LyricsDiagLabel.READ_ISSUES])
        assertEquals(
            DiagValue.Outcome(LookupOutcome.FOUND_SAME_STEM),
            byLabel[LyricsDiagLabel.OUTCOME],
        )
    }

    @Test
    fun rowsForANotFoundShowTheFallbackRanAndMatchedNothing() {
        val byLabel = rowsByLabel(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(
                LookupOutcome.NOT_FOUND,
                sameStemCandidate = null,
                titleCheckPassed = null,
                fallbackMatchCount = 0,
                winnerFile = null,
                parsedLineCount = null,
            ),
        )
        assertEquals(DiagValue.Text("0"), byLabel[LyricsDiagLabel.FALLBACK_MATCHES])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.SAME_STEM])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.TITLE_CHECK])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.WINNER_FILE])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.PARSED_LINES])
        assertEquals(
            DiagValue.Outcome(LookupOutcome.NOT_FOUND),
            byLabel[LyricsDiagLabel.OUTCOME],
        )
    }

    @Test
    fun rowsForAParseEmptyShowTheReadParseSplit() {
        val byLabel = rowsByLabel(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(
                LookupOutcome.PARSE_EMPTY,
                audioFileFound = false,
                sameStemCandidate = null,
                titleCheckPassed = null,
                fallbackMatchCount = 2,
                winnerFile = null,
                parsedLineCount = null,
                readFailedCount = 1,
                parseEmptyCount = 1,
            ),
        )
        // "read failed / parsed empty": one of each — the two
        // failure kinds the old single null used to merge.
        assertEquals(DiagValue.Text("1/1"), byLabel[LyricsDiagLabel.READ_ISSUES])
        assertEquals(DiagValue.Text("2"), byLabel[LyricsDiagLabel.FALLBACK_MATCHES])
        assertEquals(
            DiagValue.Outcome(LookupOutcome.PARSE_EMPTY),
            byLabel[LyricsDiagLabel.OUTCOME],
        )
    }

    @Test
    fun rowsForAnErrorTraceHaveNoIndexDerivedValues() {
        val byLabel = rowsByLabel(
            LyricsIndexDiag(LyricsIndexState.UNREADABLE, null),
            trace(
                LookupOutcome.ERROR,
                audioFileFound = false,
                sameStemCandidate = null,
                titleCheckPassed = null,
                winnerFile = null,
                parsedLineCount = null,
                indexLrcCount = null,
            ),
        )
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.INDEX_COUNT])
        // No index: "audio found" is not false, it is unknowable.
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.AUDIO_FOUND])
        assertEquals(
            DiagValue.Outcome(LookupOutcome.ERROR),
            byLabel[LyricsDiagLabel.OUTCOME],
        )
    }
}
