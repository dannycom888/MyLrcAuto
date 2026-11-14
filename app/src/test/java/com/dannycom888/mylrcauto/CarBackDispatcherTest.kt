package com.dannycom888.mylrcauto

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dispatch half of the v1.0.29 back-arrow fix, pinned against
 * the real androidx.activity classes the fix is built on.
 *
 * The mechanism (Car App Library 1.7.0 bytecode, see
 * CarSession.ensureBackCallback): a tap on the header's
 * Action.BACK makes the host call IAppManager.onBackPressed,
 * which the library routes to
 * `CarContext.getOnBackPressedDispatcher().onBackPressed()`.
 * With no enabled callback the dispatcher's fallback runs — in
 * the library that fallback is ScreenManager.pop(), a no-op on
 * this app's one-screen stack, which is why the arrow was dead.
 * CarSession registers an enabled callback whose press finishes
 * the car app instead.
 *
 * These tests pin the dispatcher semantics that fix relies on:
 * an enabled callback intercepts the press (the fallback never
 * runs), and a disabled one lets the fallback through — so if a
 * future androidx.activity update changed either behaviour, the
 * fix's premise would fail here first, not on the car.
 */
class CarBackDispatcherTest {

    @Test
    fun withNoCallbackTheFallbackRuns() {
        var fallbackRuns = 0
        val dispatcher = OnBackPressedDispatcher { fallbackRuns++ }
        dispatcher.onBackPressed()
        assertEquals(1, fallbackRuns)
    }

    @Test
    fun anEnabledCallbackInterceptsAndTheFallbackDoesNotRun() {
        var fallbackRuns = 0
        var callbackRuns = 0
        val dispatcher = OnBackPressedDispatcher { fallbackRuns++ }
        dispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    callbackRuns++
                }
            },
        )
        dispatcher.onBackPressed()
        assertEquals(1, callbackRuns)
        assertEquals(0, fallbackRuns)
    }

    @Test
    fun aDisabledCallbackLetsTheFallbackThrough() {
        var fallbackRuns = 0
        var callbackRuns = 0
        val dispatcher = OnBackPressedDispatcher { fallbackRuns++ }
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                callbackRuns++
            }
        }
        dispatcher.addCallback(callback)
        dispatcher.onBackPressed()
        assertEquals(0, callbackRuns)
        assertEquals(1, fallbackRuns)
    }
}
