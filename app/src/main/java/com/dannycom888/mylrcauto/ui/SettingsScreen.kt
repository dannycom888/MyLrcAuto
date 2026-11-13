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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.dannycom888.mylrcauto.car.LaunchDecision
import com.dannycom888.mylrcauto.car.LaunchEntry
import com.dannycom888.mylrcauto.car.LaunchRecord
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

/** One label/value row of the last-launch diagnostics block. */
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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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

        // Lyrics lookup diagnostics (v1.0.22): the index state and
        // the last lookup's inputs + step-by-step outcome — the
        // evidence that says WHY a song shows no lyrics.
        Spacer(modifier = Modifier.height(24.dp))

        LyricsDiagnosticsSection(lyricsIndexDiag, lastLookup)

        // Last car launch (item-7 diagnostics): what the head unit
        // sent for the most recent tap — icon or card — and what
        // the launch decision did with it. Sits just above the
        // version, at the bottom of the scrollable list.
        Spacer(modifier = Modifier.height(24.dp))

        LaunchDiagnosticsSection(lastLaunch)

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
