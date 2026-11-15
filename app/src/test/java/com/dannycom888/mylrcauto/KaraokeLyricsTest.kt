package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.lyricsTickDelayMs
import com.dannycom888.mylrcauto.lyrics.LrcParser
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.WordSweep
import com.dannycom888.mylrcauto.lyrics.WordTiming
import androidx.compose.ui.graphics.Color
import com.dannycom888.mylrcauto.settings.SweepColorChoice
import com.dannycom888.mylrcauto.ui.SWEEP_COLOR
import com.dannycom888.mylrcauto.ui.SweepFill
import com.dannycom888.mylrcauto.ui.charFillProgress
import com.dannycom888.mylrcauto.ui.sweepColorFor
import com.dannycom888.mylrcauto.ui.sweepFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.33: enhanced-LRC word tags, the karaoke sweep, the seconds
 * validation, and the tick pace that follows from them. The fixtures
 * are lines in the exact shape the converter emits.
 */
class KaraokeLyricsTest {

    // ---- parsing word tags -------------------------------------------------

    @Test
    fun enhancedLineParsesWordTimingsAndEndMarker() {
        val lines = LrcParser.parse(
            "[00:30.82]<00:30.82>当<00:31.42>火<00:32.02>车<00:32.62>开<00:33.22>入" +
                "<00:33.82>这<00:34.42>座<00:35.02>陌<00:35.62>生<00:36.22>的" +
                "<00:36.82>城<00:37.42>市<00:38.02>",
        )
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(30_820L, line.timeMs)
        // The plain text is the words with every tag stripped — what
        // the mirror subtitle and the glance keep showing.
        assertEquals("当火车开入这座陌生的城市", line.text)
        assertEquals(13, line.words.size)
        assertEquals(WordTiming(30_820L, "当"), line.words[0])
        assertEquals(WordTiming(31_420L, "火"), line.words[1])
        assertEquals(WordTiming(37_420L, "市"), line.words[11])
        // The last tag carries no text: it is the line's end marker.
        assertEquals(WordTiming(38_020L, ""), line.words[12])
        // Fragments concatenate back to the line text exactly.
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun plainLinesHaveNoWordTimingsAndUnchangedText() {
        val lines = LrcParser.parse("[00:01.00]第一行\n[00:05.50]第二行 歌词")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.words.isEmpty() })
        assertEquals("第一行", lines[0].text)
        assertEquals("第二行 歌词", lines[1].text)
    }

    @Test
    fun angleBracketsInOrdinaryTextAreNotTags() {
        val lines = LrcParser.parse("[00:01.00]我 < 你 > 他\n[00:02.00]爱你<3分")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.words.isEmpty() })
        assertEquals("我 < 你 > 他", lines[0].text)
        assertEquals("爱你<3分", lines[1].text)
    }

    @Test
    fun wordTagNotAtLineStartStaysLiteralText() {
        // A tag-shaped string mid-line is not an enhanced line: the
        // line renders exactly as v1.0.32 rendered it, tag and all.
        val lines = LrcParser.parse("[00:01.00]歌词<00:02.00>混入")
        assertEquals(1, lines.size)
        assertTrue(lines[0].words.isEmpty())
        assertEquals("歌词<00:02.00>混入", lines[0].text)
    }

    @Test
    fun multiTagEnhancedLineSharesItsWordTimings() {
        val lines = LrcParser.parse("[00:01.00][00:03.00]<00:01.00>重<00:01.50>复")
        assertEquals(2, lines.size)
        assertEquals("重复", lines[0].text)
        assertEquals(lines[0].words, lines[1].words)
        assertEquals(WordTiming(1_000L, "重"), lines[0].words[0])
    }

    @Test
    fun offsetShiftsWordTimingsToo() {
        val lines = LrcParser.parse("[offset:+500]\n[00:01.00]<00:01.00>一<00:01.50>二")
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals(1_500L, lines[0].words[0].startMs)
        assertEquals(2_000L, lines[0].words[1].startMs)
    }

    // ---- seconds validation --------------------------------------------------

    @Test
    fun lineTagWithSecondsOver59IsSkipped() {
        val lines = LrcParser.parse("[00:75.00]坏行\n[01:60.00]也坏\n[00:05.00]好行")
        assertEquals(1, lines.size)
        assertEquals("好行", lines[0].text)
        assertEquals(5_000L, lines[0].timeMs)
    }

    @Test
    fun lineTagWith59SecondsIsValid() {
        val lines = LrcParser.parse("[00:59.99]边界")
        assertEquals(1, lines.size)
        assertEquals(59_990L, lines[0].timeMs)
    }

    @Test
    fun validTagSurvivesAlongsideAnInvalidOne() {
        val lines = LrcParser.parse("[00:75.00][00:03.00]重复行")
        assertEquals(1, lines.size)
        assertEquals(3_000L, lines[0].timeMs)
        assertEquals("重复行", lines[0].text)
    }

    @Test
    fun wordTagWithSecondsOver59DropsOnlyItsSegment() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>好<00:75.00>坏<00:02.00>行")
        assertEquals(1, lines.size)
        assertEquals("好行", lines[0].text)
        assertEquals(
            listOf(WordTiming(1_000L, "好"), WordTiming(2_000L, "行")),
            lines[0].words,
        )
    }

    // ---- the sweep -----------------------------------------------------------

    private val perChar = listOf(
        WordTiming(1_000L, "当"),
        WordTiming(1_600L, "火"),
        WordTiming(2_200L, "车"),
        WordTiming(3_800L, ""),
    )

    @Test
    fun sweepLightsPerCharacterFragmentsAtTheirStarts() {
        assertEquals(0, WordSweep.sungCharCount(perChar, 999L))
        assertEquals(0, WordSweep.sungCharCount(perChar, 1_000L))
        assertEquals(0, WordSweep.sungCharCount(perChar, 1_300L))
        assertEquals(1, WordSweep.sungCharCount(perChar, 1_600L))
        assertEquals(1, WordSweep.sungCharCount(perChar, 1_900L))
        assertEquals(2, WordSweep.sungCharCount(perChar, 2_200L))
        assertEquals(2, WordSweep.sungCharCount(perChar, 3_799L))
        assertEquals(3, WordSweep.sungCharCount(perChar, 3_800L))
        assertEquals(3, WordSweep.sungCharCount(perChar, 60_000L))
    }

    @Test
    fun sweepInterpolatesInsideAMultiCharacterFragment() {
        val words = listOf(
            WordTiming(0L, "abcd"),
            WordTiming(4_000L, "ef"),
            WordTiming(6_000L, ""),
        )
        assertEquals(0, WordSweep.sungCharCount(words, 0L))
        assertEquals(2, WordSweep.sungCharCount(words, 2_000L))
        assertEquals(3, WordSweep.sungCharCount(words, 3_999L))
        assertEquals(4, WordSweep.sungCharCount(words, 4_000L))
        assertEquals(5, WordSweep.sungCharCount(words, 5_000L))
        assertEquals(6, WordSweep.sungCharCount(words, 6_000L))
    }

    @Test
    fun sweepOfNoWordsIsZero() {
        assertEquals(0, WordSweep.sungCharCount(emptyList(), 10_000L))
    }

    // ---- tick pace -------------------------------------------------------------

    @Test
    fun tickRunsFastWhileAWordTimedLinePlays() {
        val document = LyricsDocument(
            lines = LrcParser.parse("[00:01.00]<00:01.00>一<00:01.50>二\n[00:05.00]普通行"),
            sourceName = "test",
        )
        assertEquals(200L, lyricsTickDelayMs(document, 1_200L))
        // The plain line that follows ticks at the ordinary pace.
        assertEquals(500L, lyricsTickDelayMs(document, 6_000L))
        // Before the first line there is nothing to sweep.
        assertEquals(500L, lyricsTickDelayMs(document, 100L))
    }

    @Test
    fun tickIsOrdinaryForPlainDocumentsAndNone() {
        val document = LyricsDocument(
            lines = LrcParser.parse("[00:01.00]普通行"),
            sourceName = "test",
        )
        assertEquals(500L, lyricsTickDelayMs(document, 2_000L))
        assertEquals(500L, lyricsTickDelayMs(null, 2_000L))
    }

    // ---- smooth fill (v1.0.34) -------------------------------------------------

    @Test
    fun charFillProgressRunsLinearlyAcrossTheCharacter() {
        // A character sung from 1.0s to 1.6s.
        assertEquals(0f, charFillProgress(1_000L, 1_600L, 400L), 0.001f)
        assertEquals(0f, charFillProgress(1_000L, 1_600L, 1_000L), 0.001f)
        assertEquals(0.5f, charFillProgress(1_000L, 1_600L, 1_300L), 0.001f)
        assertEquals(1f, charFillProgress(1_000L, 1_600L, 1_600L), 0.001f)
        assertEquals(1f, charFillProgress(1_000L, 1_600L, 9_000L), 0.001f)
    }

    @Test
    fun charFillProgressWithoutASpanStepsAtItsMoment() {
        // endMs <= startMs: nothing to fill across, so the character
        // is unsung before its moment and sung from its moment on.
        assertEquals(0f, charFillProgress(2_000L, 2_000L, 1_999L), 0.001f)
        assertEquals(1f, charFillProgress(2_000L, 2_000L, 2_000L), 0.001f)
        assertEquals(1f, charFillProgress(2_000L, 2_000L, 2_001L), 0.001f)
        // A reversed span follows the same rule, keyed on endMs.
        assertEquals(0f, charFillProgress(2_000L, 1_500L, 1_499L), 0.001f)
        assertEquals(1f, charFillProgress(2_000L, 1_500L, 1_500L), 0.001f)
    }

    @Test
    fun sweepFillFillsTheCharacterBeingSung() {
        // Three characters, 600 ms each, plus the end marker.
        val words = listOf(
            WordTiming(1_000L, "当"),
            WordTiming(1_600L, "火"),
            WordTiming(2_200L, "车"),
            WordTiming(2_800L, ""),
        )
        assertEquals(SweepFill(0, 0f), sweepFill(words, 999L))
        assertEquals(SweepFill(0, 0f), sweepFill(words, 1_000L))
        assertEquals(SweepFill(0, 0.5f), sweepFill(words, 1_300L))
        assertEquals(SweepFill(1, 0f), sweepFill(words, 1_600L))
        assertEquals(SweepFill(1, 0.5f), sweepFill(words, 1_900L))
        assertEquals(SweepFill(3, 1f), sweepFill(words, 2_800L))
        assertEquals(SweepFill(3, 1f), sweepFill(words, 60_000L))
    }

    @Test
    fun sweepFillDividesAMultiCharacterFragmentEvenly() {
        // One two-character fragment sung across a full second, then
        // the end marker: each character gets half the span.
        val words = listOf(
            WordTiming(1_000L, "AB"),
            WordTiming(2_000L, ""),
        )
        assertEquals(SweepFill(0, 0.5f), sweepFill(words, 1_250L))
        assertEquals(SweepFill(1, 0f), sweepFill(words, 1_500L))
        assertEquals(SweepFill(1, 0.5f), sweepFill(words, 1_750L))
        assertEquals(SweepFill(2, 1f), sweepFill(words, 2_000L))
    }

    @Test
    fun sweepFillLastFragmentWithoutEndMarkerStepsWhole() {
        // No trailing end marker: the last fragment has no measured
        // end, so it counts as sung whole from its start — the
        // behaviour such a line had before the smooth fill.
        val words = listOf(
            WordTiming(1_000L, "当"),
            WordTiming(2_000L, "火"),
        )
        assertEquals(SweepFill(0, 0.5f), sweepFill(words, 1_500L))
        assertEquals(SweepFill(2, 1f), sweepFill(words, 2_000L))
        assertEquals(SweepFill(2, 1f), sweepFill(words, 9_000L))
    }

    @Test
    fun sweepFillOfNoWordsIsDoneAndEmpty() {
        assertEquals(SweepFill(0, 1f), sweepFill(emptyList(), 10_000L))
    }

    // ---- sweep colour choice (v1.0.35) -------------------------------------

    @Test
    fun sweepColorGoldIsTheOriginalSweepColor() {
        // GOLD maps to SWEEP_COLOR itself: the default choice is the
        // exact colour the sweep has had since v1.0.33, unchanged.
        assertEquals(Color(0xFFFFD54F), SWEEP_COLOR)
        assertEquals(SWEEP_COLOR, sweepColorFor(SweepColorChoice.GOLD))
    }

    @Test
    fun sweepColorOrangeAndRedAreThePickedHues() {
        assertEquals(Color(0xFFFF9800), sweepColorFor(SweepColorChoice.ORANGE))
        assertEquals(Color(0xFFFF5252), sweepColorFor(SweepColorChoice.RED))
    }
}
