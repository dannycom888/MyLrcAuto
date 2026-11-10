package com.dannycom888.mylrcauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricsState
import kotlinx.coroutines.delay

/**
 * Transport icons as vector path data.
 *
 * No icons artifact is on the dependency list, so — following the Melisma
 * pattern — the four icons this screen needs are built from the standard
 * Material path data instead of pulling in `material-icons-extended`.
 */
private fun transportIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(androidx.compose.ui.graphics.Color.White),
        )
    }.build()

private val PlayIcon by lazy { transportIcon("PlayArrow", "M8,5v14l11,-7z") }
private val PauseIcon by lazy { transportIcon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
private val SkipNextIcon by lazy {
    transportIcon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
}
private val SkipPreviousIcon by lazy {
    transportIcon("SkipPrevious", "M6,6h2v12H6zm3.5,6l8.5,6V6z")
}
private val SettingsIcon by lazy {
    transportIcon(
        "Settings",
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.06,-0.94l2.03," +
            "-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59," +
            "-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24," +
            "-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 " +
            "7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 " +
            "2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.06,0.94l-2.03,1.58c-0.18," +
            "0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5," +
            "0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44," +
            "-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 " +
            "0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61l-2.01,-1.58zM12," +
            "15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z",
    )
}

/** The phone screen: header, status rows, lyrics list, transport. */
@Composable
fun LyricsScreen(container: AppContainer) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(container = container, onBack = { showSettings = false })
        return
    }

    val context = LocalContext.current
    val snapshot by container.media.snapshot.collectAsStateWithLifecycle()
    val permissionGranted by container.media.permissionGranted.collectAsStateWithLifecycle()
    val lyricsState by container.lyrics.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val track = snapshot.track

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Top bar: app title + settings entry point.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = SettingsIcon,
                    contentDescription = stringResource(R.string.settings_title),
                )
            }
        }

        // Header: current track, or "Nothing playing".
        Text(
            text = if (snapshot.hasTrack) track!!.title else stringResource(R.string.player_nothing_playing),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (snapshot.hasTrack) {
            Text(
                text = track!!.artist.ifBlank { stringResource(R.string.player_unknown_artist) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            snapshot.sourceLabel?.let { label ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Body, in priority order: permission / folder / looking / no lyrics / loaded.
        when {
            !permissionGranted -> {
                StatusBlock(
                    title = stringResource(R.string.status_no_permission_title),
                    body = stringResource(R.string.status_no_permission_body),
                    buttonText = stringResource(R.string.status_no_permission_button),
                    onButtonClick = {
                        context.startActivity(container.media.notificationAccessIntent())
                    },
                )
            }
            settings.localLyricsFolderUri == null -> {
                StatusBlock(
                    title = stringResource(R.string.status_no_folder_title),
                    body = stringResource(R.string.status_no_folder_body),
                    buttonText = stringResource(R.string.status_no_folder_button),
                    onButtonClick = { showSettings = true },
                )
            }
            lyricsState is LyricsState.Looking -> {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(R.string.status_looking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            lyricsState is LyricsState.None -> {
                StatusBlock(
                    title = stringResource(R.string.status_no_lyrics_title),
                    body = stringResource(R.string.status_no_lyrics_body),
                )
            }
            lyricsState is LyricsState.Loaded -> {
                val document = (lyricsState as LyricsState.Loaded).document
                // 500 ms ticker: drives recomposition, so LyricsView's
                // positionMsProvider reads a fresh extrapolated playhead.
                var tick by remember(document) { mutableLongStateOf(0L) }
                LaunchedEffect(document) {
                    while (true) {
                        delay(500)
                        tick++
                    }
                }
                val playheadMs = remember(tick, snapshot) { snapshot.playback.currentMs() }
                LyricsView(
                    document = document,
                    positionMsProvider = { playheadMs },
                    onSeek = { positionMs -> container.media.seekTo(positionMs) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = document.sourceName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            // LyricsState.Idle: no track, nothing more to show.
            else -> {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Transport row, only when the player accepts at least one command,
        // and only the actions it actually supports.
        if (snapshot.canControl) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (snapshot.transport.skipPrevious) {
                    IconButton(onClick = { container.media.skipPrevious() }) {
                        Icon(
                            imageVector = SkipPreviousIcon,
                            contentDescription = stringResource(R.string.transport_previous),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                if (snapshot.transport.playPause) {
                    IconButton(onClick = { container.media.togglePlayPause() }) {
                        Icon(
                            imageVector = if (snapshot.playback.isPlaying) PauseIcon else PlayIcon,
                            contentDescription = stringResource(
                                if (snapshot.playback.isPlaying) R.string.car_pause else R.string.car_play,
                            ),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                if (snapshot.transport.skipNext) {
                    IconButton(onClick = { container.media.skipNext() }) {
                        Icon(
                            imageVector = SkipNextIcon,
                            contentDescription = stringResource(R.string.transport_next),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A status row: bold title, body text, and an optional action button. */
@Composable
private fun StatusBlock(
    title: String,
    body: String,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttonText != null && onButtonClick != null) {
            Button(onClick = onButtonClick) {
                Text(text = buttonText)
            }
        }
    }
}
