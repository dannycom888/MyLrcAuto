package com.dannycom888.mylrcauto

import androidx.car.app.model.Action
import com.dannycom888.mylrcauto.car.carPaneHeaderAction
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The big-screen back-arrow wiring (v1.0.27): every pane in the
 * car screen sets its header through the one wiring point,
 * [carPaneHeaderAction] — this pins that what it hands the host
 * is the Car App Library's standard BACK action itself (identity,
 * not a look-alike), the action the host turns into "leave this
 * screen". The 2026-11-13 car test reported the arrow dead on the
 * broken pane; with the wiring pinned here, a dead arrow on a
 * future run is the host's side of the contract, and the render
 * record (CarRenderDiagnostics) says which pane it happened on.
 */
class CarPaneHeaderTest {

    @Test
    fun thePaneHeaderActionIsTheLibraryBackAction() {
        assertSame(Action.BACK, carPaneHeaderAction())
    }
}
