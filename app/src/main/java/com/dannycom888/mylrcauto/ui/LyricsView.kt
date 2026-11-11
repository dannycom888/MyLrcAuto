package com.dannycom888.mylrcauto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
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
 * Auto-scroll does not fight the reader: once the user drags the list, the
 * list stops following and a "back to current line" button appears; tapping
 * it resumes following. Tapping any line seeks to it via [onSeek].
 *
 * [followAlways] is for surfaces that are not touchable (the car): a drag
 * can never switch following off there, and there is no button to resume it
 * with, so following simply stays on.
 *
 * An empty document renders nothing; the caller owns the empty state.
 */
@Composable
fun LyricsView(
    document: LyricsDocument,
    positionMsProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    followAlways: Boolean = false,
) {
    val listState = rememberLazyListState()
    val currentIndex = document.indexAt(positionMsProvider())

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
            val target = (currentIndex - 2).coerceAtLeast(0)
                .coerceAtMost((document.lines.size - 1).coerceAtLeast(0))
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
                Text(
                    text = line.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = if (isCurrent) 22.sp else 17.sp,
                    lineHeight = if (isCurrent) 30.sp else 24.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    },
                )
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
