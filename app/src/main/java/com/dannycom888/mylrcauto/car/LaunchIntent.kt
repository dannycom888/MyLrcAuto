package com.dannycom888.mylrcauto.car

/**
 * Describing the intent a car session was launched with — the item-7
 * diagnostic (v1.0.19), now the log half of the v1.0.21 launch
 * decision.
 *
 * The user wants one tap on the app icon to show only the small card
 * (dashboard: map + media card) and a tap on the card to open the big
 * screen, always, driving or parked. Implementing that needs the app
 * to tell an icon launch from a card-tap launch, and the Car App
 * Library (1.7.0, verified against its bytecode) gives no signal
 * that does:
 *
 * - The host delivers its launch intent to `Session.onCreateScreen`
 *   (new session) or `Session.onNewIntent` (existing session), and
 *   without a session-activity marker both an icon tap and a card
 *   tap arrive as the same launcher intent — there is no
 *   documented or observable difference in the library. AALyrics,
 *   the reference media app, sets no session activity and never
 *   faces this: it is a pure media app, so the host draws its card
 *   and its now-playing screen from the MediaSession and there is
 *   no CarAppService screen to open.
 * - Even with a distinguisher, finishing the icon launch
 *   (`CarContext.finishCarApp`) destroys the session, and until
 *   v1.0.21 the mirror media session was stopped in the session's
 *   onDestroy, so the card would have died with it.
 *
 * v1.0.19 only recorded the launch intent (and whether the mirror
 * session was already active) in the log. v1.0.21 acts on it: the
 * mirror is decoupled from the template session (gated on the
 * CarConnection projection state instead), so finishing an icon
 * launch no longer kills the card, and [decideLaunch] finishes
 * every launch that is not judged card-driven. The judgment is a
 * heuristic — see `LaunchDecision.kt` for exactly which signal
 * carries it and what remains unproven — and every launch is
 * still logged with this description, plus stored for the phone's
 * Settings screen (`LaunchDiagnostics.kt`), so the user's car
 * test settles what his head unit actually sends.
 *
 * Pure string formatting, so the description is unit-testable
 * without an Android runtime: the session extracts the strings
 * from the `Intent` and passes them in.
 */
internal fun describeLaunchIntent(
    action: String?,
    categories: Set<String>?,
    extrasKeys: Set<String>?,
): String {
    val categoryText = categories.orEmpty().sorted().joinToString(",")
    val extrasText = extrasKeys.orEmpty().sorted().joinToString(",")
    return "launch intent: action=${action ?: "(none)"} " +
        "categories=[$categoryText] extras=[$extrasText]"
}
