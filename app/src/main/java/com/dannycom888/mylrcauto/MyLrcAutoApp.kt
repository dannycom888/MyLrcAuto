package com.dannycom888.mylrcauto

import android.app.Application
import android.content.Context
import com.dannycom888.mylrcauto.lyrics.LocalFolderSource
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LyricsEngine
import com.dannycom888.mylrcauto.media.MediaSessionRepository
import com.dannycom888.mylrcauto.settings.SettingsStore
import com.dannycom888.mylrcauto.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hand-rolled container instead of a DI framework: there are five objects,
 * they are all singletons, and the wiring between them is the interesting
 * part of this file.
 */
class MyLrcAutoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        // The app's own menu language, chosen in Settings — applied before
        // onCreate so the container and everything it builds reads resources
        // in the chosen language. See AppLocale.
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start watching playback now, so the phone UI sees what is playing
        // immediately rather than waiting for the first activity resume.
        container.media.start()
    }

    class AppContainer(context: Context) {

        /**
         * App-lifetime scope, never cancelled: the wiring below (index
         * pre-build, folder watching, track → lyrics) lives as long as the
         * process does.
         */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val settings: SettingsStore = SettingsStore(context)

        val media: MediaSessionRepository = MediaSessionRepository(context)

        /**
         * Local-only lyrics: the user's own `.lrc` files in a folder they
         * pick, read through the Storage Access Framework. The source reads
         * the folder URI from Settings, so picking a new folder takes effect
         * on the next scan.
         */
        val localFolderLyrics: LocalLyricsProvider =
            LocalLyricsProvider(
                context = context,
                hasFolder = { settings.current.localLyricsFolderUri != null },
            ).also { it.attach(LocalFolderSource(context, settings)) }

        val lyrics: LyricsEngine = LyricsEngine(scope, localFolderLyrics)

        init {
            // The file index lives only in memory, so a fresh process starts
            // without one — and the first lookup would otherwise build it
            // inside a timeout, which a big folder can outrun. Build it
            // eagerly at startup, so the first track already finds its lyrics.
            scope.launch {
                if (settings.current.localLyricsFolderUri != null) {
                    localFolderLyrics.refreshIndex()
                }
            }

            // A new lyrics folder means the cached file index is stale: drop
            // it and rebuild for the new folder. The initial emission is
            // dropped — the startup pre-build above already covers it.
            scope.launch {
                settings.settings
                    .map { it.localLyricsFolderUri }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { uri ->
                        localFolderLyrics.invalidate()
                        if (uri != null) {
                            localFolderLyrics.refreshIndex()
                        }
                    }
            }

            // The whole app in one line: whatever the phone is playing
            // decides what we look up.
            scope.launch {
                media.snapshot
                    .map { it.track }
                    .distinctUntilChanged { old, new -> old?.key == new?.key }
                    .collect { track -> lyrics.setTrack(track) }
            }
        }
    }
}
