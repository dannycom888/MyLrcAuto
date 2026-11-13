package com.dannycom888.mylrcauto.car

/**
 * Describing the intent a car session was launched with — the item-7
 * diagnostic (v1.0.19).
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
 *   (`CarContext.finishCarApp`) destroys the session — the AIDL
 *   has no explicit session-destroy call; a session dies only when
 *   the host unbinds (per session) or the service is destroyed —
 *   and the current code stops the mirror media session in the
 *   session's onDestroy, so the card would die with it. Keeping
 *   the mirror alive after a finish while stopping it when the
 *   car disconnects has no library-level signal either: the
 *   service's onDestroy fires in both cases. A delayed stop is
 *   not the answer — the card has to survive a whole drive, not
 *   a few seconds.
 *
 * So v1.0.19 does not implement the two-tap flow. What it does is
 * record the launch intent (and whether the mirror session was
 * already active) in the log, so the follow-up is built on what
 * the user's car actually sends instead of on a guess: if the
 * intents do differ, the follow-up can key the finish on that;
 * if they do not, the follow-up needs a session-activity marker
 * plus a car-gone detector (Bluetooth/USB disconnect) before the
 * finish is safe to add. Until then the flow stays as it is:
 * icon tap opens the big screen, Home/BACK returns to the
 * dashboard with the card, card tap reopens the big screen.
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
