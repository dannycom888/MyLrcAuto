package com.dannycom888.mylrcauto.car

/**
 * The item-7 launch decision: two-tap flow, always (v1.0.21).
 *
 * The user wants what every media app on his car does: one tap on
 * the app icon shows ONLY the dashboard (map + the small media
 * card); a tap on the card opens the big screen
 * ([CarLyricsScreen]). Media apps get that for free — the host
 * draws their card and their now-playing screen from the media
 * session, so there is no app screen for an icon tap to open. This
 * app is a template app: the host hands an icon tap straight to
 * [CarSession.onCreateScreen], so the two-step has to be emulated
 * at runtime — an icon launch finishes the car app at once
 * (`CarContext.finishCarApp`), which lands the user back on the
 * dashboard, and the card survives because the mirror is no longer
 * tied to the template session (it is gated on the projection
 * connection — see `MirrorActivation`).
 *
 * **Which signal carries the decision, honestly.** The Car App
 * Library (1.7.0) exposes no icon-tap-vs-card-tap signal: both
 * arrive as session starts with a launch intent, and v1.0.19's
 * logging was added precisely because no documented difference
 * exists. This decision is built from the signals that ARE
 * available, and only one of them can separate an icon tap from a
 * card tap:
 *
 * - [LaunchSignals.mirrorAlreadyActive] — a NECESSARY condition
 *   for a card tap, never a sufficient one. A card can only be
 *   tapped while it is on the dashboard, which needs the mirror
 *   session already active before this launch (read before the
 *   launch itself can start the mirror). With no active mirror
 *   there is no card, so the launch finishes no matter what the
 *   intent claims. But an icon tap with the card already up also
 *   has the mirror active, so this signal alone cannot tell the
 *   two apart.
 * - The intent's shape — the ONLY candidate distinguisher. A
 *   plain launcher intent ([isPlainLauncherIntent]: action MAIN
 *   or absent, no categories beyond LAUNCHER, no extras) is what
 *   an icon tap sends. Anything beyond that profile — a different
 *   action, an extra category, any extras key — is treated as
 *   card-driven. Whether the user's head unit actually marks card
 *   taps this way is UNPROVEN until his car test (the Settings
 *   diagnostics show exactly what each tap sent — see
 *   [LaunchRecord]). If card taps arrive with plain launcher
 *   intents too, they will finish like icon taps and the big
 *   screen becomes unreachable from the card — the documented
 *   failure mode of this heuristic, and the reason the
 *   diagnostics exist.
 * - [LaunchSignals.bigScreenShownThisConnection] and
 *   [LaunchSignals.entry] are RECORDED but deliberately do not
 *   change the outcome: the user's rule is "icon always → card",
 *   so an icon tap finishes even after the big screen was already
 *   shown this connection, and a card tap re-opens the big screen
 *   just the same. They are in the signals so the tests can pin
 *   that, and so the diagnostics show the full picture.
 *
 * Pure, so the whole scenario table is unit-tested without an
 * Android runtime: the session extracts the fields from the
 * `Intent` and passes them in.
 */

/** Which door the launch came through. */
enum class LaunchEntry {
    /** A new session was created for this launch (`Session.onCreateScreen`). */
    CREATE_SCREEN,

    /** The launch intent arrived at a session that already exists (`Session.onNewIntent`). */
    NEW_INTENT,
}

/** What the session does with a launch. */
enum class LaunchDecision {
    /** Open (or keep) the big screen — a card-driven launch. */
    SHOW_BIG_SCREEN,

    /** Finish the car app at once — the user lands on the dashboard. */
    FINISH_TO_DASHBOARD,
}

/** Everything the launch decision is allowed to look at. */
data class LaunchSignals(
    val entry: LaunchEntry,
    val intentAction: String?,
    val intentCategories: Set<String>,
    val intentExtrasKeys: Set<String>,
    /** The mirror media session was already active before this launch could start it. */
    val mirrorAlreadyActive: Boolean,
    /** The big screen has already been shown during this car connection. */
    val bigScreenShownThisConnection: Boolean,
)

private const val LAUNCHER_ACTION_MAIN = "android.intent.action.MAIN"
private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

/**
 * Whether an intent has exactly the plain launcher shape: the
 * action an icon tap sends (MAIN, or none), no category beyond
 * LAUNCHER, and no extras at all. Any deviation is information —
 * the only information there is — that the launch did not come
 * from the icon.
 */
fun isPlainLauncherIntent(
    action: String?,
    categories: Set<String>,
    extrasKeys: Set<String>,
): Boolean =
    (action == null || action == LAUNCHER_ACTION_MAIN) &&
        categories.all { it == LAUNCHER_CATEGORY } &&
        extrasKeys.isEmpty()

/**
 * Whether this launch is judged card-driven — the only launches
 * that may show the big screen. Two conditions, both required:
 * the card must already have been on the dashboard (mirror
 * active before this launch), and the intent must carry
 * something a plain icon tap does not.
 */
fun isCardDrivenLaunch(signals: LaunchSignals): Boolean =
    signals.mirrorAlreadyActive &&
        !isPlainLauncherIntent(
            signals.intentAction,
            signals.intentCategories,
            signals.intentExtrasKeys,
        )

/**
 * The decision itself. Everything that is not card-driven
 * finishes to the dashboard — cold icon taps (no card yet), icon
 * taps with the card up, icon taps after the big screen was
 * already shown this connection, and intents delivered to a live
 * session that are not card-driven.
 */
fun decideLaunch(signals: LaunchSignals): LaunchDecision =
    if (isCardDrivenLaunch(signals)) {
        LaunchDecision.SHOW_BIG_SCREEN
    } else {
        LaunchDecision.FINISH_TO_DASHBOARD
    }

/**
 * Per-connection launch memory: whether the big screen has been
 * shown since the projection connected.
 *
 * Lives for one car connection. A projection disconnect ends the
 * connection, so the flag resets there (fed by the same
 * CarConnection observer that gates the mirror); a template
 * session ending does NOT reset it — finishing on an icon tap is
 * the normal two-tap flow, not a new connection.
 */
class LaunchConnectionState {
    var bigScreenShown: Boolean = false
        private set

    fun onBigScreenShown() {
        bigScreenShown = true
    }

    /** Feed every projection-connection change; a disconnect resets the flag. */
    fun onProjectionChanged(connected: Boolean) {
        if (!connected) bigScreenShown = false
    }
}
