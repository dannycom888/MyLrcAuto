package com.dannycom888.mylrcauto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricLine
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.WordSweep
import com.dannycom888.mylrcauto.settings.LyricsFontChoice
import kotlinx.coroutines.CancellationException

/**
 * The shared lyrics list, used by the phone screen and the car surface.
 *
 * A [LazyColumn] of every line. The line playing at [positionMsProvider] is
 * highlighted (larger, bolder, accent colour) and auto-scrolled into view
 * whenever it changes. The playhead position comes from [positionMsProvider],
 * evaluated during composition — the caller drives recomposition with a
 * ticker, so this composable just reads it.
 *
 * Lines before the first timestamp ([LyricsDocument.indexAt] returning -1)
 * show normally, with no line highlighted.
 *
 * A current line that carries word timings (an enhanced LRC) adds
 * a sweep over that same highlight: the whole line keeps its
 * glowing colour, and the characters already sung are repainted in
 * [SWEEP_COLOR] as the sweep reaches them. Plain lines — every
 * line of a plain file — highlight whole, exactly as before.
 *
 * Auto-scroll does not fight the reader: once the user drags the list, the
 * list stops following and a "back to current line" button appears; tapping
 * it resumes following. Tapping any line seeks to it via [onSeek].
 *
 * [followAlways] is for surfaces that are not touchable (the car): a drag
 * can never switch following off there, and there is no button to resume it
 * with, so following simply stays on.
 *
 * [fontSizeSp] is the base size (non-current lines), from Settings; the
 * current line is 5 sp larger, exactly as it was when the sizes were
 * hardcoded (17 sp / 22 sp). Both the phone screen and the car surface pass
 * the same value, so one setting sizes both.
 *
 * [lyricsFont] is the Chinese lyrics font choice from Settings
 * (Bauhaus or the original font); it is passed through to
 * [lyricsFontSpec] per line. English lines are unaffected.
 *
 * An empty document renders nothing; the caller owns the empty state.
 */
object LyricsTextSize {
    /** Current line: 5 sp larger than the base size (17 → 22). */
    fun currentSizeSp(baseSp: Int): Int = baseSp + 5

    /** Current line height: base + 11 sp (17 → 28). */
    fun currentLineHeightSp(baseSp: Int): Int = baseSp + 11

    /** Other lines' height: base + 5 sp (17 → 22). */
    fun lineHeightSp(baseSp: Int): Int = baseSp + 5
}

/**
 * The first visible row the list scrolls to for the current line.
 *
 * The current line lands on the third visible row, with two lines of
 * context above it — clamped to the top at the start of a song and to
 * the last line at the end. Pure, so the scroll-following decision is
 * unit-testable: when the current line advances, this target must
 * advance with it, or the list stops following the song.
 */
internal fun scrollTargetIndex(currentIndex: Int, lineCount: Int): Int =
    (currentIndex - 2).coerceAtLeast(0)
        .coerceAtMost((lineCount - 1).coerceAtLeast(0))

/**
 * The karaoke sweep colour: the characters already sung are painted
 * in this, over the current line's unchanged glowing base colour.
 * Golden yellow — a hue contrast against the cyan glow, not a
 * brightness one, so the swept part keeps its glow. THE one place
 * to change it when the user picks another colour.
 */
internal val SWEEP_COLOR: Color = Color(0xFFFFD54F)

/**
 * The current line of an enhanced file, split at the sweep: the
 * characters already sung in [sungColor], the rest in [unsungColor]
 * (the line's ordinary glowing colour — the base layer shows
 * through untouched). The split point is [WordSweep]'s pure count;
 * this only paints it.
 */
private fun karaokeText(
    line: LyricLine,
    positionMs: Long,
    sungColor: Color,
    unsungColor: Color,
): AnnotatedString {
    val sungCount = WordSweep.sungCharCount(line.words, positionMs)
        .coerceIn(0, line.text.length)
    return buildAnnotatedString {
        append(line.text)
        if (sungCount > 0) {
            addStyle(SpanStyle(color = sungColor), 0, sungCount)
        }
        if (sungCount < line.text.length) {
            addStyle(SpanStyle(color = unsungColor), sungCount, line.text.length)
        }
    }
}

@Composable
fun LyricsView(
    document: LyricsDocument,
    positionMsProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    followAlways: Boolean = false,
    fontSizeSp: Int = 17,
    lyricsFont: LyricsFontChoice = LyricsFontChoice.BAUHAUS,
) {
    // Keyed on the document, so a new song starts at the top: without the
    // key the scroll position carried over from the previous song, and the
    // second song opened already scrolled to its end. LyricsDocument is a
    // data class, so re-emission of equal content keeps the state.
    val listState = remember(document) { LazyListState() }
    // One read of the playhead per composition: the line lookup and
    // the karaoke sweep below must agree on the position they use.
    val positionMs = positionMsProvider()
    val currentIndex = document.indexAt(positionMs)

    // A new document starts by following the line that is playing.
    var followCurrent by remember(document) { mutableStateOf(true) }

    // A drag is the user taking the scroll for themselves: stop following
    // until they ask to come back. (Programmatic scrolls do not produce a
    // DragInteraction, so following never switches itself off.) Skipped
    // entirely when followAlways is set: on a non-touchable surface a lost
    // follow could never be resumed.
    LaunchedEffect(listState) {
        if (followAlways) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                followCurrent = false
            }
        }
    }

    // Only scroll when the current line actually changes (or following
    // resumes); a scroll on every recomposition would fight the user.
    // Scroll so the current line lands on the third visible row, with two
    // lines of context above it (clamped to the top at the start of a song).
    // The target is clamped to the document and the scroll is guarded: a
    // scroll must never crash the composition on the car surface. If the
    // animated scroll fails, fall back to an instant jump — except for
    // cancellation, which means this effect is restarting and must be let
    // through so the new run can take over.
    LaunchedEffect(currentIndex, followCurrent) {
        if (followCurrent && currentIndex >= 0) {
            val target = scrollTargetIndex(currentIndex, document.lines.size)
            try {
                listState.animateScrollToItem(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { listState.scrollToItem(target) }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(document.lines, key = { index, _ -> index }) { index, line ->
                val isCurrent = index == currentIndex
                // Chinese lines draw in the chosen font (Bauhaus bold, or
                // the original default font); English lines keep the
                // default font, unchanged. The glowing current line keeps
                // its colour and size either way — only the font and
                // weight change here.
                val fontSpec = lyricsFontSpec(line.text, isCurrent, lyricsFont)
                val lineModifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                val lineFontSize =
                    if (isCurrent) LyricsTextSize.currentSizeSp(fontSizeSp).sp else fontSizeSp.sp
                val lineHeight =
                    if (isCurrent) LyricsTextSize.currentLineHeightSp(fontSizeSp).sp else LyricsTextSize.lineHeightSp(fontSizeSp).sp
                if (isCurrent && line.words.isNotEmpty()) {
                    // Karaoke sweep (v1.0.33): two layers. The base
                    // layer is the ordinary current line — the whole
                    // line in its glowing colour, exactly as v1.0.32
                    // draws it, unsung part included. Over it, the
                    // characters already sung are repainted in
                    // SWEEP_COLOR, advancing character by character,
                    // so the sweep reads by hue against the glow —
                    // never by dimming anything. Only the current
                    // line of an enhanced file takes this path; every
                    // other line renders exactly as before.
                    Text(
                        text = karaokeText(
                            line = line,
                            positionMs = positionMs,
                            sungColor = SWEEP_COLOR,
                            unsungColor = MaterialTheme.colorScheme.secondary,
                        ),
                        modifier = lineModifier,
                        fontSize = lineFontSize,
                        lineHeight = lineHeight,
                        fontFamily = fontSpec.fontFamily,
                        fontWeight = fontSpec.fontWeight,
                    )
                } else {
                    Text(
                        text = line.text,
                        modifier = lineModifier,
                        fontSize = lineFontSize,
                        lineHeight = lineHeight,
                        fontFamily = fontSpec.fontFamily,
                        fontWeight = fontSpec.fontWeight,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                    )
                }
            }
        }

        if (!followCurrent && currentIndex >= 0) {
            Button(
                onClick = { followCurrent = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.lyrics_back_to_current))
            }
        }
    }
}
