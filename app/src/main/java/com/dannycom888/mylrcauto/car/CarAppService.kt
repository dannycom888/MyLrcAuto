package com.dannycom888.mylrcauto.car

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.media.MirrorServiceControl
import com.dannycom888.mylrcauto.util.AppLocale

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
        // A car session is live from here: the mirror may run (see
        // MirrorActivation — v1.0.18 gates it on a live car session,
        // because a mirror running with no car present blocked the
        // wireless Android Auto connection). The flag is set before
        // anything else, so the snapshot hook's combined flow can
        // start the service as soon as a track is loaded.
        val app = applicationContext as MyLrcAutoApp
        runCatching { app.container.setCarSessionLive(true) }
        // The car app is the foreground app from here, so starting the
        // mirror service is allowed — where the snapshot hook's
        // background start may have been blocked (v1.0.13). The screen
        // does the same when it is created; the two call sites cannot
        // double-start a running service.
        //
        // Guarded: session creation must never throw — a throw here
        // takes the whole car app down at the moment the car opens
        // it, and a mirror that fails to start is not worth that.
        // The snapshot hook is the backstop: it starts the service
        // as soon as the flag above is set and a track is loaded.
        runCatching {
            startMirrorFromCarApp(app.container.media.snapshot.value) {
                MirrorServiceControl.start(applicationContext)
            }
        }
        return CarSession()
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
}
