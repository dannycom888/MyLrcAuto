package com.dannycom888.mylrcauto

import android.app.Application
import android.content.Context
import com.dannycom888.mylrcauto.lyrics.LocalFolderSource
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LyricsEngine
import com.dannycom888.mylrcauto.media.MediaSessionRepository
import com.dannycom888.mylrcauto.media.MirrorAction
import com.dannycom888.mylrcauto.media.MirrorActivation
import com.dannycom888.mylrcauto.media.MirrorActivationTracker
import com.dannycom888.mylrcauto.media.MirrorServiceControl
import com.dannycom888.mylrcauto.settings.SettingsStore
import com.dannycom888.mylrcauto.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

        private val appContext: Context = context.applicationContext

        /** Whether the mirror media service is currently wanted running. */
        private val mirrorActivation = MirrorActivationTracker()

        private val _carSessionLive = MutableStateFlow(false)

        /**
         * Whether a car session is currently live — a [com.dannycom888.mylrcauto.car.CarSession]
         * exists because the car connected and opened the app.
         *
         * v1.0.18: the mirror gate. The mirror used to start whenever a
         * track was loaded, with no car anywhere, and its active media
         * session — held by a foreground service — blocked the phone's
         * wireless Android Auto connection. The mirror runs only while
         * this is true; the snapshot hook below combines it with the
         * track, so disconnecting the car stops the service at once
         * and the next connection starts with no active session in
         * the way.
         */
        val carSessionLive: StateFlow<Boolean> = _carSessionLive.asStateFlow()

        /** Called by the car session when it starts and when it ends. */
        fun setCarSessionLive(live: Boolean) {
            _carSessionLive.value = live
        }

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
                folderUri = { settings.current.localLyricsFolderUri },
                backgroundScope = scope,
            ).also { it.attach(LocalFolderSource(context, settings)) }

        val lyrics: LyricsEngine = LyricsEngine(scope, localFolderLyrics)

        init {
            // The file index is cached on disk, so a fresh process can serve
            // the first lookup from the cache at once — but the cache may be
            // stale, so rebuild it eagerly at startup too, keeping it fresh
            // for the next launch.
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

            // A lookup answered by a stale index must not stand. The
            // first lookup in a fresh process is served by the
            // disk-cached index, which the background rebuild then
            // replaces with a fresh walk (LocalLyricsProvider bumps
            // indexVersion when it lands) — and the hook above only
            // fires on a track change, so a track the stale index
            // missed would never be looked up again. When the index
            // is replaced, look the current track up again. The
            // initial value is dropped: nothing has been replaced
            // yet at startup, and the first fetch uses whatever
            // index is current when it runs.
            scope.launch {
                localFolderLyrics.indexVersion
                    .drop(1)
                    .collect { lyrics.refresh() }
            }

            // The mirror media session (v1.0.13): the dashboard card is
            // drawn from the active media session, and the car host only
            // binds LyricsMediaService when the user selects it — but
            // the user's flow is Poweramp selected, then tap the app,
            // and tapping the app opens the CarAppService template, never
            // the browser service, so in that flow the service never
            // started and the card stayed Poweramp's. The app therefore
            // starts its own service the moment a track is loaded, and
            // stops it when there is no track — no picker selection
            // needed. (v1.0.14: a paused track still counts as loaded,
            // so the card keeps its lyric line while paused.) The
            // tracker turns the snapshot stream into actions, and it is
            // the only throttle: this hook collects every snapshot (no
            // distinctUntilChanged on the boolean), because a start that
            // failed once — or a service that died mid-drive — must be
            // re-attempted on the next track change and on the tracker's
            // 5s retry, not suppressed forever by a flip-only filter
            // (v1.0.17: that suppression is how the card stayed gone
            // after a single failed start).
            //
            // v1.0.18: gated on a live car session as well
            // (carSessionLive). The hook used to start the service
            // whenever a track was loaded, car or no car — and that
            // active session, held by a foreground service with the
            // phone nowhere near a car, blocked the wireless Android
            // Auto connection. The combined flow fires on either input
            // changing, so the car connecting starts the mirror (a
            // track is already loaded) and the car disconnecting stops
            // it at once, even if the snapshot does not change.
            scope.launch {
                combine(media.snapshot, carSessionLive) { snapshot, connected ->
                    snapshot to connected
                }.collect { (snapshot, connected) ->
                    when (mirrorActivation.update(
                        MirrorActivation.shouldBeActive(snapshot, connected),
                        snapshot.track?.key,
                        System.currentTimeMillis(),
                    )) {
                        MirrorAction.START -> MirrorServiceControl.start(appContext)
                        MirrorAction.STOP -> MirrorServiceControl.stop(appContext)
                        null -> Unit
                    }
                }
            }
        }
    }
}
