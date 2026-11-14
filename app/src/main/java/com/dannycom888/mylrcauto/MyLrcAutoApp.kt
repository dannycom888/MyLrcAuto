package com.dannycom888.mylrcauto

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.dannycom888.mylrcauto.car.CarLastErrorStore
import com.dannycom888.mylrcauto.car.CarRenderDiagnosticsStore
import com.dannycom888.mylrcauto.car.LaunchConnectionState
import com.dannycom888.mylrcauto.car.carLastErrorRecord
import com.dannycom888.mylrcauto.car.LaunchDiagnosticsStore
import com.dannycom888.mylrcauto.lyrics.LocalFolderSource
import com.dannycom888.mylrcauto.lyrics.LocalLyricsProvider
import com.dannycom888.mylrcauto.lyrics.LyricsEngine
import com.dannycom888.mylrcauto.media.MediaSessionRepository
import com.dannycom888.mylrcauto.media.MirrorAction
import com.dannycom888.mylrcauto.media.MirrorActivation
import com.dannycom888.mylrcauto.media.MirrorActivationTracker
import com.dannycom888.mylrcauto.media.MirrorRepublishRecord
import com.dannycom888.mylrcauto.media.MirrorRepublishReason
import com.dannycom888.mylrcauto.media.MirrorRepublishStore
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

        private val _carProjectionConnected = MutableStateFlow(false)

        /**
         * Whether an Android Auto projection is currently connected —
         * the CarConnection type, mapped to a boolean.
         *
         * v1.0.18 gated the mirror on a live car *session*; v1.0.21
         * gates it on the projection itself (the AALyrics
         * demand-lifecycle pattern). The mirror's only consumer is
         * the car's dashboard card, and item 7's two-tap flow
         * finishes the template session on every icon tap — the card
         * must outlive the session, so the session can no longer be
         * the gate. Projection connected is still "a car is actually
         * there": before a projection exists this is false, the
         * mirror never runs, and the wireless handshake protection
         * from v1.0.18 (an active session held with no car present
         * blocked wireless Android Auto) is unchanged. A projection
         * disconnect flips it false and stops the mirror at once.
         */
        val carProjectionConnected: StateFlow<Boolean> =
            _carProjectionConnected.asStateFlow()

        /**
         * The CarConnection feeding [carProjectionConnected]. Held
         * in a field (with its observer) for the container's whole
         * life: the LiveData only keeps delivering while both live.
         */
        private var carConnection: CarConnection? = null
        private val carConnectionObserver = Observer<Int> { type ->
            val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION
            _carProjectionConnected.value = connected
            launchConnectionState.onProjectionChanged(connected)
        }

        /**
         * Per-connection launch memory for the two-tap decision:
         * whether the big screen has been shown since the projection
         * connected. Reset by the CarConnection observer above when
         * the projection goes away.
         */
        val launchConnectionState = LaunchConnectionState()

        /** The most recent car launch's decision inputs and outcome (Settings diagnostics). */
        val launchDiagnostics: LaunchDiagnosticsStore = LaunchDiagnosticsStore(context)

        /** The most recent big-screen render's pane, inputs and fallback cause (Settings diagnostics). */
        val carRenderDiagnostics: CarRenderDiagnosticsStore = CarRenderDiagnosticsStore(context)

        /**
         * The last exception on a car entry path, sticky across
         * healthy renders and process restarts (Settings
         * diagnostics, v1.0.30 — see CarLastError).
         */
        val carLastError: CarLastErrorStore = CarLastErrorStore(context)

        /**
         * Records a car entry-path failure for the Settings
         * diagnostics AND the log. Guarded end to end and deduped
         * by the store: recording must never be the thing that
         * breaks the path it is watching, and a failure repeating
         * on a tick must not rewrite the same fact forever. Every
         * guarded catch on the car entry paths (session creation,
         * screen creation, new intent, template build, surface
         * callbacks) calls this, so the next transient failure —
         * the 2026-11-13 playlist-switch error-screen flash above
         * all — leaves its name behind.
         */
        fun recordCarError(site: String, error: Throwable) {
            Log.w(TAG, "car entry path failed at $site", error)
            runCatching {
                carLastError.record(
                    carLastErrorRecord(
                        timestampMs = System.currentTimeMillis(),
                        site = site,
                        error = error,
                    ),
                )
            }
        }

        /**
         * The most recent full re-publish of the mirror media
         * session, and what triggered it (Settings diagnostics,
         * v1.0.32 — see MirrorRepublish). After a host-side
         * teardown this row is how the next car test confirms
         * the recovery fired.
         */
        val mirrorRepublish: MirrorRepublishStore = MirrorRepublishStore(context)

        /**
         * Records a full mirror re-publish for the Settings
         * diagnostics AND the log. Guarded like [recordCarError]:
         * the record is written from the publish path itself, and
         * recording must never be the thing that breaks it.
         */
        fun recordMirrorRepublish(reason: MirrorRepublishReason) {
            Log.i(TAG, "mirror re-published in full: $reason")
            runCatching {
                mirrorRepublish.record(
                    MirrorRepublishRecord(
                        timestampMs = System.currentTimeMillis(),
                        reason = reason,
                    ),
                )
            }
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
            // Watch the car connection itself (v1.0.21): CarConnection
            // reports the projection state whether or not a template
            // session exists, which is exactly what the mirror gate
            // and the launch memory need. Guarded: if the connection
            // cannot be observed on this device, the gate simply stays
            // false — the mirror never runs — rather than taking the
            // whole app down in Application.onCreate.
            runCatching {
                val connection = CarConnection(appContext)
                carConnection = connection
                connection.type.observeForever(carConnectionObserver)
            }.onFailure { Log.w(TAG, "could not observe the car connection", it) }

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
            // v1.0.18: gated on a car being there as well. The hook
            // used to start the service whenever a track was loaded,
            // car or no car — and that active session, held by a
            // foreground service with the phone nowhere near a car,
            // blocked the wireless Android Auto connection. The
            // combined flow fires on either input changing, so the
            // car connecting starts the mirror (a track is already
            // loaded) and the car disconnecting stops it at once,
            // even if the snapshot does not change.
            //
            // v1.0.21: the car input is the projection connection,
            // not a live template session — the two-tap flow
            // finishes template sessions on icon taps, and the card
            // (this mirror) must survive them for the whole drive.
            scope.launch {
                combine(media.snapshot, carProjectionConnected) { snapshot, connected ->
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

        private companion object {
            const val TAG = "MyLrcAutoApp"
        }
    }
}
