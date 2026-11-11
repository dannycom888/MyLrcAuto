package com.dannycom888.mylrcauto.car

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

    // 500 ms ticker, same as the phone screen: drives recomposition, so
    // LyricsView's positionMsProvider reads a fresh extrapolated playhead.
    // The car surface otherwise only recomposes when a flow emits, which is
    // why the big lyrics panel could freeze while the template card — which
    // has its own tick loop — kept updating its line. The tick runs whether
    // or not a document is shown: it is also what lets the Looking timeout
    // below flip the status text, since nothing else recomposes while the
    // lookup is still running.
    var tick by remember(document) { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val playheadMs = remember(tick) { positionMsProvider() }

    // Past the timeout, the surface says "no lyrics" instead of "looking"
    // — the same answer the fallback template gives after the same wait
    // (CarLyricsScreen). Without this the surface could show "looking"
    // forever while the template next to it had already given up.
    var lookingSinceMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(glance is CarGlance.Looking) {
        lookingSinceMs = if (glance is CarGlance.Looking) {
            System.currentTimeMillis()
        } else {
            0L
        }
    }
    val lookingTimedOut = glance is CarGlance.Looking && lookingSinceMs != 0L &&
        System.currentTimeMillis() - lookingSinceMs > CarGlance.LOOKING_TIMEOUT_MS
    val statusGlance = if (lookingTimedOut) CarGlance.None else glance

    Row(
        modifier
            .fillMaxSize()
            .background(Color(0xFF101820)),
    ) {
        // The cover lives here, in a column on the left of the VISIBLE
        // area, beside the lyrics — never above them, so the list keeps
        // its full height (see CoverColumn). Shown whenever a track is
        // playing.
        //
        // It cannot go BELOW the template pane (the top-left box): the
        // region under the pane is not the app's surface. The host grants
        // the surface, then reports the unobstructed part as the visible
        // area — and on this car that rectangle starts to the RIGHT of the
        // pane column (the car's own photo proves it: the app's content
        // begins ~60% across the screen, with the whole left strip below
        // the pane empty). The pane column is host-reserved space for the
        // full height, so there is nowhere below the pane to draw. The
        // narrower column (v1.0.10) is the app-side answer instead.
        if (snapshot.hasTrack) {
            CoverColumn(
                artwork = snapshot.artwork,
                title = snapshot.track?.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.app_name),
                artist = snapshot.track?.artist.orEmpty(),
            )
        }
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
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CarStatus(glance = statusGlance)
            }
        }
    }
}

/**
 * The current track's cover, title and artist, in a column on the left of
 * the surface, beside the lyrics.
 *
 * The cover lives here, not in the template pane: this car's system ignores
 * Pane.setImage in the MapWithContentTemplate content pane (title and
 * artist show, no image at all), while the surface is drawn by the app
 * itself, so a cover drawn here is guaranteed visible. It sits beside the
 * lyrics, never above them, so the list keeps its full height. The cover
 * comes from the player's own metadata (see MediaSessionRepository); a
 * recycled bitmap is treated as no cover, and the app's M-note icon stands
 * in when the player publishes none.
 *
 * Narrow (v1.0.10): the column is 172dp with a 140dp cover, down from
 * 212dp / 180dp — the pane on this car cannot be moved, so giving the
 * lyrics ~40dp more width is the app-side answer to "more room for the
 * lyrics".
 */
@Composable
private fun CoverColumn(
    artwork: Bitmap?,
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(172.dp)
            .fillMaxHeight()
            .padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
    ) {
        val cover = artwork?.takeUnless { it.isRecycled }?.asImageBitmap()
            ?: ImageBitmap.imageResource(R.drawable.ic_launcher_foreground)
        Image(
            bitmap = cover,
            contentDescription = null,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = artist,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One or two lines, because that is all a driver should be given to read.
 *
 * The templated screen says the same things at more length; this is the
 * version for a surface that is mostly lyrics, where a paragraph would be
 * unreadable anyway.
 */
@Composable
private fun CarStatus(glance: CarGlance) {
    val (title, body) = when (glance) {
        CarGlance.Silent ->
            stringResource(R.string.car_silent) to stringResource(R.string.car_silent_body)
        CarGlance.Looking ->
            stringResource(R.string.car_looking) to null
        CarGlance.None ->
            stringResource(R.string.car_none) to stringResource(R.string.car_none_body)
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
