package com.dannycom888.mylrcauto

import androidx.car.app.model.Action
import com.dannycom888.mylrcauto.car.carPaneHeaderAction
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The big-screen back-arrow wiring: every pane in the car screen
 * sets its header through the one wiring point,
 * [carPaneHeaderAction] — this pins that what it hands the host
 * is the Car App Library's standard BACK action itself (identity,
 * not a look-alike), the action whose tap makes the host call
 * back into the app.
 *
 * The pin's meaning changed in v1.0.29. v1.0.27 read the dead
 * arrow in the 2026-11-13 car test as possibly the host's side
 * of the contract; the library bytecode says otherwise (see
 * CarSession.ensureBackCallback): the header forbids listeners,
 * a BACK tap lands on the CarContext's OnBackPressedDispatcher,
 * and the library's fallback — ScreenManager.pop() — is a no-op
 * on this app's one-screen stack. The exit therefore lives in
 * the session's registered callback, not in this action; this
 * action is the host's cue to send the press, and it stays
 * Action.BACK. The dispatcher's half of the mechanism is pinned
 * in CarBackDispatcherTest.
 */
class CarPaneHeaderTest {

    @Test
    fun thePaneHeaderActionIsTheLibraryBackAction() {
        assertSame(Action.BACK, carPaneHeaderAction())
    }
}
