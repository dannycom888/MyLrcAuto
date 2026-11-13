package com.dannycom888.mylrcauto.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricsState
import com.dannycom888.mylrcauto.ui.LyricsView
import kotlinx.coroutines.delay

/**
 * The lyrics screen, drawn on the car's own surface.
 *
 * This is the phone's renderer, not an imitation of it: [LyricsView] is the
 * same composable the phone draws, so the list, the current-line highlight
 * and the auto-scroll arrive for free and cannot drift out of step with the
 * phone's version.
 *
 * **Nothing here is interactive, by construction.** A car host does not
 * deliver touches to the surface as ordinary events — it sends map gestures
 * through `SurfaceCallback` instead — so a button drawn here would be a
 * button that does nothing. Every control therefore lives in the host's own
 * action strip, where the targets are big, the host places them, and the
 * driver already knows where they are. The seek handler is still wired
 * through to [LyricsView] rather than dropped, so the same composable works
 * unchanged if a host ever does deliver a tap.
 */
@Composable
fun CarLyricsContent(
    container: AppContainer,
    positionMsProvider: () -> Long,
    onSeek: (Long) -> Unit,
    surfaceState: CarSurfaceState,
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    density: Float,
    modifier: Modifier = Modifier,
) {
    val snapshot by container.media.snapshot.collectAsStateWithLifecycle()
    val lyricsState by container.lyrics.state.collectAsStateWithLifecycle()
    val permitted by container.media.permissionGranted.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // The same decision the templated screen makes, from the same function,
    // so the two car screens cannot disagree about whether there is anything
    // to show. Drawing straight from a Loaded state instead let two cases
    // through that it already had answers for: untimed lyrics, which arrive
    // as a document and would sit here as a frozen page nobody can scroll,
    // and missing notification access, which showed as "nothing playing" —
    // true, and permanently so, with no hint why.
    val glance = CarGlance.of(
        state = lyricsState,
        hasTrack = snapshot.hasTrack,
        permissionGranted = permitted,
        positionMs = snapshot.playback.currentMs(),
    )
    val document = (lyricsState as? LyricsState.Loaded)?.document
        ?.takeIf { glance is CarGlance.Now }

    // The 500 ms ticker, same as the phone screen: drives recomposition, so
    // LyricsView's positionMsProvider reads a fresh extrapolated playhead.
    // The car surface otherwise only recomposes when a flow emits, which is
    // why the big lyrics panel could freeze while the template card — which
    // has its own tick loop — kept updating its line.
    //
    // The tick state is deliberately NOT keyed on the document. It was
    // (v1.0.5–v1.0.13): `remember(document)` returned a new state object
    // every time the lyrics loaded or the song changed, while the
    // LaunchedEffect below — keyed on Unit, so never restarted — kept
    // incrementing the OLD one. The composition then read a tick stuck at
    // 0, `playheadMs` never recomputed, the current line never advanced,
    // and the list stopped scrolling (and the highlight stuck) from the
    // moment the first song's lyrics arrived. A heartbeat's value is
    // irrelevant — only that it changes — so one stable state object for
    // the composition's lifetime is the whole fix.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    // Refreshed on either driver: the local tick above, or the snapshot
    // the repository re-emits every 500 ms while playing. Either one
    // stalling must not freeze the playhead again — a stall used to leave
    // the list still for a minute and then jump a long way when the
    // position finally updated, instead of following line by line.
    // positionMsProvider reads the live snapshot, so the value is fresh
    // whichever driver recomputes it.
    val playheadMs = remember(tick, snapshot) { positionMsProvider() }

    // The status is the glance itself. There is no "no lyrics" text
    // anywhere on this screen any more: it flashed on every song
    // switch (the lookup had not finished yet, or the template build
    // had failed over to it) and then the lyrics arrived, so the words
    // themselves were the bug, not the lookup. When there are no lyrics
    // to show — still looking, or a lookup that completed with no
    // match — the lyrics area stays empty, with the cover and the title
    // still showing; nothing ever says "no lyrics".
    val statusGlance = glance

    // The host's visible area: the unobstructed rectangle the lyrics live
    // in. The surface itself is bigger — the visible area's left edge is
    // where the pane column ends, and the surface extends left of it (the
    // car's own photo proves it: the content begins ~60% across, so the
    // surface's left ~60% is real surface). The pane box sits at the top
    // of that column; below it is drawable surface, and the host draws
    // nothing there — Melisma's background fills the whole screen
    // including below its pane. So the small cover goes there (see
    // belowPaneCoverPlacement), and the lyrics take the visible area's
    // full width, with no cover column beside them.
    val area by surfaceState.visibleArea.collectAsStateWithLifecycle()
    // Inside the host's own idea of what is visible: it draws a header
    // and an action strip over this surface and says where, so the words
    // go in the gap rather than under the furniture.
    val contentPadding = insetsFor(area, surfaceWidthPx, surfaceHeightPx, density)
    val coverPlacement = belowPaneCoverPlacement(
        areaLeftPx = area?.left,
        surfaceHeightPx = surfaceHeightPx,
        density = density,
    )

    // Background: always all black (the user asked for only black in
    // v1.0.15 — the blurred-cover option and its slider are gone). The
    // backdrop is the app's own surface only: the host-drawn pane
    // (top-left box) is the car's UI and is never touched.
    val cover = snapshot.artwork?.takeUnless { it.isRecycled }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        )
        // The small cover below the pane, in the pane column: the sharp
        // cover (or the M-note icon when the player publishes none),
        // 200dp, its top edge below the pane's midline with clear space
        // between the pane and the cover. The title and artist stay in
        // the pane itself, so they are not repeated here. A soft glow
        // runs around the cover's edge, in the theme's secondary colour
        // — the same colour as the glowing current lyric line. Shown
        // only while a track is playing; without a visible area yet
        // there is no column to put it in, so it stays hidden.
        if (snapshot.hasTrack && coverPlacement != null) {
            val smallCover = cover?.asImageBitmap()
                ?: ImageBitmap.imageResource(R.drawable.ic_launcher_foreground)
            val glow = MaterialTheme.colorScheme.secondary
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = coverPlacement.leftDp.dp, y = coverPlacement.topDp.dp)
                    .size(coverPlacement.sizeDp.dp)
                    .shadow(
                        elevation = COVER_GLOW_DP.dp,
                        shape = RoundedCornerShape(8.dp),
                        clip = false,
                        ambientColor = glow.copy(alpha = 0.55f),
                        spotColor = glow.copy(alpha = 0.55f),
                    ),
            ) {
                Image(
                    bitmap = smallCover,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // v1.0.18: the lyrics draw only once the host has reported
            // the visible area. Before that the padding is zero and the
            // list is laid out from the surface's left edge — under the
            // pane column, on the left of the screen — and a second
            // later, when the area arrives, the whole screen shifts
            // right into place. A first frame drawn edge to edge and
            // corrected a moment later was judged better than one not
            // drawn (v1.0.14); the car test proved the opposite — the
            // shift itself is the bug. Until the area arrives the
            // surface stays black (the template's pane still shows
            // what is playing), and the first lyrics frame is already
            // in the right place.
            if (area != null) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (document != null) {
                        LyricsView(
                            document = document,
                            positionMsProvider = { playheadMs },
                            onSeek = onSeek,
                            // The car surface is not touchable, so following must never
                            // switch itself off: there is no "back to current line"
                            // button the driver could tap to resume it.
                            followAlways = true,
                            // Same size setting as the phone: one slider sizes both.
                            fontSizeSp = settings.lyricsFontSizeSp,
                            // Same font choice as the phone: the setting picks the
                            // font on both surfaces at once.
                            lyricsFont = settings.lyricsFont,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CarStatus(glance = statusGlance)
                    }
                }
            }
        }
    }
}

/**
 * The cover's glow: the shadow elevation around the cover's edge, in
 * dp. A soft halo on the black background, in the same colour as the
 * glowing current lyric line.
 */
private const val COVER_GLOW_DP = 20f

/**
 * One or two lines, because that is all a driver should be given to read.
 *
 * The templated screen says the same things at more length; this is the
 * version for a surface that is mostly lyrics, where a paragraph would be
 * unreadable anyway.
 */
@Composable
private fun CarStatus(glance: CarGlance) {
    // No lyrics to show is an empty area, never a message: the "no
    // lyrics" text flashed on every song switch and was deleted. The
    // cover and the title still show above this; this is only the
    // lyrics area, blank until the words arrive.
    if (glance is CarGlance.None) return
    val (title, body) = when (glance) {
        CarGlance.Silent ->
            stringResource(R.string.car_silent) to stringResource(R.string.car_silent_body)
        CarGlance.Looking ->
            stringResource(R.string.car_looking) to null
        is CarGlance.None ->
            // Unreachable: the early return above handles None. Kept
            // only so the when stays exhaustive.
            stringResource(R.string.car_looking) to null
        CarGlance.Untimed ->
            stringResource(R.string.car_untimed) to stringResource(R.string.car_untimed_body)
        CarGlance.NoPermission ->
            stringResource(R.string.car_permission) to stringResource(R.string.car_permission_body)
        // Reached only if the caller drew this instead of the lyrics, which it does not.
        is CarGlance.Now -> stringResource(R.string.car_looking) to null
    }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = body,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
