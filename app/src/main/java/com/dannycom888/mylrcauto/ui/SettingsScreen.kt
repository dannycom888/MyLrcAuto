package com.dannycom888.mylrcauto.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.car.CarGlanceKind
import com.dannycom888.mylrcauto.car.CarLastErrorRecord
import com.dannycom888.mylrcauto.car.CarPaneKind
import com.dannycom888.mylrcauto.car.CarRenderRecord
import com.dannycom888.mylrcauto.car.CarSurfaceBlock
import com.dannycom888.mylrcauto.car.LaunchDecision
import com.dannycom888.mylrcauto.car.LaunchEntry
import com.dannycom888.mylrcauto.car.LaunchRecord
import com.dannycom888.mylrcauto.car.formatCarLastError
import com.dannycom888.mylrcauto.car.formatLaunchTimestamp
import com.dannycom888.mylrcauto.lyrics.DiagValue
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LookupMatchStep
import com.dannycom888.mylrcauto.lyrics.LookupOutcome
import com.dannycom888.mylrcauto.lyrics.LookupTrace
import com.dannycom888.mylrcauto.lyrics.LyricsDiagLabel
import com.dannycom888.mylrcauto.lyrics.LyricsIndexDiag
import com.dannycom888.mylrcauto.lyrics.LyricsIndexState
import com.dannycom888.mylrcauto.lyrics.lyricsDiagRows
import com.dannycom888.mylrcauto.media.MirrorRepublishRecord
import com.dannycom888.mylrcauto.media.MirrorRepublishReason
import com.dannycom888.mylrcauto.media.formatMirrorRepublish
import com.dannycom888.mylrcauto.settings.AppLanguage
import com.dannycom888.mylrcauto.settings.LyricsFontChoice
import com.dannycom888.mylrcauto.settings.SettingsStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BackIcon by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser()
                .parsePathString("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
                .toNodes(),
            fill = SolidColor(androidx.compose.ui.graphics.Color.White),
        )
    }.build()
}

/** Language choice → its label string. */
@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.CHINESE -> R.string.settings_language_chinese
        AppLanguage.ENGLISH -> R.string.settings_language_english
    },
)

/** The app's own versionName, read from the package so it updates every release. */
private fun appVersionName(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: ""

/** One label/value row of a diagnostics block. */
@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Weighted (v1.0.27): an unweighted value is measured
        // first and can squeeze itself into a narrow vertical
        // strip — the long codepoints hex did exactly that. With
        // both sides weighted, a long value wraps inside its own
        // column instead.
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.5f),
        )
    }
}

/**
 * The last car launch, as the session recorded it: what the head
 * unit sent (action / categories / extras / flags), the state the
 * decision looked at, and what it decided. This is the evidence
 * that settles whether a card tap and an icon tap arrive
 * differently on the user's car — screenshot it after each tap.
 */
@Composable
private fun LaunchDiagnosticsSection(record: LaunchRecord?) {
    Text(
        text = stringResource(R.string.settings_diag_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (record == null) {
        Text(
            text = stringResource(R.string.settings_diag_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        return
    }
    val none = stringResource(R.string.settings_diag_empty_value)
    val yes = stringResource(R.string.settings_diag_yes)
    val no = stringResource(R.string.settings_diag_no)
    fun joined(values: Set<String>): String =
        values.sorted().joinToString(", ").ifEmpty { none }
    fun yesNo(value: Boolean): String = if (value) yes else no
    Spacer(modifier = Modifier.height(4.dp))
    DiagRow(
        stringResource(R.string.settings_diag_time),
        formatLaunchTimestamp(record.timestampMs),
    )
    DiagRow(
        stringResource(R.string.settings_diag_entry),
        stringResource(
            when (record.entry) {
                LaunchEntry.CREATE_SCREEN -> R.string.settings_diag_entry_create
                LaunchEntry.NEW_INTENT -> R.string.settings_diag_entry_intent
            },
        ),
    )
    DiagRow(
        stringResource(R.string.settings_diag_action),
        record.intentAction ?: none,
    )
    DiagRow(
        stringResource(R.string.settings_diag_categories),
        joined(record.intentCategories),
    )
    DiagRow(
        stringResource(R.string.settings_diag_extras),
        joined(record.intentExtrasKeys),
    )
    DiagRow(
        stringResource(R.string.settings_diag_flags),
        record.intentFlags.toString(),
    )
    DiagRow(
        stringResource(R.string.settings_diag_mirror),
        yesNo(record.mirrorAlreadyActive),
    )
    DiagRow(
        stringResource(R.string.settings_diag_shown),
        yesNo(record.bigScreenShownThisConnection),
    )
    DiagRow(
        stringResource(R.string.settings_diag_decision),
        stringResource(
            when (record.decision) {
                LaunchDecision.SHOW_BIG_SCREEN -> R.string.settings_diag_decision_big
                LaunchDecision.FINISH_TO_DASHBOARD -> R.string.settings_diag_decision_dash
            },
        ),
    )
}

/**
 * The most recent big-screen render, as the car screen recorded
 * it (v1.0.27): which pane it returned, what the lyrics side had,
 * the surface facts the choice was made from, and the exception
 * when the fallback fired. This is the evidence that separates
 * the three title-and-artist-only shapes a broken big screen can
 * be — screenshot it after a car run that looks wrong. The final
 * row is the sticky last entry-path error (v1.0.30): it survives
 * the healthy renders that overwrite everything above it. Below
 * it, the last full mirror re-publish (v1.0.32): after a
 * host-side teardown, it is the proof the recovery fired.
 */
@Composable
private fun CarRenderDiagnosticsSection(
    record: CarRenderRecord?,
    lastError: CarLastErrorRecord?,
    mirrorRepublish: MirrorRepublishRecord?,
) {
    Text(
        text = stringResource(R.string.settings_renderdiag_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (record == null) {
        Text(
            text = stringResource(R.string.settings_renderdiag_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // The last-error row shows even with no render record:
        // it is sticky across process restarts, and a failure
        // that killed the process before any render is exactly
        // the one that must not be hidden by the empty state.
        if (lastError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            LastErrorRow(lastError)
        }
        // Same reasoning as the last-error row above: a mirror
        // re-publish recorded before any render — a service
        // restart is exactly that shape — must not be hidden by
        // the empty state either.
        if (mirrorRepublish != null) {
            if (lastError == null) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            MirrorRepublishRow(mirrorRepublish)
        }
        return
    }
    val none = stringResource(R.string.settings_diag_empty_value)
    val yes = stringResource(R.string.settings_diag_yes)
    val no = stringResource(R.string.settings_diag_no)
    fun yesNo(value: Boolean): String = if (value) yes else no
    Spacer(modifier = Modifier.height(4.dp))
    DiagRow(
        stringResource(R.string.settings_diag_time),
        formatLaunchTimestamp(record.timestampMs),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_pane),
        stringResource(
            when (record.pane) {
                CarPaneKind.ON_SURFACE -> R.string.settings_renderdiag_pane_surface
                CarPaneKind.LYRICS -> R.string.settings_renderdiag_pane_lyrics
                CarPaneKind.NEUTRAL -> R.string.settings_renderdiag_pane_neutral
                CarPaneKind.MESSAGE -> R.string.settings_renderdiag_pane_message
                CarPaneKind.EMERGENCY -> R.string.settings_renderdiag_pane_emergency
            },
        ),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_glance),
        stringResource(
            when (record.glance) {
                CarGlanceKind.NOW -> R.string.settings_renderdiag_glance_now
                CarGlanceKind.LOOKING -> R.string.settings_renderdiag_glance_looking
                CarGlanceKind.NONE -> R.string.settings_renderdiag_glance_none
                CarGlanceKind.SILENT -> R.string.settings_renderdiag_glance_silent
                CarGlanceKind.UNTIMED -> R.string.settings_renderdiag_glance_untimed
                CarGlanceKind.NO_PERMISSION -> R.string.settings_renderdiag_glance_permission
            },
        ),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_lines),
        record.lineCount.toString(),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_surface),
        yesNo(record.surfaceLive),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_surface_width),
        record.surfaceWidthDp?.let { "${it.roundToInt()} dp" } ?: none,
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_callback_registered),
        yesNo(record.surfaceCallbackRegistered),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_callback_fired),
        yesNo(record.surfaceCallbackFired),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_container),
        if (record.surfaceContainerWidthPx != null && record.surfaceContainerHeightPx != null) {
            "${record.surfaceContainerWidthPx} × ${record.surfaceContainerHeightPx} px"
        } else {
            none
        },
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_blocked),
        stringResource(
            when (record.surfaceBlock) {
                CarSurfaceBlock.ACTIVE -> R.string.settings_renderdiag_blocked_active
                CarSurfaceBlock.API_TOO_LOW -> R.string.settings_renderdiag_blocked_api
                CarSurfaceBlock.NOT_REGISTERED ->
                    R.string.settings_renderdiag_blocked_not_registered
                CarSurfaceBlock.NEVER_OFFERED ->
                    R.string.settings_renderdiag_blocked_never_offered
                CarSurfaceBlock.OFFER_FAILED ->
                    R.string.settings_renderdiag_blocked_offer_failed
                CarSurfaceBlock.OFFER_ENDED -> R.string.settings_renderdiag_blocked_offer_ended
                CarSurfaceBlock.NOT_FOREGROUND ->
                    R.string.settings_renderdiag_blocked_not_foreground
                CarSurfaceBlock.AREA_EMPTY -> R.string.settings_renderdiag_blocked_area_empty
                CarSurfaceBlock.TOO_NARROW -> R.string.settings_renderdiag_blocked_too_narrow
                CarSurfaceBlock.UNKNOWN -> R.string.settings_renderdiag_blocked_unknown
            },
        ) + (record.surfaceFailure?.let { ": $it" } ?: ""),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_area),
        stringResource(
            when {
                !record.visibleAreaReported -> R.string.settings_renderdiag_area_none
                record.visibleAreaEmpty -> R.string.settings_renderdiag_area_empty
                else -> R.string.settings_renderdiag_area_ok
            },
        ),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_started),
        yesNo(record.screenStarted),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_api),
        record.apiLevel.toString(),
    )
    DiagRow(
        stringResource(R.string.settings_renderdiag_error),
        when {
            record.errorClass == null -> none
            record.errorMessage == null -> record.errorClass
            else -> "${record.errorClass}: ${record.errorMessage}"
        },
    )
    if (lastError != null) {
        LastErrorRow(lastError)
    } else {
        DiagRow(
            stringResource(R.string.settings_renderdiag_last_error),
            none,
        )
    }
    if (mirrorRepublish != null) {
        MirrorRepublishRow(mirrorRepublish)
    } else {
        DiagRow(
            stringResource(R.string.settings_renderdiag_mirror_republish),
            none,
        )
    }
}

/**
 * The sticky last car entry-path error (v1.0.30): time, callback
 * site, exception. Unlike the render record above it — which the
 * next healthy render overwrites — this one survives success and
 * process restarts, so the transient failure that flashed the
 * host's error screen and recovered leaves its name here.
 */
@Composable
private fun LastErrorRow(lastError: CarLastErrorRecord) {
    DiagRow(
        stringResource(R.string.settings_renderdiag_last_error),
        formatCarLastError(lastError, formatLaunchTimestamp(lastError.timestampMs)),
    )
}

/**
 * The most recent full re-publish of the mirror media session
 * (v1.0.32): when it happened and what triggered it — a car
 * session starting, or the mirror service itself restarting.
 * The taskbar widget's song comes back through that publish
 * after a host-side teardown, so after a bad run this row is
 * the check that the recovery actually fired.
 */
@Composable
private fun MirrorRepublishRow(republish: MirrorRepublishRecord) {
    DiagRow(
        stringResource(R.string.settings_renderdiag_mirror_republish),
        formatMirrorRepublish(
            republish,
            formatLaunchTimestamp(republish.timestampMs),
            stringResource(
                when (republish.reason) {
                    MirrorRepublishReason.CAR_SESSION_START ->
                        R.string.settings_renderdiag_republish_car_session
                    MirrorRepublishReason.SERVICE_START ->
                        R.string.settings_renderdiag_republish_service_start
                },
            ),
        ),
    )
}

/** Label string for one lyrics-diagnostics row. */
private fun lyricsDiagLabelRes(label: LyricsDiagLabel): Int = when (label) {
    LyricsDiagLabel.INDEX_STATE -> R.string.settings_lrcdiag_index_state
    LyricsDiagLabel.INDEX_COUNT -> R.string.settings_lrcdiag_index_count
    LyricsDiagLabel.INDEX_AUDIO_COUNT -> R.string.settings_lrcdiag_index_audio_count
    LyricsDiagLabel.INDEX_DROPPED -> R.string.settings_lrcdiag_index_dropped
    LyricsDiagLabel.LOOKUP_TIME -> R.string.settings_diag_time
    LyricsDiagLabel.TRACK -> R.string.settings_lrcdiag_track
    LyricsDiagLabel.TITLE_CODEPOINTS -> R.string.settings_lrcdiag_codepoints
    LyricsDiagLabel.SOURCE -> R.string.settings_lrcdiag_source
    LyricsDiagLabel.AUDIO_FOUND -> R.string.settings_lrcdiag_audio_found
    LyricsDiagLabel.SAME_STEM -> R.string.settings_lrcdiag_same_stem
    LyricsDiagLabel.TITLE_CHECK -> R.string.settings_lrcdiag_title_check
    LyricsDiagLabel.FALLBACK_MATCHES -> R.string.settings_lrcdiag_fallback
    LyricsDiagLabel.RAW_PROBE -> R.string.settings_lrcdiag_probe
    LyricsDiagLabel.CLOSEST_NAMES -> R.string.settings_lrcdiag_closest
    LyricsDiagLabel.CLOSEST_NAME_CODEPOINTS -> R.string.settings_lrcdiag_closest_codepoints
    LyricsDiagLabel.WINNER_FILE -> R.string.settings_lrcdiag_winner
    LyricsDiagLabel.MATCH_STEP -> R.string.settings_lrcdiag_match_step
    LyricsDiagLabel.PARSED_LINES -> R.string.settings_lrcdiag_parsed_lines
    LyricsDiagLabel.READ_ISSUES -> R.string.settings_lrcdiag_read_issues
    LyricsDiagLabel.OUTCOME -> R.string.settings_lrcdiag_outcome
}

/** Label string for an index state. */
private fun lyricsIndexStateRes(state: LyricsIndexState): Int = when (state) {
    LyricsIndexState.NO_FOLDER -> R.string.settings_lrcdiag_state_no_folder
    LyricsIndexState.BUILDING -> R.string.settings_lrcdiag_state_building
    LyricsIndexState.READY -> R.string.settings_lrcdiag_state_ready
    LyricsIndexState.UNREADABLE -> R.string.settings_lrcdiag_state_unreadable
}

/** Label string for a lookup outcome. */
private fun lookupOutcomeRes(outcome: LookupOutcome): Int = when (outcome) {
    LookupOutcome.FOUND_SAME_STEM -> R.string.settings_lrcdiag_outcome_stem
    LookupOutcome.FOUND_METADATA -> R.string.settings_lrcdiag_outcome_meta
    LookupOutcome.NOT_FOUND -> R.string.settings_lrcdiag_outcome_not_found
    LookupOutcome.PARSE_EMPTY -> R.string.settings_lrcdiag_outcome_parse_empty
    LookupOutcome.READ_FAILED -> R.string.settings_lrcdiag_outcome_read_failed
    LookupOutcome.ERROR -> R.string.settings_lrcdiag_outcome_error
}

/** Label string for the step that produced a lookup's document. */
private fun lookupMatchStepRes(step: LookupMatchStep): Int =
    when (step) {
        LookupMatchStep.AUDIO_WITH_ARTIST ->
            R.string.settings_lrcdiag_step_audio_artist
        LookupMatchStep.AUDIO_TITLE_ONLY ->
            R.string.settings_lrcdiag_step_audio_title
        LookupMatchStep.NAME_WITH_ARTIST ->
            R.string.settings_lrcdiag_step_name_artist
        LookupMatchStep.NAME_TITLE_ONLY ->
            R.string.settings_lrcdiag_step_name_title
        LookupMatchStep.FUZZY ->
            R.string.settings_lrcdiag_step_fuzzy
    }

/**
 * The lyrics index state and the most recent lookup's trace
 * (v1.0.22): what the player reported for the track, and which
 * step of the lookup failed when the lyrics area is empty. The
 * rows come from the pure [lyricsDiagRows]; this composable only
 * localizes them. Screenshot it next to a song that shows no
 * lyrics — the trace names the failing step.
 */
@Composable
private fun LyricsDiagnosticsSection(indexDiag: LyricsIndexDiag, lookup: LookupTrace?) {
    Text(
        text = stringResource(R.string.settings_lrcdiag_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (lookup == null) {
        Text(
            text = stringResource(R.string.settings_lrcdiag_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    val yes = stringResource(R.string.settings_diag_yes)
    val no = stringResource(R.string.settings_diag_no)
    for (row in lyricsDiagRows(indexDiag, lookup)) {
        DiagRow(
            label = stringResource(lyricsDiagLabelRes(row.label)),
            value = when (val value = row.value) {
                is DiagValue.Text -> value.text
                is DiagValue.YesNo -> if (value.value) yes else no
                is DiagValue.IndexState -> stringResource(lyricsIndexStateRes(value.state))
                is DiagValue.Outcome -> stringResource(lookupOutcomeRes(value.outcome))
                is DiagValue.MatchStep -> stringResource(lookupMatchStepRes(value.step))
            },
        )
    }
}

/**
 * Settings: language switch at the top, lyrics size slider, lyrics font
 * choice (original / Bauhaus), keep-screen-on switch, lyrics folder
 * pick + rescan, about line.
 *
 * Picking a language calls [com.dannycom888.mylrcauto.settings.SettingsStore.setAppLanguage];
 * the activity recreates itself (see MainActivity) so the whole screen switches
 * language at once. The choice persists in SharedPreferences. The size, font
 * and keep-screen-on choices persist the same way, in the same store.
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val lastLaunch by container.launchDiagnostics.last.collectAsStateWithLifecycle()
    val lastRender by container.carRenderDiagnostics.last.collectAsStateWithLifecycle()
    val lastCarError by container.carLastError.last.collectAsStateWithLifecycle()
    val lastMirrorRepublish by container.mirrorRepublish.last.collectAsStateWithLifecycle()
    val lyricsIndexDiag by container.localFolderLyrics.indexDiag.collectAsStateWithLifecycle()
    val lastLookup by container.localFolderLyrics.lastLookup.collectAsStateWithLifecycle()

    var rescanMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        container.settings.setFolderUri(uri.toString())
        rescanMessage = null
    }

    // Display name of the picked folder: the tree document's name when it
    // resolves ("Music"), otherwise the URI's last segment, otherwise the raw URI.
    val folderName = settings.localLyricsFolderUri?.let { raw ->
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(raw))?.name }.getOrNull()
            ?: Uri.parse(raw).lastPathSegment
            ?: raw
    }

    // Scrollable: the list of sections is taller than a phone screen, so
    // without this the bottom rows (folder, version) are cut off with no
    // way to reach them — that is why the version was invisible.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // Top bar with back.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = BackIcon,
                    contentDescription = stringResource(R.string.settings_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // Language switch — at the top, so it is visible the moment the
        // screen opens. Immediate effect (activity recreates), persists.
        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        AppLanguage.entries.forEach { language ->
            // Compact rows (v1.0.29, the user's second pass at
            // these): v1.0.19 zeroed the rows' vertical padding,
            // but a Material3 RadioButton still enforces a 48dp
            // minimum interactive size, so the rows stayed tall.
            // For THESE rows only, the minimum is switched off and
            // the row takes an explicit 36dp height — modestly
            // tighter, not crushed; the text size is unchanged and
            // every other section's rows keep the standard minimum.
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { container.settings.setAppLanguage(language) }
                        .padding(vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.appLanguage == language,
                        onClick = { container.settings.setAppLanguage(language) },
                    )
                    Text(
                        text = languageLabel(language),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Lyrics size — one slider for the phone and the car: the same
        // LyricsView draws both, so the setting sizes both at once. 14–32 sp
        // in 1 sp steps, saved on every move, so the list behind this screen
        // is already the new size when the user goes back.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_font_size_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.settings_font_size_value,
                    settings.lyricsFontSizeSp,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Slider(
            value = settings.lyricsFontSizeSp.toFloat(),
            onValueChange = { container.settings.setLyricsFontSizeSp(it.roundToInt()) },
            valueRange = SettingsStore.MIN_LYRICS_FONT_SIZE_SP.toFloat()
                ..SettingsStore.MAX_LYRICS_FONT_SIZE_SP.toFloat(),
            steps = SettingsStore.MAX_LYRICS_FONT_SIZE_SP -
                SettingsStore.MIN_LYRICS_FONT_SIZE_SP - 1,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Lyrics font — two choices, same radio pattern as the language
        // switch: 原来的 (the default font, as before v1.0.15) and
        // Bauhaus (the user's uploaded font, bold Chinese lyrics).
        // Default is Bauhaus, so nothing changes unless he switches.
        // English lyrics keep the default font either way.
        Text(
            text = stringResource(R.string.settings_font_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        LyricsFontChoice.entries.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { container.settings.setLyricsFont(choice) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.lyricsFont == choice,
                    onClick = { container.settings.setLyricsFont(choice) },
                )
                Text(
                    text = stringResource(
                        when (choice) {
                            LyricsFontChoice.ORIGINAL -> R.string.settings_font_original
                            LyricsFontChoice.BAUHAUS -> R.string.settings_font_bauhaus
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Keep screen on — the phone screen holds the window flag while
        // lyrics are showing; leaving the lyrics screen drops it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { container.settings.setKeepScreenOn(!settings.keepScreenOn) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_keep_screen_on_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_keep_screen_on_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.keepScreenOn,
                onCheckedChange = { container.settings.setKeepScreenOn(it) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Lyrics folder.
        Text(
            text = stringResource(R.string.settings_folder_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = folderName ?: stringResource(R.string.settings_folder_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { folderPicker.launch(null) }) {
                Text(text = stringResource(R.string.settings_folder_pick))
            }
            OutlinedButton(
                onClick = {
                    if (scanning) return@OutlinedButton
                    container.scope.launch {
                        scanning = true
                        val result = container.localFolderLyrics.refreshIndex()
                        rescanMessage = when (result) {
                            is LocalLyricsProvider.IndexResult.Ready ->
                                context.getString(
                                    R.string.settings_rescan_done,
                                    result.lrcFileCount,
                                )
                            is LocalLyricsProvider.IndexResult.Unreadable ->
                                context.getString(R.string.settings_rescan_unreadable)
                            LocalLyricsProvider.IndexResult.NoFolder ->
                                context.getString(R.string.settings_folder_none)
                        }
                        scanning = false
                    }
                },
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(text = stringResource(R.string.settings_rescan))
            }
        }
        if (scanning) {
            Text(
                text = stringResource(R.string.settings_rescan_scanning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (rescanMessage != null) {
            Text(
                text = rescanMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Diagnostics visibility (v1.0.30, completed in v1.0.31):
        // one switch for the diagnostics blocks below （歌词查找、
        // 上次在车上打开 and 大屏显示）. DISPLAY ONLY — the launch
        // log, the render record and the last-error record are all
        // written whether or not these blocks are composed, so
        // flipping this on after something went wrong on the car
        // still shows what was captured while it was off. Hidden by
        // default: Settings stays short for a user who never needs
        // the evidence.
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    container.settings.setDiagnosticsVisible(!settings.diagnosticsVisible)
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_diagnostics_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.diagnosticsVisible,
                onCheckedChange = { container.settings.setDiagnosticsVisible(it) },
            )
        }

        if (settings.diagnosticsVisible) {
            // Lyrics lookup diagnostics (v1.0.22): the index state and
            // the last lookup's inputs + step-by-step outcome — the
            // evidence that says WHY a song shows no lyrics.
            Spacer(modifier = Modifier.height(24.dp))

            LyricsDiagnosticsSection(lyricsIndexDiag, lastLookup)
        }

        if (settings.diagnosticsVisible) {
            // Last car launch (item-7 diagnostics): what the head
            // unit sent for the most recent tap — icon or card —
            // and what the launch decision did with it. Gated by
            // the same switch as the other two diagnostics blocks
            // (v1.0.31: v1.0.30 gated 歌词查找 and 大屏显示 but
            // missed this one, so flipping 诊断 off still left the
            // launch block on screen — the user's screenshot).
            Spacer(modifier = Modifier.height(24.dp))

            LaunchDiagnosticsSection(lastLaunch)
        }

        if (settings.diagnosticsVisible) {
            // Big screen render (v1.0.27 diagnostics): which pane the
            // car screen last returned, what the lyrics side had, and
            // the exception when it fell back — the evidence for a big
            // screen that shows only the title and artist. The section
            // ends with the sticky last-error row (v1.0.30).
            Spacer(modifier = Modifier.height(24.dp))

            CarRenderDiagnosticsSection(lastRender, lastCarError, lastMirrorRepublish)
        }

        // Version, read from the package so it updates every release —
        // and the about line below it. Both sit at the bottom of a
        // scrollable list, so they are always reachable.
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_version, appVersionName(context)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}
