package com.dannycom888.mylrcauto.car

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the car screen's drawing surface is doing, shared between the session
 * and the screens.
 *
 * Two facts, both of which the template has to react to:
 *
 * - **Whether there is a surface at all.** A host that does not grant one —
 *   an older Android Auto, or a head unit that declines — leaves the app
 *   with templates and nothing else. That is a supported outcome rather than
 *   a failure, so the screen watches this and falls back to the two-line pane
 *   instead of showing an empty box where the lyrics should be.
 * - **Which part of it is actually visible.** The host draws its own chrome
 *   over the surface and says where, so the lyrics are laid out inside that
 *   rectangle rather than underneath a header.
 */
class CarSurfaceState {

    private val _live = MutableStateFlow(false)

    /** True once the host has handed over a surface and it is being drawn on. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private val _visibleArea = MutableStateFlow<Rect?>(null)

    /**
     * The unobstructed part of the surface, in surface pixels.
     *
     * Null until the host says otherwise. The content draws nothing
     * until it arrives: v1.0.14 treated null as "all of it" on the
     * grounds that a first frame drawn edge to edge and corrected a
     * moment later beats one not drawn — but the car test showed the
     * correction itself is the bug (the lyrics appear on the left,
     * then the whole screen shifts right a second later when the
     * area arrives). v1.0.18 waits for the area, so the first frame
     * is already in the right place.
     */
    val visibleArea: StateFlow<Rect?> = _visibleArea.asStateFlow()

    private val _surfaceWidthDp = MutableStateFlow<Float?>(null)

    /**
     * The granted surface's width, in dp, or null when no surface is live.
     *
     * The screen uses it to tell a full-screen surface from a split-screen /
     * dashboard slot: a host that only shows a corner of the app still
     * reports the surface as live, so width is the only signal that the
     * template — not the surface — should be showing the lyrics.
     */
    val surfaceWidthDp: StateFlow<Float?> = _surfaceWidthDp.asStateFlow()

    // ---- the v1.0.29 surface-offer facts -----------------------------------
    //
    // "No surface" used to be a single fact (live == false), and three
    // different failures hide inside it: the callback was never registered,
    // it was registered but the host never answered it, or the host answered
    // and the app's own presentation failed. The 2026-11-13 19:07 car run
    // sat in one of those states for a whole song with the diagnostics
    // unable to say which. These facts are what separate them; the render
    // record (CarRenderDiagnostics) carries them to Settings.

    private val _callbackRegistered = MutableStateFlow(false)

    /** True once the session's surface callback is registered with the host. */
    val callbackRegistered: StateFlow<Boolean> = _callbackRegistered.asStateFlow()

    private val _callbackFired = MutableStateFlow(false)

    /**
     * True once the host has called `onSurfaceAvailable` at least once this
     * session — the offer arrived, whether or not it could be used.
     */
    val callbackFired: StateFlow<Boolean> = _callbackFired.asStateFlow()

    private val _containerWidthPx = MutableStateFlow<Int?>(null)

    /** Width of the most recently offered surface container, in px. */
    val containerWidthPx: StateFlow<Int?> = _containerWidthPx.asStateFlow()

    private val _containerHeightPx = MutableStateFlow<Int?>(null)

    /** Height of the most recently offered surface container, in px. */
    val containerHeightPx: StateFlow<Int?> = _containerHeightPx.asStateFlow()

    private val _surfaceFailure = MutableStateFlow<String?>(null)

    /**
     * Why the most recent surface offer could not be used (the virtual
     * display refused, or the presentation would not show), as
     * "ExceptionClass: message"; null when no offer has failed.
     */
    val surfaceFailure: StateFlow<String?> = _surfaceFailure.asStateFlow()

    internal fun onCallbackRegistered() {
        _callbackRegistered.value = true
    }

    internal fun onCallbackFired(widthPx: Int, heightPx: Int) {
        _callbackFired.value = true
        _containerWidthPx.value = widthPx
        _containerHeightPx.value = heightPx
    }

    internal fun onSurfaceFailure(failure: String) {
        _surfaceFailure.value = failure
    }

    internal fun onLive(live: Boolean) {
        _live.value = live
        if (!live) {
            _visibleArea.value = null
            _surfaceWidthDp.value = null
        }
    }

    internal fun onVisibleArea(area: Rect) {
        _visibleArea.value = Rect(area)
    }

    internal fun onSurfaceSize(widthDp: Float) {
        _surfaceWidthDp.value = widthDp
    }
}
