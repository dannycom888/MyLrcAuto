package com.dannycom888.mylrcauto.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF101820)),
    ) {
        if (document != null) {
            LyricsView(
                document = document,
                positionMsProvider = positionMsProvider,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CarStatus(glance = glance)
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
