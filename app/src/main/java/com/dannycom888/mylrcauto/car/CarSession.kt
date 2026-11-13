package com.dannycom888.mylrcauto.car

import android.content.Intent
import android.util.Log
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
 * [mirrorAlreadyActive] is the item-7 diagnostic (v1.0.19): whether
 * the mirror media session was already active before this session
 * was created — read in [CarAppService.onCreateSession] before the
 * mirror is started, so it cannot be this launch's own start. A
 * card tap can only happen while the card is already showing, which
 * needs an active mirror session; a cold icon tap never has one.
 * It is logged in [onCreateScreen], not acted on: the library gives
 * no reliable way to finish an icon launch without also killing
 * the card (see [describeLaunchIntent]), so v1.0.19 records the
 * facts for the follow-up instead of guessing.
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
class CarSession(
    private val mirrorAlreadyActive: Boolean = false,
) : Session(), SavedStateRegistryOwner {

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

        // Item-7 diagnostic (v1.0.19): what the host launched us with,
        // and whether the mirror was already active — the two facts
        // the follow-up needs to tell an icon tap from a card tap.
        // Logged, not acted on: the library gives no reliable way to
        // finish an icon launch without also killing the card (see
        // describeLaunchIntent), so this version records the facts
        // instead of guessing.
        Log.i(
            TAG,
            describeLaunchIntent(intent.action, intent.categories, intent.extras?.keySet()) +
                " mirrorAlreadyActive=$mirrorAlreadyActive",
        )

        // The car session is live (also set in onCreateSession — this
        // is the backstop for a session created any other way). The
        // mirror gate reads this flag, so the service only runs while
        // the car is actually connected.
        app.setCarSessionLive(true)

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

            override fun onDestroy(owner: LifecycleOwner) {
                // The car is gone: the mirror stops with the session.
                // v1.0.18 — the service must never outlive the car
                // connection, or its active media session is still
                // held the next time the phone tries to connect
                // wirelessly. The snapshot hook's combined flow fires
                // the same STOP when the flag flips, but this direct
                // stop does not depend on that flow being collected.
                // Logged for the item-7 follow-up: a session destroyed
                // right after an icon launch is what would kill the
                // card if that launch were finished.
                Log.i(TAG, "car session destroyed, stopping the mirror")
                app.setCarSessionLive(false)
                MirrorServiceControl.stop(carContext.applicationContext)
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

    override fun onNewIntent(intent: Intent) {
        // Item-7 diagnostic (v1.0.19): a new intent for a session
        // that already exists — e.g. the icon tapped again while the
        // app is running, or the card tapped while the session is
        // alive. Logged with the same description as onCreateScreen,
        // so the follow-up can see whether the two launch sources
        // ever arrive with different intents. No behaviour change:
        // the single lyrics screen is the whole app, and the tick
        // loop already re-reads the playing track.
        Log.i(
            TAG,
            "new intent for live session: " +
                describeLaunchIntent(intent.action, intent.categories, intent.extras?.keySet()),
        )
    }

    // No media.stop() on destroy either, deliberately: the media repository
    // is shared with the phone, and a car session ending must not stop the
    // phone tracking playback. The surface releases itself through the
    // session lifecycle observer registered in CarSurface.

    private companion object {
        const val TAG = "MyLrcAutoCarSession"
    }
}
