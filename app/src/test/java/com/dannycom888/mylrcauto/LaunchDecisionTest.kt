package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.LaunchConnectionState
import com.dannycom888.mylrcauto.car.LaunchDecision
import com.dannycom888.mylrcauto.car.LaunchEntry
import com.dannycom888.mylrcauto.car.LaunchSignals
import com.dannycom888.mylrcauto.car.decideLaunch
import com.dannycom888.mylrcauto.car.isCardDrivenLaunch
import com.dannycom888.mylrcauto.car.isPlainLauncherIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 7 (v1.0.21) — the two-tap launch decision, as the full
 * scenario table the user's spec requires. Icon taps always land
 * on the dashboard; only a launch judged card-driven may show the
 * big screen. The judgment's only distinguishing signal is the
 * intent's shape beyond the plain launcher profile (the library
 * exposes nothing else), gated on the card actually having been
 * up (mirror already active) — these tests pin both the table and
 * the gate, so the heuristic's exact behaviour is on record
 * before the car test confirms or refutes it.
 */
class LaunchDecisionTest {

    /** What an icon tap sends: MAIN + LAUNCHER, no extras. */
    private fun iconTap(
        entry: LaunchEntry = LaunchEntry.CREATE_SCREEN,
        mirrorAlreadyActive: Boolean,
        bigScreenShown: Boolean = false,
    ) = LaunchSignals(
        entry = entry,
        intentAction = "android.intent.action.MAIN",
        intentCategories = setOf("android.intent.category.LAUNCHER"),
        intentExtrasKeys = emptySet(),
        mirrorAlreadyActive = mirrorAlreadyActive,
        bigScreenShownThisConnection = bigScreenShown,
    )

    /** A launch carrying something a plain icon tap does not — the card-driven candidate. */
    private fun cardTap(
        entry: LaunchEntry = LaunchEntry.CREATE_SCREEN,
        mirrorAlreadyActive: Boolean = true,
        bigScreenShown: Boolean = false,
    ) = LaunchSignals(
        entry = entry,
        intentAction = "android.intent.action.MAIN",
        intentCategories = setOf("android.intent.category.LAUNCHER"),
        intentExtrasKeys = setOf("androidx.car.app.extra.STARTED_FROM_MEDIA_CARD"),
        mirrorAlreadyActive = mirrorAlreadyActive,
        bigScreenShownThisConnection = bigScreenShown,
    )

    @Test
    fun scenario1_coldIconTapWithNoCardGoesToDashboard() {
        // No card yet (mirror never active) and a plain launcher
        // intent: nothing to open the big screen for.
        assertEquals(
            LaunchDecision.FINISH_TO_DASHBOARD,
            decideLaunch(iconTap(mirrorAlreadyActive = false)),
        )
    }

    @Test
    fun scenario2_cardTapShowsTheBigScreen() {
        assertEquals(
            LaunchDecision.SHOW_BIG_SCREEN,
            decideLaunch(cardTap()),
        )
    }

    @Test
    fun scenario2_cardTapOnLiveSessionShowsTheBigScreen() {
        assertEquals(
            LaunchDecision.SHOW_BIG_SCREEN,
            decideLaunch(cardTap(entry = LaunchEntry.NEW_INTENT)),
        )
    }

    @Test
    fun scenario3_iconTapWhileCardIsUpGoesToDashboard() {
        // The card is on the dashboard (mirror active) and the big
        // screen has not been shown yet this connection — an icon
        // tap still only shows the card.
        assertEquals(
            LaunchDecision.FINISH_TO_DASHBOARD,
            decideLaunch(iconTap(mirrorAlreadyActive = true, bigScreenShown = false)),
        )
    }

    @Test
    fun scenario4_iconTapAfterBigScreenWasShownGoesToDashboard() {
        // Icon always → card, even after the big screen was shown.
        assertEquals(
            LaunchDecision.FINISH_TO_DASHBOARD,
            decideLaunch(iconTap(mirrorAlreadyActive = true, bigScreenShown = true)),
        )
        assertEquals(
            LaunchDecision.FINISH_TO_DASHBOARD,
            decideLaunch(
                iconTap(
                    entry = LaunchEntry.NEW_INTENT,
                    mirrorAlreadyActive = true,
                    bigScreenShown = true,
                ),
            ),
        )
    }

    @Test
    fun cardTapAgainAfterBigScreenWasShownReopensIt() {
        // Back on the dashboard after visiting the big screen, a
        // card tap must open it again — bigScreenShown must not
        // veto a card-driven launch.
        assertEquals(
            LaunchDecision.SHOW_BIG_SCREEN,
            decideLaunch(cardTap(bigScreenShown = true)),
        )
    }

    @Test
    fun cardShapedIntentWithoutACardCannotShowTheBigScreen() {
        // No mirror → no card on the dashboard → nothing could
        // have been tapped; whatever sent this intent, it finishes.
        assertFalse(isCardDrivenLaunch(cardTap(mirrorAlreadyActive = false)))
        assertEquals(
            LaunchDecision.FINISH_TO_DASHBOARD,
            decideLaunch(cardTap(mirrorAlreadyActive = false)),
        )
    }

    @Test
    fun plainLauncherProfileIsExact() {
        assertTrue(
            isPlainLauncherIntent(
                "android.intent.action.MAIN",
                setOf("android.intent.category.LAUNCHER"),
                emptySet(),
            ),
        )
        // No action at all, no categories, no extras: still the
        // plain shape (some hosts send a bare intent).
        assertTrue(isPlainLauncherIntent(null, emptySet(), emptySet()))
        // Any extras key breaks the profile.
        assertFalse(
            isPlainLauncherIntent(
                "android.intent.action.MAIN",
                setOf("android.intent.category.LAUNCHER"),
                setOf("anything"),
            ),
        )
        // A different action breaks it.
        assertFalse(
            isPlainLauncherIntent(
                "android.intent.action.VIEW",
                setOf("android.intent.category.LAUNCHER"),
                emptySet(),
            ),
        )
        // An extra category breaks it.
        assertFalse(
            isPlainLauncherIntent(
                "android.intent.action.MAIN",
                setOf("android.intent.category.LAUNCHER", "com.example.OTHER"),
                emptySet(),
            ),
        )
    }

    @Test
    fun connectionStateTracksShownUntilDisconnect() {
        val state = LaunchConnectionState()
        assertFalse(state.bigScreenShown)
        state.onBigScreenShown()
        assertTrue(state.bigScreenShown)
        // A (re)connect report does not clear it; only a
        // disconnect ends the connection.
        state.onProjectionChanged(connected = true)
        assertTrue(state.bigScreenShown)
        state.onProjectionChanged(connected = false)
        assertFalse(state.bigScreenShown)
    }
}
