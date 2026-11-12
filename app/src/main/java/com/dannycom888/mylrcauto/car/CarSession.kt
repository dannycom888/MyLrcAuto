package com.dannycom888.mylrcauto.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.media.MirrorServiceControl

/**
 * One connection to a car, for as long as it lasts.
 *
 * One thing happens here that the phone's activity normally does, because a
 * car screen can be the *only* thing that ever opens this app — plug the
 * phone in on the way to work and no window of ours is ever created:
 * **start watching.** The media repository is brought up exactly as the
 * phone brings it up. Without it, the car screen would sit on "nothing
 * playing" while the stereo played, because nothing had ever asked the
 * system what was going on.
 *
 * A `ComposeView` drawn onto the car's surface needs a `LifecycleOwner` and
 * a `SavedStateRegistryOwner`, and a session is the right lifetime for both:
 * the UI on the car screen should exist for exactly as long as the car is
 * connected.
 */
class CarSession : Session(), SavedStateRegistryOwner {

    private val savedState = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedState.savedStateRegistry

    init {
        // At construction, and not in onCreateScreen: a registry is restored
        // while its owner is still initialising, which is what an activity
        // does inside onCreate. There is nothing to restore — a car session
        // has no state worth carrying across a reconnect — but the registry
        // has to have been restored before a ComposeView is allowed to use it.
        savedState.performRestore(null)
    }

    /** Shared with the screens, which need to know whether there is a surface to draw the app on. */
    private val surface = CarSurfaceState()

    private val container: AppContainer
        get() = (carContext.applicationContext as MyLrcAutoApp).container

    override fun onCreateScreen(intent: Intent): Screen {
        val app = container

        // The main screen is created: the car app is the foreground app,
        // so starting the mirror service is allowed here too — the same
        // start the session creation already attempted, and the snapshot
        // hook's background start may have been blocked (v1.0.13).
        // Starting an already-running service is a no-op.
        startMirrorFromCarApp(app.media.snapshot.value) {
            MirrorServiceControl.start(carContext.applicationContext)
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.media.refresh()
                app.media.start()
            }
        })

        // Asked for once, here, rather than when a screen appears: the host
        // answers whenever it is ready, and a callback registered later can
        // miss the surface it already offered.
        //
        // Not asked for at all on a host below car API 7, which is the level
        // MapWithContentTemplate needs — the one template that can carry a
        // surface here. An older host can still hand one over, and accepting
        // it would mean standing up a virtual display, a presentation and a
        // whole composition to draw something no template of ours could put
        // on screen.
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            runCatching {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(CarSurface(this, app, surface))
            }
        }

        return CarLyricsScreen(carContext, app, surface)
    }

    // No onNewIntent override: there is nothing to do with a new intent —
    // the single lyrics screen is the whole app, and the tick loop in
    // CarLyricsScreen already re-reads the playing track.
    //
    // No media.stop() on destroy either, deliberately: the media repository
    // is shared with the phone, and a car session ending must not stop the
    // phone tracking playback. The surface releases itself through the
    // session lifecycle observer registered in CarSurface.
}
