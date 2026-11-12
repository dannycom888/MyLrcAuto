package com.dannycom888.mylrcauto.car

import com.dannycom888.mylrcauto.media.MirrorActivation
import com.dannycom888.mylrcauto.media.PlayerSnapshot

/**
 * The car-app start path for the mirror media session.
 *
 * v1.0.13 started the service only from the snapshot hook, and that
 * proved not enough: the hook's background start was blocked while the
 * phone sat in the pocket, and the tracker had already recorded
 * "active", so `distinctUntilChanged` never re-fired it — the card
 * stayed Poweramp's even after the user opened the car app. So the
 * car app also starts the service itself, at the two moments it is
 * the foreground car app and a start is allowed: when the session is
 * created ([CarAppService.onCreateSession]) and when the main screen
 * is created ([CarSession.onCreateScreen]). That matches the user's
 * flow exactly: Poweramp selected, tap My LRC Auto, the car app
 * opens, the service starts, the session activates, the card takes
 * over.
 *
 * Only while a track is actually loaded — playing or paused — the
 * mirror's own rule ([MirrorActivation.shouldBeActive]). A paused
 * track still has a current lyric line for the card, and the snapshot
 * hook is the backstop for a track that loads after the car app opens.
 * Starting an already-running service is a no-op, so the two call
 * sites (and the hook) cannot double-start anything.
 *
 * v1.0.18: these call sites are inside the car session (session
 * creation, screen creation), so a car session is live whenever they
 * run — the car gate in [MirrorActivation.shouldBeActive] is met by
 * construction here. The snapshot hook is the one that needed the
 * explicit gate, because it fires with no car anywhere.
 *
 * Pure apart from the lambda the caller supplies, so the decision is
 * unit-testable without a Context.
 */
internal fun startMirrorFromCarApp(
    snapshot: PlayerSnapshot,
    startService: () -> Unit,
) {
    if (MirrorActivation.shouldBeActive(snapshot)) startService()
}
