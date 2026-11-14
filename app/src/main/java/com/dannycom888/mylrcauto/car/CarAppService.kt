package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.media.LyricsMediaService
import com.dannycom888.mylrcauto.media.MirrorServiceControl
import com.dannycom888.mylrcauto.util.AppLocale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android Auto entry point.
 *
 * The class is deliberately named the same as its superclass — the manifest
 * already declares `.car.CarAppService`, so the name is fixed — and the
 * superclass is therefore referenced fully qualified below rather than
 * imported.
 *
 * **What this cannot be, and why.** The Car App Library admits apps in seven
 * categories — navigation, POI, IoT, weather, media, messaging, calling —
 * and there is no category for lyrics. POI is the one that can reach
 * MapWithContentTemplate, which is what gives the app a surface of its own
 * to draw the lyrics on — so the category is chosen for the template it
 * unlocks rather than for describing the app (see the manifest). The
 * consequence is that this can never be a Play-listed Android Auto app and
 * needs Android Auto's own unknown-sources developer setting to appear,
 * which is the same audience an APK already has.
 */
class CarAppService : androidx.car.app.CarAppService() {

    override fun attachBaseContext(base: Context) {
        // The app's own menu language, chosen in Settings — the car screen
        // reads its strings from this service's context, so it is wrapped
        // here rather than relying on the Application's. See AppLocale.
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreateSession(): Session {
        val app = applicationContext as MyLrcAutoApp
        // Item-7 diagnostic (v1.0.19), now a decision input (v1.0.21):
        // whether the mirror session was already active BEFORE this
        // session was created — read before the start below, so it
        // cannot be this launch's own start. A card tap can only
        // happen while the card is already showing, which needs an
        // active mirror; a cold icon tap never has one. Passed to
        // the session, which feeds it to the launch decision (see
        // LaunchDecision.kt).
        val mirrorAlreadyActive = LyricsMediaService.isSessionActive
        // The car app is the foreground app from here, so starting the
        // mirror service is allowed — where the snapshot hook's
        // background start may have been blocked (v1.0.13). The screen
        // does the same when it is created; the two call sites cannot
        // double-start a running service.
        //
        // The re-publish rides along (v1.0.32): a session starting
        // is the moment the host (re)reads the world, and if its
        // last teardown was host-side only — the 2026-11-13 20:55
        // error-screen flash, after which the big screen recovered
        // but the taskbar widget never showed the song again — the
        // running mirror needs to put its metadata back and
        // re-assert its session, which a plain start delivered to
        // a running service never did. See MirrorRepublish.
        //
        // Guarded: session creation must never throw — a throw here
        // takes the whole car app down at the moment the car opens
        // it, and a mirror that fails to start is not worth that.
        // The snapshot hook is the backstop: gated on the projection
        // connection (v1.0.21), it starts the service as soon as the
        // car is connected and a track is loaded.
        runCatching {
            startMirrorFromCarApp(app.container.media.snapshot.value) {
                MirrorServiceControl.start(applicationContext)
                MirrorServiceControl.republish(applicationContext)
            }
        }.onFailure {
            app.container.recordCarError(CarErrorSite.ON_CREATE_SESSION, it)
        }
        installUncaughtErrorRecorder(app)
        // The session object itself: construction is a saved-state
        // restore over nothing, which has no realistic failure —
        // but if it ever did throw there is no fallback session to
        // return, so the failure is recorded (v1.0.30) and rethrown:
        // the record is what the next Settings screenshot reads.
        return try {
            CarSession(mirrorAlreadyActive)
        } catch (e: Exception) {
            app.container.recordCarError(CarErrorSite.ON_CREATE_SESSION, e)
            throw e
        }
    }

    /**
     * Installs (once) a default uncaught-exception handler that
     * records the failure into the sticky last-error diagnostics
     * and then delegates to the handler it replaced (v1.0.30).
     *
     * Why: the 2026-11-13 playlist-switch flash — the host's
     * error screen on big-screen entry, recovering on its own —
     * is the shape of a process death, and a process death erases
     * every in-memory trace; the render record written after the
     * restart described only the recovery （回退原因 （无）). A
     * handler that writes the record synchronously before the
     * process goes down is the one instrument that survives it.
     * Crash behaviour itself is unchanged: the record is written
     * behind a guard, and the previous handler always runs.
     */
    private fun installUncaughtErrorRecorder(app: MyLrcAutoApp) {
        if (!uncaughtRecorderInstalled.compareAndSet(false, true)) return
        val store = app.container.carLastError
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                store.record(
                    carLastErrorRecord(
                        timestampMs = System.currentTimeMillis(),
                        site = CarErrorSite.UNCAUGHT,
                        error = error,
                    ),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Log.e(TAG, "uncaught exception on thread ${thread.name}", error)
            }
        }
    }

    /**
     * Which hosts may drive this app.
     *
     * The service has to be exported for Android Auto to reach it at all, so
     * this is the check that replaces a permission: only a host whose
     * signature is on the library's own allowlist gets to open a session. A
     * debug build allows any host, which is what makes the Desktop Head Unit
     * usable while developing — and is exactly why it is gated on the
     * debuggable flag rather than left in for convenience.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    private companion object {
        const val TAG = "MyLrcAutoCarService"

        /** Whether the uncaught-exception recorder has been installed (once per process). */
        val uncaughtRecorderInstalled = AtomicBoolean(false)
    }
}
