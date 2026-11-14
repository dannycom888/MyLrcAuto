package com.dannycom888.mylrcauto.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
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
        // Guarded as a whole (v1.0.30): the pieces inside were
        // guarded one by one, the body was not — and this callback
        // is the big screen's front door. A throw escaping it is
        // exactly what makes the host draw its own error screen
        // (the 2026-11-13 playlist-switch flash). A failure is
        // recorded for Settings and answered with the plain screen:
        // its construction only stores fields, and its template
        // path has its own guards.
        return try {
            createScreen(intent)
        } catch (e: Exception) {
            container.recordCarError(CarErrorSite.ON_CREATE_SCREEN, e)
            CarLyricsScreen(carContext, container, surface)
        }
    }

    private fun createScreen(intent: Intent): Screen {
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
                // Guarded (v1.0.30): bringing the watcher up touches
                // the notification listener and media controllers;
                // a failure here must cost the watching, not the
                // session the host is creating around it.
                runCatching {
                    app.media.refresh()
                    app.media.start()
                }.onFailure {
                    app.recordCarError(CarErrorSite.SESSION_START, it)
                }
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

        // v1.0.29: the surface callback is registered for EVERY
        // session, here at creation — before the launch decision,
        // so the FINISH path registers too. v1.0.27 registered only
        // on the show paths (creation's SHOW branch and
        // onNewIntent's), and the 2026-11-13 19:07 car run showed
        // the hole in that shape: the session had been created by
        // an icon tap (FINISH — no registration), the app had been
        // finished, and the card tap's onNewIntent registered only
        // afterwards — after the launch moment at which the host
        // does its surface negotiation for a fresh foreground app.
        // The callback never fired for that session: no surface,
        // the fallback pane for the whole song, and nothing in the
        // app could ask again. Registration is cheap and inert
        // until the host offers a surface (a finished session's
        // callback is released with the session, by CarSurface's
        // lifecycle observer), so there is no path that should
        // skip it.
        ensureSurfaceCallback(app)

        // The back arrow's exit (v1.0.29, see ensureBackCallback):
        // registered for every session too, before the decision —
        // a FINISH session needs no back handling (it is already
        // leaving), but registering costs nothing and keeps the
        // rule "every session wires both" free of branches.
        ensureBackCallback()

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

        // (The surface callback was already registered above, for
        // every session shape — see ensureSurfaceCallback.)

        return CarLyricsScreen(carContext, app, surface)
    }

    override fun onNewIntent(intent: Intent) {
        // Guarded as a whole (v1.0.30), like onCreateScreen: this
        // is the callback a card tap lands on — the exact tap in
        // the 2026-11-13 playlist-switch sequence — and nothing in
        // it may escape to the host. A failure is recorded and
        // swallowed: the session keeps whatever screen it had.
        runCatching { handleNewIntent(intent) }
            .onFailure { container.recordCarError(CarErrorSite.ON_NEW_INTENT, it) }
    }

    private fun handleNewIntent(intent: Intent) {
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
                // re-ask for the surface callback: registration runs
                // at creation for every session (v1.0.29), so this
                // is the retry for the one case left, a creation-time
                // registration that failed.
                app.launchConnectionState.onBigScreenShown()
                ensureSurfaceCallback(app)
                ensureBackCallback()
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
     * Asked for from onCreateScreen — unconditionally, before the
     * launch decision (v1.0.29; see the call site for the 19:07
     * evidence) — and again from onNewIntent's show path, where it
     * is the retry for a session whose creation-time registration
     * failed. Asking is idempotent by construction (one instance,
     * created once), so asking again costs nothing.
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
        }.onSuccess {
            surface.onCallbackRegistered()
        }.onFailure { Log.w(TAG, "could not register the surface callback", it) }
    }

    /** Whether the back callback is registered (it is registered at most once per session). */
    private var backCallbackRegistered: Boolean = false

    /**
     * Wires the big screen's back arrow to actually leave
     * (v1.0.29) — the fix for the dead arrow in both the
     * 2026-11-13 car runs.
     *
     * The mechanism, read from the Car App Library 1.7.0
     * bytecode (not the docs, which never mention it):
     *
     * - The header action itself cannot carry the exit: a
     *   template's header is validated against
     *   ACTIONS_CONSTRAINTS_HEADER, which sets
     *   `onClickListenerAllowed = false`. Action.BACK is a
     *   listener-less standard action BY DESIGN — tapping it
     *   only makes the host call `IAppManager.onBackPressed`.
     * - That call lands on
     *   `CarContext.getOnBackPressedDispatcher().onBackPressed()`.
     *   With no callback registered, the dispatcher's fallback
     *   runs: `ScreenManager.pop()`.
     * - `pop()` is a deliberate no-op when the stack holds one
     *   screen (`if (size <= 1) return`, verbatim in the
     *   bytecode). This app's stack ALWAYS holds one screen —
     *   CarLyricsScreen is the only screen the session ever
     *   creates — so every back press on the big screen fell
     *   into that no-op. The arrow was dead by construction,
     *   on every pane, on both template paths — not a host
     *   quirk and not a wiring slip: there was nothing anywhere
     *   that turned a back press into an exit.
     *
     * The fix is the library's own extension point: an enabled
     * [OnBackPressedCallback] on the session's dispatcher,
     * whose press finishes the car app — the exact
     * `finishCarApp()` the launch decision's FINISH path
     * already proved lands this user back on the dashboard
     * with the card alive. The dispatcher is template-agnostic,
     * so the one registration covers the fallback pane, the
     * message panes and the surface template's content pane
     * alike. Scoped to the session's lifecycle (addCallback
     * with the session as owner): a dead session's callback
     * removes itself.
     */
    private fun ensureBackCallback() {
        if (backCallbackRegistered) return
        backCallbackRegistered = true
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "back pressed on the big screen; finishing to the dashboard")
                runCatching { carContext.finishCarApp() }
                    .onFailure {
                        Log.w(TAG, "finishCarApp on back failed", it)
                        container.recordCarError(CarErrorSite.BACK_PRESSED, it)
                    }
            }
        }
        runCatching {
            carContext.onBackPressedDispatcher.addCallback(this, callback)
        }.onFailure {
            backCallbackRegistered = false
            Log.w(TAG, "could not register the back callback", it)
        }
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
