package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.DiagValue
import com.dannycom888.mylrcauto.lyrics.ClosestStoredName
import com.dannycom888.mylrcauto.lyrics.LookupMatchStep
import com.dannycom888.mylrcauto.lyrics.LookupOutcome
import com.dannycom888.mylrcauto.lyrics.LookupTrace
import com.dannycom888.mylrcauto.lyrics.StoredNameKind
import com.dannycom888.mylrcauto.lyrics.closestStoredFileNames
import com.dannycom888.mylrcauto.lyrics.longestCommonSubstringLength
import com.dannycom888.mylrcauto.lyrics.rawTitleProbe
import com.dannycom888.mylrcauto.lyrics.titleCodepointsHex
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
        indexAudioCount: Int? = 845,
        rawProbeLrcCount: Int? = 1,
        rawProbeAudioCount: Int? = 1,
        matchStep: LookupMatchStep? = LookupMatchStep.AUDIO_WITH_ARTIST,
        titleCodepoints: String = "4E0D 518D 72B9 8C6B",
        closestNames: List<ClosestStoredName> = listOf(
            ClosestStoredName("Beyond - 不再犹豫.lrc", StoredNameKind.LRC),
            ClosestStoredName("Beyond - 不再犹豫.mp3", StoredNameKind.AUDIO),
        ),
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
        indexAudioCount = indexAudioCount,
        rawProbeLrcCount = rawProbeLrcCount,
        rawProbeAudioCount = rawProbeAudioCount,
        matchStep = matchStep,
        titleCodepoints = titleCodepoints,
        closestNames = closestNames,
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
        assertEquals(4, rows.size)
        assertEquals(LyricsDiagLabel.INDEX_STATE, rows[0].label)
        assertEquals(DiagValue.IndexState(LyricsIndexState.READY), rows[0].value)
        assertEquals(LyricsDiagLabel.INDEX_COUNT, rows[1].label)
        assertEquals(DiagValue.Text("1092"), rows[1].value)
        // v1.0.23: the audio count and the dropped-row count ride
        // along; unknown (a bare diag) renders as "—", not 0.
        assertEquals(LyricsDiagLabel.INDEX_AUDIO_COUNT, rows[2].label)
        assertEquals(DiagValue.Text("—"), rows[2].value)
        assertEquals(LyricsDiagLabel.INDEX_DROPPED, rows[3].label)
        assertEquals(DiagValue.Text("—"), rows[3].value)
    }

    @Test
    fun rowsForASameStemHitShowInputsAndEveryStep() {
        val index = LyricsIndexDiag(
            LyricsIndexState.READY, 1092, audioCount = 845, droppedCount = 0,
        )
        val rows = lyricsDiagRows(
            index,
            trace(LookupOutcome.FOUND_SAME_STEM),
        )
        // Index rows first, then the lookup rows in display order.
        assertEquals(
            listOf(
                LyricsDiagLabel.INDEX_STATE,
                LyricsDiagLabel.INDEX_COUNT,
                LyricsDiagLabel.INDEX_AUDIO_COUNT,
                LyricsDiagLabel.INDEX_DROPPED,
                LyricsDiagLabel.LOOKUP_TIME,
                LyricsDiagLabel.TRACK,
                LyricsDiagLabel.TITLE_CODEPOINTS,
                LyricsDiagLabel.SOURCE,
                LyricsDiagLabel.AUDIO_FOUND,
                LyricsDiagLabel.SAME_STEM,
                LyricsDiagLabel.TITLE_CHECK,
                LyricsDiagLabel.FALLBACK_MATCHES,
                LyricsDiagLabel.RAW_PROBE,
                LyricsDiagLabel.CLOSEST_NAMES,
                LyricsDiagLabel.CLOSEST_NAMES,
                LyricsDiagLabel.WINNER_FILE,
                LyricsDiagLabel.MATCH_STEP,
                LyricsDiagLabel.PARSED_LINES,
                LyricsDiagLabel.READ_ISSUES,
                LyricsDiagLabel.OUTCOME,
            ),
            rows.map { it.label },
        )
        val byLabel = rowsByLabel(
            index,
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
        // v1.0.24: the title's codepoints ride right under it.
        assertEquals(
            DiagValue.Text("4E0D 518D 72B9 8C6B"),
            byLabel[LyricsDiagLabel.TITLE_CODEPOINTS],
        )
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
        // v1.0.23 rows: the served audio count, the scan's dropped
        // count, the raw probe ("audio/.lrc") and the producing step.
        assertEquals(DiagValue.Text("845"), byLabel[LyricsDiagLabel.INDEX_AUDIO_COUNT])
        assertEquals(DiagValue.Text("0"), byLabel[LyricsDiagLabel.INDEX_DROPPED])
        assertEquals(DiagValue.Text("1/1"), byLabel[LyricsDiagLabel.RAW_PROBE])
        // v1.0.24: one row per closest stored name, verbatim, kind
        // marked in the text — .lrc first (the helper's order).
        val closestRows = rows.filter { it.label == LyricsDiagLabel.CLOSEST_NAMES }
        assertEquals(
            listOf(
                DiagValue.Text("Beyond - 不再犹豫.lrc（歌词）"),
                DiagValue.Text("Beyond - 不再犹豫.mp3（音频）"),
            ),
            closestRows.map { it.value },
        )
        assertEquals(
            DiagValue.MatchStep(LookupMatchStep.AUDIO_WITH_ARTIST),
            byLabel[LyricsDiagLabel.MATCH_STEP],
        )
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
    fun rowsForAFuzzyHitCarryTheFuzzyStep() {
        // v1.0.25: a lookup the fuzzy last resort produced carries
        // the FUZZY step into the MATCH_STEP row, so a trace shows
        // the near-match for what it was.
        val byLabel = rowsByLabel(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(
                outcome = LookupOutcome.FOUND_METADATA,
                audioFileFound = true,
                titleCheckPassed = false,
                fallbackMatchCount = 1,
                matchStep = LookupMatchStep.FUZZY,
                titleCodepoints = "4E0D 518D 4F18 8C6B",
            ),
        )
        assertEquals(
            DiagValue.MatchStep(LookupMatchStep.FUZZY),
            byLabel[LyricsDiagLabel.MATCH_STEP],
        )
        assertEquals(
            DiagValue.Outcome(LookupOutcome.FOUND_METADATA),
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
                rawProbeLrcCount = 0,
                rawProbeAudioCount = 0,
                matchStep = null,
            ),
        )
        assertEquals(DiagValue.Text("0"), byLabel[LyricsDiagLabel.FALLBACK_MATCHES])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.SAME_STEM])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.TITLE_CHECK])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.WINNER_FILE])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.PARSED_LINES])
        // The 2026-11-12 device-trace shape: probe 0/0 says the
        // entries never reached the served index, and a miss has
        // no producing step.
        assertEquals(DiagValue.Text("0/0"), byLabel[LyricsDiagLabel.RAW_PROBE])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.MATCH_STEP])
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
                matchStep = null,
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
                indexAudioCount = null,
                rawProbeLrcCount = null,
                rawProbeAudioCount = null,
                matchStep = null,
            ),
        )
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.INDEX_COUNT])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.INDEX_AUDIO_COUNT])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.RAW_PROBE])
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.MATCH_STEP])
        // No index: "audio found" is not false, it is unknowable.
        assertEquals(DiagValue.Text("—"), byLabel[LyricsDiagLabel.AUDIO_FOUND])
        assertEquals(
            DiagValue.Outcome(LookupOutcome.ERROR),
            byLabel[LyricsDiagLabel.OUTCOME],
        )
    }

    @Test
    fun rawProbeCountsVerbatimTitlePerList() {
        val probe = rawTitleProbe(
            rawTitle = "不再犹豫",
            lrcFileNames = listOf(
                "Beyond - 不再犹豫.lrc",
                "Beyond - 喜欢你.lrc",
                "不再犹豫 (Live).lrc",
            ),
            audioFileNames = listOf("Beyond - 不再犹豫.mp3", "海阔天空.mp3"),
        )
        assertEquals(2, probe.lrcCount)
        assertEquals(1, probe.audioCount)
    }

    @Test
    fun rawProbeIsRawNoNormalizationNoCaseFolding() {
        // The probe's whole value is that it does NOT normalize:
        // a name that only matches after normalization (different
        // case / spacing) must NOT count — probe 0 with a matcher
        // hit is a different diagnosis from probe 0 with a miss.
        val probe = rawTitleProbe(
            rawTitle = "Bu Zai You Yu",
            lrcFileNames = listOf("bu zai you yu.lrc", "BuZaiYouYu.lrc"),
            audioFileNames = listOf("BU ZAI YOU YU.mp3"),
        )
        assertEquals(0, probe.lrcCount)
        assertEquals(0, probe.audioCount)
    }

    @Test
    fun rawProbeEmptyTitleIsZeroZero() {
        val probe = rawTitleProbe(
            rawTitle = "",
            lrcFileNames = listOf("Beyond - 不再犹豫.lrc"),
            audioFileNames = listOf("Beyond - 不再犹豫.mp3"),
        )
        assertEquals(0, probe.lrcCount)
        assertEquals(0, probe.audioCount)
    }

    // ---- v1.0.24: codepoints + closest stored names --------------

    @Test
    fun titleCodepointsHexFormatsUppercasePaddedHex() {
        // The example from the spec: 不再犹豫.
        assertEquals("4E0D 518D 72B9 8C6B", titleCodepointsHex("不再犹豫"))
        // The Traditional variant differs at exactly one position —
        // 7336 （猶） vs 72B9 （犹） — which is the whole point of
        // showing this row: the differing slot names the variant.
        assertEquals("4E0D 518D 7336 8C6B", titleCodepointsHex("不再猶豫"))
        // ASCII is padded to four digits; empty stays empty.
        assertEquals("0042 0065 0079 006F 006E 0064", titleCodepointsHex("Beyond"))
        assertEquals("", titleCodepointsHex(""))
    }

    @Test
    fun longestCommonSubstringCountsTheLongestRun() {
        assertEquals(4, longestCommonSubstringLength("不再犹豫", "Beyond - 不再犹豫.lrc"))
        // Traditional file name vs Simplified title: only "不再"
        // runs — the script difference breaks the substring there.
        assertEquals(2, longestCommonSubstringLength("不再犹豫", "Beyond - 不再猶豫.lrc"))
        assertEquals(0, longestCommonSubstringLength("不再犹豫", "海阔天空.mp3"))
        assertEquals(0, longestCommonSubstringLength("", "不再犹豫.lrc"))
        assertEquals(0, longestCommonSubstringLength("不再犹豫", ""))
    }

    @Test
    fun closestStoredFileNamesRankByLongestCommonSubstring() {
        val closest = closestStoredFileNames(
            rawTitle = "不再犹豫",
            lrcFileNames = listOf(
                "Beyond - 不再猶豫.lrc",
                "不再犹豫 (Live).lrc",
                "汪峰 - 北京北京.lrc",
            ),
            audioFileNames = listOf(
                "Beyond - 不再猶豫.mp3",
                "海阔天空.mp3",
            ),
        )
        // The verbatim name (4) first; the Traditional pair (2)
        // next, .lrc before audio on the tie; names sharing no
        // character with the title are excluded entirely.
        assertEquals(
            listOf(
                ClosestStoredName("不再犹豫 (Live).lrc", StoredNameKind.LRC),
                ClosestStoredName("Beyond - 不再猶豫.lrc", StoredNameKind.LRC),
                ClosestStoredName("Beyond - 不再猶豫.mp3", StoredNameKind.AUDIO),
            ),
            closest,
        )
    }

    @Test
    fun closestStoredFileNamesRespectLimitAndEmptyTitle() {
        val names = (1..6).map { "不再犹豫 $it.lrc" }
        assertEquals(3, closestStoredFileNames("不再犹豫", names, emptyList()).size)
        assertEquals(
            2,
            closestStoredFileNames("不再犹豫", names, emptyList(), limit = 2).size,
        )
        assertTrue(closestStoredFileNames("", names, emptyList()).isEmpty())
        assertTrue(
            closestStoredFileNames("不再犹豫", listOf("海阔天空.lrc"), emptyList()).isEmpty(),
        )
    }

    @Test
    fun rawProbeStaysRawAcrossScripts() {
        // The probe is deliberately NOT folded: a Traditional file
        // name does not contain the Simplified title verbatim, so
        // the probe reads 0/0 even though v1.0.24's matcher now
        // finds the file. Probe 0/0 + a HIT is the new signature of
        // "script variant, folded"; probe 0/0 + a MISS keeps its
        // v1.0.23 meaning and the closest-names row shows why.
        val probe = rawTitleProbe(
            rawTitle = "不再犹豫",
            lrcFileNames = listOf("Beyond - 不再猶豫.lrc"),
            audioFileNames = listOf("Beyond - 不再猶豫.mp3"),
        )
        assertEquals(0, probe.lrcCount)
        assertEquals(0, probe.audioCount)
    }

    @Test
    fun rowsWithoutClosestNamesShowOneDashRow() {
        val rows = lyricsDiagRows(
            LyricsIndexDiag(LyricsIndexState.READY, 1092),
            trace(LookupOutcome.NOT_FOUND, closestNames = emptyList()),
        )
        val closestRows = rows.filter { it.label == LyricsDiagLabel.CLOSEST_NAMES }
        assertEquals(1, closestRows.size)
        assertEquals(DiagValue.Text("—"), closestRows.single().value)
    }
}
