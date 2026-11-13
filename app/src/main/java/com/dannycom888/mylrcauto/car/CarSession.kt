package com.dannycom888.mylrcauto.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import com.dannycom888.mylrcauto.media.LyricsMediaService
import com.dannycom888.mylrcauto.media.MirrorServiceControl

/**
 * One connection to a car, for as long as it lasts.
 *
 * [mirrorAlreadyActive] is the item-7 signal: whether the mirror
 * media session was already active before this session was created
 * — read in [CarAppService.onCreateSession] before the mirror is
 * started, so it cannot be this launch's own start. A card tap can
 * only happen while the card is already showing, which needs an
 * active mirror session; a cold icon tap never has one. v1.0.19
 * only logged it; v1.0.21 feeds it to the launch decision
 * ([decideLaunch]): a launch that is not judged card-driven
 * finishes the car app at once, so an icon tap lands the user
 * back on the dashboard — the two-tap flow every media app on
 * the user's car has. The card survives the finish because the
 * mirror is gated on the projection connection, not on this
 * session (see MirrorActivation).
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

        // Item 7 (v1.0.21): decide what this launch is before doing
        // any of the big screen's work. mirrorAlreadyActive was
        // read in onCreateSession, before this launch could start
        // the mirror; the intent fields are the only other evidence
        // there is. The decision, its inputs and the raw intent are
        // logged AND stored for the phone's Settings screen — the
        // stored record is how the user's car test settles what
        // his head unit sends for an icon tap vs a card tap.
        val signals = LaunchSignals(
            entry = LaunchEntry.CREATE_SCREEN,
            intentAction = intent.action,
            intentCategories = intent.categories.orEmpty(),
            intentExtrasKeys = intent.extras?.keySet().orEmpty(),
            mirrorAlreadyActive = mirrorAlreadyActive,
            bigScreenShownThisConnection = app.launchConnectionState.bigScreenShown,
        )
        val decision = decideLaunch(signals)
        recordLaunch(app, signals, intent.flags, decision)
        Log.i(
            TAG,
            describeLaunchIntent(intent.action, intent.categories, intent.extras?.keySet()) +
                " mirrorAlreadyActive=$mirrorAlreadyActive" +
                " bigScreenShown=${signals.bigScreenShownThisConnection}" +
                " -> $decision",
        )

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.media.refresh()
                app.media.start()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                // Deliberately NOT stopping the mirror (v1.0.21):
                // sessions now end on purpose — every icon tap
                // finishes one — while the car stays connected, and
                // the card must outlive them all. The mirror stops
                // when the projection disconnects, through the
                // CarConnection gate in the container's snapshot
                // hook. (v1.0.18 stopped it here; that coupling is
                // exactly what item 7 had to break.)
                Log.i(TAG, "car session destroyed (the mirror is projection-gated and keeps running)")
            }
        })

        if (decision == LaunchDecision.FINISH_TO_DASHBOARD) {
            // Land back on the dashboard: finish the car app as
            // soon as this call has returned and the session exists
            // for the host to end. The screen returned below is a
            // placeholder for the moment in between — and the
            // fail-open fallback if a host ever refuses the finish:
            // the user then gets the big screen, exactly the
            // pre-item-7 behaviour, instead of a stuck app.
            Handler(Looper.getMainLooper()).post {
                runCatching { carContext.finishCarApp() }
                    .onFailure { Log.w(TAG, "finishCarApp failed; staying on the big screen", it) }
            }
            return CarLyricsScreen(carContext, app, surface)
        }

        app.launchConnectionState.onBigScreenShown()

        // The main screen is being shown: the car app is the
        // foreground app, so starting the mirror service is allowed
        // here — the same start session creation already attempted,
        // and the snapshot hook's background start may have been
        // blocked (v1.0.13). Starting an already-running service is
        // a no-op. (The finish path above skips this on purpose:
        // the projection-gated hook owns the mirror for the whole
        // connection, and an icon launch needs no start of its own.)
        startMirrorFromCarApp(app.media.snapshot.value) {
            MirrorServiceControl.start(carContext.applicationContext)
        }

        ensureSurfaceCallback(app)

        return CarLyricsScreen(carContext, app, surface)
    }

    override fun onNewIntent(intent: Intent) {
        val app = container
        // The same decision as onCreateScreen, for a session that
        // already exists — the icon tapped again while the app is
        // running, or the card tapped while the session is alive.
        // mirrorAlreadyActive is read now, before anything here
        // could start the mirror; mid-session it is simply "the
        // card is up", which is the fact the decision needs.
        val signals = LaunchSignals(
            entry = LaunchEntry.NEW_INTENT,
            intentAction = intent.action,
            intentCategories = intent.categories.orEmpty(),
            intentExtrasKeys = intent.extras?.keySet().orEmpty(),
            mirrorAlreadyActive = LyricsMediaService.isSessionActive,
            bigScreenShownThisConnection = app.launchConnectionState.bigScreenShown,
        )
        val decision = decideLaunch(signals)
        recordLaunch(app, signals, intent.flags, decision)
        Log.i(
            TAG,
            "new intent for live session: " +
                describeLaunchIntent(intent.action, intent.categories, intent.extras?.keySet()) +
                " mirrorAlreadyActive=${signals.mirrorAlreadyActive}" +
                " bigScreenShown=${signals.bigScreenShownThisConnection}" +
                " -> $decision",
        )
        when (decision) {
            LaunchDecision.SHOW_BIG_SCREEN -> {
                // The big screen is this session's only screen and
                // is already on its stack; the host brings the app
                // forward itself. Keep the launch memory true — and
                // make sure the surface callback exists (below): a
                // session created through the FINISH path never
                // registered one.
                app.launchConnectionState.onBigScreenShown()
                ensureSurfaceCallback(app)
            }

            LaunchDecision.FINISH_TO_DASHBOARD ->
                // The session is live, so the finish can go out at
                // once — an icon tap always lands on the dashboard,
                // even with the big screen already showing.
                runCatching { carContext.finishCarApp() }
                    .onFailure { Log.w(TAG, "finishCarApp failed; staying on the big screen", it) }
        }
    }

    /**
     * The surface callback, created at most once per session: its
     * constructor registers a lifecycle observer on the session, so
     * a second instance would double the surface's bookkeeping.
     */
    private var surfaceCallback: CarSurface? = null

    /**
     * Registers the surface callback, if this session has not yet:
     * the door to the app's own drawing surface, without which the
     * big screen can only ever be the template fallback.
     *
     * Asked for from the two places that show the big screen —
     * onCreateScreen's show path, and onNewIntent's. The second is
     * the one that mattered (v1.0.27, code-proven gap): a session
     * created by an icon tap returns from onCreateScreen through
     * the FINISH path, before any registration; the launch record
     * from the 2026-11-13 car test (entry onNewIntent, decision
     * SHOW_BIG_SCREEN) proves the card tap can land on exactly such
     * a still-live session, and nothing on that path ever
     * registered a callback for it — so no surface could arrive,
     * and the big screen was stuck on the fallback pane. Asking
     * here is idempotent by construction (one instance, created
     * once), so the show path asking again costs nothing.
     *
     * Asked for once rather than when a screen appears: the host
     * answers whenever it is ready, and a callback registered later
     * can miss the surface it already offered.
     *
     * Not asked for at all on a host below car API 7, which is the
     * level MapWithContentTemplate needs — the one template that
     * can carry a surface here. An older host can still hand one
     * over, and accepting it would mean standing up a virtual
     * display, a presentation and a whole composition to draw
     * something no template of ours could put on screen.
     */
    private fun ensureSurfaceCallback(app: AppContainer) {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_7) return
        val callback = surfaceCallback ?: CarSurface(this, app, surface).also {
            surfaceCallback = it
        }
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        }.onFailure { Log.w(TAG, "could not register the surface callback", it) }
    }

    /**
     * Store the launch for the phone's Settings diagnostics.
     * Guarded: a diagnostic must never break a launch.
     */
    private fun recordLaunch(
        app: AppContainer,
        signals: LaunchSignals,
        intentFlags: Int,
        decision: LaunchDecision,
    ) {
        runCatching {
            app.launchDiagnostics.record(
                LaunchRecord(
                    timestampMs = System.currentTimeMillis(),
                    entry = signals.entry,
                    intentAction = signals.intentAction,
                    intentCategories = signals.intentCategories,
                    intentExtrasKeys = signals.intentExtrasKeys,
                    intentFlags = intentFlags,
                    mirrorAlreadyActive = signals.mirrorAlreadyActive,
                    bigScreenShownThisConnection = signals.bigScreenShownThisConnection,
                    decision = decision,
                ),
            )
        }.onFailure { Log.w(TAG, "could not store the launch diagnostic", it) }
    }

    // No media.stop() on destroy either, deliberately: the media repository
    // is shared with the phone, and a car session ending must not stop the
    // phone tracking playback. The surface releases itself through the
    // session lifecycle observer registered in CarSurface.

    private companion object {
        const val TAG = "MyLrcAutoCarSession"
    }
}
