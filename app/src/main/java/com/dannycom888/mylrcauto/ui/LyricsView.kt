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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.WordTiming
import com.dannycom888.mylrcauto.settings.LyricsFontChoice
import com.dannycom888.mylrcauto.settings.SweepColorChoice
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
 * glowing colour, and the part already sung is overpainted in
 * [SWEEP_COLOR]. The gold edge does not jump character by
 * character: it fills the character being sung from its left edge
 * to its right edge as the character is sung, so the boundary
 * moves continuously with the playhead. Plain lines — every line
 * of a plain file — highlight whole, exactly as before.
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
 * [sweepColorChoice] is the karaoke sweep colour from Settings
 * (v1.0.35), mapped through [sweepColorFor]; both surfaces pass
 * the same value, so one choice colours both.
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
 * brightness one, so the swept part keeps its glow. Since v1.0.35
 * this is the GOLD entry of the Settings → 扫色颜色 choice (and its
 * default); the other two hues live in [sweepColorFor] with it.
 */
internal val SWEEP_COLOR: Color = Color(0xFFFFD54F)

/**
 * The sweep colour for a Settings [SweepColorChoice]: GOLD is
 * [SWEEP_COLOR] itself, so the default path is the exact colour
 * the sweep has had since v1.0.33. Pure, so the mapping is
 * unit-testable without a UI.
 */
internal fun sweepColorFor(choice: SweepColorChoice): Color = when (choice) {
    SweepColorChoice.GOLD -> SWEEP_COLOR
    SweepColorChoice.ORANGE -> Color(0xFFFF9800)
    SweepColorChoice.RED -> Color(0xFFFF5252)
}

/**
 * How far the karaoke sweep has filled one character that starts
 * at [startMs] and ends at [endMs], at playhead [positionMs]: 0
 * before it starts, 1 once it has ended, linear in between.
 *
 * A character with no positive span (endMs <= startMs — the last
 * fragment of a line that carries no end tag has no measured end)
 * has nothing to fill across: it reads 0 before its moment and 1
 * from its moment on, the whole-character behaviour such a
 * character had before the smooth fill.
 */
internal fun charFillProgress(startMs: Long, endMs: Long, positionMs: Long): Float =
    if (endMs <= startMs) {
        if (positionMs >= endMs) 1f else 0f
    } else {
        ((positionMs - startMs).toFloat() / (endMs - startMs).toFloat())
            .coerceIn(0f, 1f)
    }

/**
 * Where the karaoke sweep stands at [positionMs]: [sungChars]
 * leading characters are fully swept, and [currentProgress] is
 * the [charFillProgress] of the character being sung right now —
 * the character at index [sungChars]. When every character is
 * sung, [currentProgress] is 1 and there is no character left to
 * fill.
 *
 * A fragment's end is the next fragment's start; the trailing
 * empty fragment is the line's end marker, so the last sung
 * character fills across to it. Characters inside a
 * multi-character fragment fill one by one, the fragment's span
 * divided evenly between them — the same even-split assumption
 * the character count made before. Pure, so the sweep math is
 * unit-testable without a UI.
 */
internal data class SweepFill(val sungChars: Int, val currentProgress: Float)

internal fun sweepFill(words: List<WordTiming>, positionMs: Long): SweepFill {
    var sung = 0
    for (index in words.indices) {
        val segment = words[index]
        val length = segment.text.length
        if (length == 0) continue // the end marker carries no characters
        if (positionMs < segment.startMs) return SweepFill(sung, 0f)
        val endMs = words.getOrNull(index + 1)?.startMs ?: segment.startMs
        if (endMs <= segment.startMs || positionMs >= endMs) {
            sung += length
            continue
        }
        // Inside this fragment: fill its characters one by one.
        val span = (endMs - segment.startMs).toDouble()
        for (charIndex in 0 until length) {
            val charStart = segment.startMs + (span * charIndex / length).toLong()
            val charEnd = segment.startMs + (span * (charIndex + 1) / length).toLong()
            val progress = charFillProgress(charStart, charEnd, positionMs)
            if (progress >= 1f) {
                sung++
            } else {
                return SweepFill(sung, progress)
            }
        }
    }
    return SweepFill(sung, 1f)
}

/**
 * The clip that bounds the sweep's gold layer at [fill]: every
 * visual line above the one being sung is covered whole, and that
 * line is covered up to the fill boundary inside the character
 * being sung — its measured glyph box, cut at [fill]'s fraction.
 * Coordinates come from the base text's own layout, so the gold
 * layer (the same text, laid out identically) is cut exactly
 * where the base text's glyphs are.
 */
private fun sweepClipPath(layout: TextLayoutResult, fill: SweepFill): Path {
    val charIndex = fill.sungChars
    val glyph = layout.getBoundingBox(charIndex)
    val boundaryX = glyph.left + fill.currentProgress * glyph.width
    val lineIndex = layout.getLineForOffset(charIndex)
    val lineTop = layout.getLineTop(lineIndex)
    val path = Path()
    if (lineIndex > 0) {
        path.addRect(Rect(0f, 0f, layout.size.width.toFloat(), lineTop))
    }
    path.addRect(
        Rect(
            left = layout.getLineLeft(lineIndex),
            top = lineTop,
            right = boundaryX,
            bottom = layout.getLineBottom(lineIndex),
        ),
    )
    return path
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
    sweepColorChoice: SweepColorChoice = SweepColorChoice.GOLD,
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
                    // Karaoke sweep: two identical texts stacked.
                    // The base layer is the ordinary current line —
                    // the whole line in its glowing colour, exactly
                    // as v1.0.32 draws it, unsung part included. Over
                    // it, the same text in the sweep colour, clipped to
                    // the part already sung: characters fully sung
                    // sit inside the clip, and the character being
                    // sung is cut mid-glyph at its fill fraction, so
                    // the gold edge moves continuously with the
                    // playhead instead of jumping character by
                    // character. The sweep reads by hue against the
                    // glow — never by dimming anything. Only the
                    // current line of an enhanced file takes this
                    // path; every other line renders exactly as
                    // before.
                    var lineLayout by remember(line) {
                        mutableStateOf<TextLayoutResult?>(null)
                    }
                    val fill = sweepFill(line.words, positionMs)
                    Box(modifier = lineModifier) {
                        Text(
                            text = line.text,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = lineFontSize,
                            lineHeight = lineHeight,
                            fontFamily = fontSpec.fontFamily,
                            fontWeight = fontSpec.fontWeight,
                            color = MaterialTheme.colorScheme.secondary,
                            onTextLayout = { lineLayout = it },
                        )
                        if (fill.sungChars > 0 || fill.currentProgress > 0f) {
                            Text(
                                text = line.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawWithContent {
                                        val contentScope = this
                                        val layout = lineLayout
                                        when {
                                            // No layout yet (the first
                                            // frame): draw nothing
                                            // rather than flash the
                                            // whole line gold for a
                                            // frame.
                                            layout == null -> Unit
                                            fill.sungChars >= line.text.length ->
                                                drawContent()
                                            else -> clipPath(
                                                sweepClipPath(layout, fill),
                                            ) {
                                                with(contentScope) { drawContent() }
                                            }
                                        }
                                    },
                                fontSize = lineFontSize,
                                lineHeight = lineHeight,
                                fontFamily = fontSpec.fontFamily,
                                fontWeight = fontSpec.fontWeight,
                                color = sweepColorFor(sweepColorChoice),
                            )
                        }
                    }
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
