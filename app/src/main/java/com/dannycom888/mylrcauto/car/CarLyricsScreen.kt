package com.dannycom888.mylrcauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.util.Log
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.R
import com.dannycom888.mylrcauto.lyrics.LyricsState
import com.dannycom888.mylrcauto.media.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The words, on a car screen.
 *
 * Everything about *which* words is decided by [CarGlance]; this only turns
 * the answer into rows and decides when to ask the host to redraw. Two rows,
 * and the second one says "Next" out loud rather than relying on colour or
 * position to make the difference — a template's colour spans are the host's
 * to honour or ignore, and a driver working out which of two
 * identical-looking lines is the current one is a driver reading rather than
 * glancing.
 *
 * **Why this polls.** Nothing emits when a playhead advances — a media
 * session publishes a position and a timestamp, and everything after that is
 * arithmetic. So the screen recomputes on a slow tick and asks the host to
 * redraw only when the *rendered content* has actually changed, which for a
 * song is once a line: a few times a minute. That matters because template
 * updates are throttled by the host, and an app that invalidates on a timer
 * rather than on a change gets throttled for nothing.
 *
 * The tick loop only runs while the screen is STARTED, though, and in
 * split-screen / dashboard this host drops the screen to STOPPED — so a
 * second, state-driven collector (see init) watches the media snapshot,
 * the lyrics state and the permission flag directly, and recomputes on
 * exactly the events the tick loop misses when stopped: a track change,
 * a lookup finishing, play/pause.
 */
class CarLyricsScreen(
    carContext: CarContext,
    private val container: AppContainer,
    private val surface: CarSurfaceState,
) : Screen(carContext) {

    /** What the host is currently showing, so a redraw can be skipped when nothing has changed. */
    private var shown: Content? = null

    /**
     * The surface-ask window (v1.0.29): when the currently
     * foregrounded stretch began asking for a surface that is not
     * live yet, and whether that window has closed unanswered.
     *
     * The ask is the surface template itself (see chooseTemplate's
     * speculative branch): presenting it is the only app-side act
     * that provokes the host's surface offer. The window bounds
     * the cost on a host that never answers — a few seconds of the
     * surface template's title strip, then the working three-line
     * pane — and it is per-foregrounding: leaving the big screen
     * (or the surface arriving) resets it, so the next card tap
     * asks afresh. Owned here rather than in chooseTemplate,
     * which stays a pure function of its inputs.
     */
    private var surfaceAskSinceMs: Long? = null
    private var surfaceAskExhausted: Boolean = false

    internal data class Content(
        val glance: CarGlance,
        val title: String,
        val artist: String,
        val transport: Transport,
        val playing: Boolean,
        /**
         * Whether the app is drawing itself on the car's surface.
         *
         * Part of the compared value rather than read at draw time, so a
         * surface arriving a second after the screen opens swaps the
         * template instead of leaving the fallback up until the next line
         * changes.
         */
        val onSurface: Boolean,
    )

    init {
        // Invalidate the moment the surface comes or goes — NOT gated on
        // STARTED like the tick loop below. A host that takes the surface
        // away in split-screen (or grants one) without the screen
        // redrawing would otherwise leave the wrong template up until the
        // next lyric line changes; lifecycleScope survives STOPPED, so this
        // fires even while the screen is covered.
        lifecycleScope.launch {
            surface.live.collect { invalidate() }
        }
        lifecycleScope.launch {
            // State-driven recompute — NOT gated on STARTED, like the
            // liveness collector above. The tick loop below only runs
            // while the screen is STARTED, and in split-screen /
            // dashboard this host drops the screen to STOPPED: then no
            // track change and no lookup finishing ever reaches
            // compose(), and the template in the slot goes stale — it
            // keeps showing whatever it showed when the app left
            // full-screen, instead of following the song. The media
            // snapshot, the lyrics state and the permission flag are
            // StateFlows, so collecting them here fires on exactly the
            // events the tick loop misses: a new track, a lookup
            // finishing, a permission change, play/pause. The snapshot
            // also ticks every 500ms while playing (the repository's
            // position ticker), so this collector fires at the same rate
            // as the tick loop — the `next != shown` comparison is the
            // guard that keeps a position-only advance from
            // invalidating. No MIN_REDRAW_GAP_MS here: state emissions
            // are rare (a track change, a lookup finishing), and one of
            // those must not wait a second behind a timer.
            combine(
                container.media.snapshot,
                container.lyrics.state,
                container.media.permissionGranted,
            ) { _, _, _ -> }
                .collect {
                    val next = runCatching { compose() }.onFailure {
                        Log.w(TAG, "state-driven compose failed, keeping the last template", it)
                        container.recordCarError(CarErrorSite.COMPOSE_STATE, it)
                    }.getOrNull() ?: return@collect
                    if (shouldInvalidateOnStateChange(next, shown)) invalidate()
                }
        }
        lifecycleScope.launch {
            // STARTED rather than CREATED: a screen with another pushed over
            // it is not visible, and recomputing lyrics nobody can see is
            // exactly the kind of work this app tries not to do.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastRedrawAt = 0L
                while (true) {
                    // Guarded, like onGetTemplate: one bad compose must not
                    // kill the tick loop (and with it every later redraw).
                    val next = runCatching { compose() }.onFailure {
                        Log.w(TAG, "compose failed, keeping the last template", it)
                        container.recordCarError(CarErrorSite.COMPOSE_TICK, it)
                    }.getOrNull()
                    val now = System.currentTimeMillis()
                    if (next != null && next != shown && now - lastRedrawAt >= MIN_REDRAW_GAP_MS) {
                        lastRedrawAt = now
                        invalidate()
                    }
                    delay(TICK_MS)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Composed fresh rather than read from the field the tick loop wrote:
        // the host asks for a template at moments of its own choosing, and
        // answering with whatever was true a second ago would put a stale
        // line on the screen.
        //
        // Guarded in two stages, because the two failure points cost
        // different things (v1.0.27). A compose that throws never produced
        // a content at all, so the emergency pane is built from the raw
        // snapshot, as before. A compose that SUCCEEDED carries real
        // lyrics in its glance, though — so when only the template BUILD
        // throws (an action the host's constraints reject, an icon that
        // fails to wrap), the emergency pane is built from that content
        // and keeps its lyric rows: the failure costs the chrome, not the
        // words. Either way the answer is real content, never a loading
        // pane and never any error text — v1.0.19's catch answered with a
        // loading pane, and a build that kept failing left the big screen
        // on an endless spinner with nothing on it.
        //
        // Every return also leaves a render record (see
        // CarRenderDiagnostics): which pane went back, what the lyrics
        // side had, and the exception when the catch fired — the
        // 2026-11-13 photo (title and artist only, dead back arrow)
        // matched three different panes, and nothing recorded which.
        val content = try {
            compose()
        } catch (e: Exception) {
            Log.w(TAG, "compose failed, showing the emergency pane instead", e)
            container.recordCarError(CarErrorSite.ON_GET_TEMPLATE, e)
            recordRender(content = null, error = e)
            return emergencyPaneGuarded(content = null)
        }
        shown = content
        return try {
            val template = content.toTemplate()
            recordRender(content, error = null)
            template
        } catch (e: Exception) {
            Log.w(TAG, "template build failed, showing the emergency pane instead", e)
            container.recordCarError(CarErrorSite.ON_GET_TEMPLATE, e)
            recordRender(content, error = e)
            emergencyPaneGuarded(content)
        }
    }

    /**
     * [emergencyPane] behind its own guard (v1.0.30): the
     * fallback was the one unguarded step left in onGetTemplate —
     * a throw inside the safety net itself would have escaped to
     * the host exactly like the failure it was sheltering from.
     * If even the emergency pane cannot be built, the answer is
     * [minimalPane]: one row, no actions, nothing left to fail
     * but a string read — and if THAT throws, there is nothing
     * an app can return anyway.
     */
    private fun emergencyPaneGuarded(content: Content?): Template =
        runCatching { emergencyPane(content) }
            .onFailure {
                Log.w(TAG, "the emergency pane itself failed, showing the minimal pane", it)
                container.recordCarError(CarErrorSite.EMERGENCY_PANE, it)
            }
            .getOrElse { minimalPane() }

    /** One row with the app name — the last template that can be built. */
    private fun minimalPane(): Template {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(carContext.getString(R.string.app_name)).build())
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(carPaneHeaderAction())
            .build()
    }

    /** The last render record this screen wrote, for change-dedup (see [recordRender]). */
    private var lastRenderRecord: CarRenderRecord? = null

    /**
     * Records what the big screen just answered (Settings → "Big
     * screen render"), so the next car run names the pane instead of
     * leaving a photo to guess from. Guarded end to end: diagnostics
     * must never be the reason a template fails. Deduped on change —
     * a pane that stays up across a hundred template asks (every
     * line change asks again) leaves one record, not a hundred
     * preference writes.
     */
    private fun recordRender(content: Content?, error: Exception?) {
        runCatching {
            val glance = content?.glance ?: runCatching {
                val snapshot = container.media.snapshot.value
                CarGlance.of(
                    state = container.lyrics.state.value,
                    hasTrack = snapshot.hasTrack,
                    permissionGranted = container.media.permissionGranted.value,
                    positionMs = snapshot.playback.currentMs(),
                )
            }.getOrDefault(CarGlance.None)
            val record = carRenderRecord(
                timestampMs = System.currentTimeMillis(),
                onSurface = content?.onSurface ?: false,
                glance = glance,
                lineCount = runCatching {
                    (container.lyrics.state.value as? LyricsState.Loaded)
                        ?.document?.lines?.size ?: 0
                }.getOrDefault(0),
                surfaceLive = surface.live.value,
                surfaceWidthDp = surface.surfaceWidthDp.value,
                visibleAreaReported = surface.visibleArea.value != null,
                visibleAreaEmpty = surface.visibleArea.value?.isEmpty == true,
                screenStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                apiLevel = carContext.carAppApiLevel,
                surfaceCallbackRegistered = surface.callbackRegistered.value,
                surfaceCallbackFired = surface.callbackFired.value,
                surfaceContainerWidthPx = surface.containerWidthPx.value,
                surfaceContainerHeightPx = surface.containerHeightPx.value,
                surfaceFailure = surface.surfaceFailure.value,
                error = error,
            )
            if (record.sameRenderAs(lastRenderRecord)) return
            lastRenderRecord = record
            container.carRenderDiagnostics.record(record)
        }
    }

    /**
     * The last-resort template, built when composing or building the
     * real pane threw. With a [content] (the build failed, the
     * compose did not) it keeps everything the failure did not touch:
     * the title/artist row AND the lyric rows — an emergency pane
     * that drops lyrics it already had is how a chrome failure became
     * a blank big screen. Without one (compose itself threw) it is
     * built straight from the current snapshot, as before: reading
     * the snapshot and building rows from two strings cannot
     * realistically fail, and if the snapshot itself is unreadable
     * the app name alone fills the row. No transport strip either
     * way: building actions is part of what this path is sheltering
     * from, and the next successful compose restores them.
     */
    private fun emergencyPane(content: Content?): Template {
        val snapshot = if (content == null) {
            runCatching { container.media.snapshot.value }.getOrNull()
        } else {
            null
        }
        val trackTitle = content?.title
            ?: snapshot?.track?.title?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.app_name)
        val artist = content?.artist
            ?: snapshot?.track?.artist?.takeIf { it.isNotBlank() }.orEmpty()
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(trackTitle)
                    .apply { if (artist.isNotEmpty()) addText(artist) }
                    .build(),
            )
        (content?.glance as? CarGlance.Now)?.let { pane.addLyricRows(it) }
        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(carPaneHeaderAction())
            .build()
    }

    private fun compose(): Content {
        val snapshot = container.media.snapshot.value
        val playback = snapshot.playback

        val glance = CarGlance.of(
            state = container.lyrics.state.value,
            hasTrack = snapshot.hasTrack,
            permissionGranted = container.media.permissionGranted.value,
            positionMs = playback.currentMs(),
        )

        val screenStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val surfaceLiveNow = surface.live.value
        val surfaceRegistered = surface.callbackRegistered.value

        // The surface-ask window's bookkeeping (see the fields'
        // doc). Only a STARTED screen asks: a covered screen is in
        // the dashboard slot, where the three-line card is the
        // answer and no surface is wanted. A live surface, or
        // leaving the foreground, resets the window.
        if (!screenStarted || surfaceLiveNow) {
            surfaceAskSinceMs = null
            surfaceAskExhausted = false
        } else if (surfaceRegistered && !surfaceAskExhausted) {
            val nowMs = System.currentTimeMillis()
            val since = surfaceAskSinceMs ?: nowMs.also { surfaceAskSinceMs = it }
            if (nowMs - since >= SURFACE_ASK_WINDOW_MS) surfaceAskExhausted = true
        }
        val surfaceRequested = screenStarted && !surfaceLiveNow &&
            surfaceRegistered && !surfaceAskExhausted

        return Content(
            glance = glance,
            title = snapshot.track?.title?.takeIf { it.isNotBlank() }
                ?: carContext.getString(R.string.app_name),
            artist = snapshot.track?.artist?.takeIf { it.isNotBlank() }.orEmpty(),
            transport = snapshot.transport,
            playing = playback.isPlaying,
            // Which template shows the lyrics is one decision, made in one
            // place (chooseTemplate) so it can be unit-tested: a live
            // surface alone is not enough — it also has to be wide enough
            // to be a full screen rather than a split-screen slot, the
            // host has to actually be showing it, and the screen itself
            // has to be STARTED (the full-screen foreground app) — a
            // covered screen in the dashboard slot falls back to the
            // three-line card even if the host kept the surface live.
            // And with no surface yet, a registered callback inside its
            // ask window still returns the surface template: presenting
            // it is how the app asks for the surface at all (v1.0.29).
            onSurface = chooseTemplate(
                surfaceLive = surfaceLiveNow,
                apiLevel = carContext.carAppApiLevel,
                surfaceWidthDp = surface.surfaceWidthDp.value,
                visibleAreaEmpty = surface.visibleArea.value?.isEmpty == true,
                screenStarted = screenStarted,
                surfaceRequested = surfaceRequested,
            ) == TemplateChoice.ON_SURFACE,
        )
    }

    // ---- turning an answer into a template ---------------------------------

    private fun Content.toTemplate(): Template {
        // With a surface, the app is already drawing the lyrics itself and
        // the template's job is only the chrome around them: what is playing,
        // and the controls. Without one — an older host, or one that declines
        // a surface — the same screen falls back to two lines of text, which
        // is a complete answer rather than a degraded one.
        if (onSurface) return onSurfaceTemplate()
        return fallback()
    }

    private fun Content.onSurfaceTemplate(): Template {
        // Title strip only. The surface itself is already drawing the full
        // lyrics list, so repeating lines here would just duplicate them —
        // but the strip cannot go away entirely: MapWithContentTemplate
        // needs a content pane, and without one the host shows no surface
        // at all.
        //
        // The pane carries the app name as its title strip, above the
        // song title and artist row: the pane shows "My LRC Auto" as
        // well as what is playing. v1.0.10 dropped the strip to narrow
        // the host-drawn box so the lyrics got more room; it is back
        // because the pane should name the app, and the strip costs
        // only a little width.
        //
        // The cover is NOT the pane's image: this car's system ignores
        // Pane.setImage in the content pane (two photos: title and artist
        // show, no image at all, not even the fallback icon), so the cover
        // lives in the surface instead (CarLyricsContent), where the app
        // draws it itself. The three-line card (previous / current / next)
        // lives in the fallback template, which is what the car shows in
        // split-screen, where the host draws the template and grants no
        // surface.
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(title)
                    .apply { if (artist.isNotEmpty()) addText(artist) }
                    .build(),
            )
            .build()

        val content = PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(carPaneHeaderAction())
            .build()

        val template = MapWithContentTemplate.Builder()
            .setContentTemplate(content)

        guardedTransportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    private fun Content.fallback(): Template = when (glance) {
        is CarGlance.Now -> lyrics(glance)
        // Still looking: the neutral pane — title and artist, real
        // content — NOT a loading pane. v1.0.19 (and earlier) answered
        // Looking with a loading template; combined with the
        // visible-area gate that kept the surface template unreachable
        // for the whole session (see TemplateChoice), a slow or stuck
        // first lookup left the big screen on that spinner forever,
        // with nothing else on it. A lookup in progress now shows the
        // same neutral pane as a finished no-match, and the state
        // collectors invalidate to the lyrics pane the moment the
        // lookup lands.
        CarGlance.Looking -> neutralPane()

        CarGlance.Silent -> message(R.string.car_silent, R.string.car_silent_body)
        // No lyrics for this track: a neutral pane with the title and
        // artist, and nothing else. The "no lyrics" text flashed on
        // every song switch and was deleted — a completed lookup with
        // no match shows the same neutral pane as a lookup still
        // running, with no words claiming anything is missing.
        CarGlance.None -> neutralPane()
        CarGlance.Untimed -> message(R.string.car_untimed, R.string.car_untimed_body)
        CarGlance.NoPermission -> message(R.string.car_permission, R.string.car_permission_body)
    }

    /**
     * The pane with only the title and artist: what the fallback shows
     * when there are no lyric lines to draw — no message, no claim.
     * Same rows as the on-surface pane, so the two cannot disagree
     * about what is playing.
     */
    private fun Content.neutralPane(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(title)
                    .apply { if (artist.isNotEmpty()) addText(artist) }
                    .build(),
            )
        val strip = paneTransport(pane)
        val template = PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(carPaneHeaderAction())
        strip?.let { template.setActionStrip(it) }
        return template.build()
    }

    private fun Content.lyrics(glance: CarGlance.Now): Template {
        val pane = Pane.Builder().addLyricRows(glance)
        val strip = paneTransport(pane)

        val template = PaneTemplate.Builder(pane.build())
            .setTitle(title)
            .setHeaderAction(carPaneHeaderAction())

        strip?.let { template.setActionStrip(it) }
        return template.build()
    }

    /**
     * The fallback pane's three lyric rows — previous, current, next;
     * the middle position identifies the current line, so there is no
     * "Next:" prefix to read. Shared by the lyrics pane and the
     * emergency pane (v1.0.27), so a template-build failure keeps the
     * same words it would have shown anyway.
     */
    private fun Pane.Builder.addLyricRows(glance: CarGlance.Now): Pane.Builder {
        glance.previous?.let { previous ->
            addRow(Row.Builder().setTitle(previous).build())
        }
        addRow(
            Row.Builder()
                .setTitle(
                    glance.line?.takeIf { it.isNotBlank() }
                        ?: carContext.getString(R.string.car_instrumental),
                )
                .build(),
        )
        glance.next?.let { next ->
            addRow(Row.Builder().setTitle(next).build())
        }
        return this
    }

    /**
     * [stripOf] behind a guard (v1.0.27): the strip is chrome, and
     * building it touches the library's action constraints — a strip
     * that fails costs the buttons, not the whole pane. This is the
     * assembly for the surface template (MapWithContentTemplate),
     * whose strip may hold up to
     * [MAP_TEMPLATE_MAX_STRIP_ACTIONS] actions, so the full transport
     * fits there unchanged. PaneTemplates assemble their transport
     * through [paneTransport] instead — their strip cap is lower
     * (v1.0.28, see there).
     */
    private fun Content.guardedTransportStrip(): ActionStrip? =
        runCatching { stripOf(carTransportFor(transport)) }
            .onFailure { Log.w(TAG, "transport strip failed, showing the pane without it", it) }
            .getOrNull()

    /**
     * The transport controls for a PaneTemplate (v1.0.28), split by
     * [paneTransportFor] across the two action containers the
     * template has: the skips in the action strip, play/pause as the
     * pane's own action.
     *
     * The split exists because the containers' caps differ and the
     * strip's is the smaller one: `PaneTemplate.setActionStrip`
     * validates the strip against the library's
     * ACTIONS_CONSTRAINTS_SIMPLE — at most
     * [PANE_TEMPLATE_MAX_STRIP_ACTIONS] actions — while a full
     * transport is three. Up to v1.0.27 the whole strip went to
     * `setActionStrip`, which threw "Action list exceeded max
     * number of 2 actions" for every full-transport pane; the call
     * sat outside the strip's own guard (the guard covered building
     * the strip, which validates nothing about counts), so the throw
     * escaped to onGetTemplate's catch and the driver got the
     * title-only emergency pane — the 2026-11-13 07:39 render record
     * names exactly that exception. The pane itself may carry up to
     * [PANE_MAX_ACTIONS] actions (validated at build against
     * ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION), and play/pause
     * — the one titled, state-changing button — is the natural pane
     * action.
     *
     * Adds the pane actions to [pane] and returns the strip, or null
     * when the session earned no skips. Everything is built behind
     * one guard, and the actions are all built BEFORE any is added:
     * a failure (an icon the library rejects) costs the controls,
     * never leaves the pane half-wired, and never costs the rows.
     */
    private fun Content.paneTransport(pane: Pane.Builder): ActionStrip? {
        val split = paneTransportFor(carTransportFor(transport))
        if (split.strip.isEmpty() && split.paneActions.isEmpty()) return null
        val built = runCatching {
            val paneActions = split.paneActions.map { transportAction(it) }
            val strip = stripOf(split.strip)
            paneActions to strip
        }.onFailure {
            Log.w(TAG, "transport controls failed, showing the pane without them", it)
        }.getOrNull() ?: return null
        built.first.forEach { pane.addAction(it) }
        return built.second
    }

    /**
     * An action strip holding [buttons], or nothing at all.
     *
     * Null rather than an empty strip, and that is not tidiness:
     * `ActionStrip.Builder.build()` throws "Action strip must contain at
     * least one action", and a session advertising only `ACTION_SEEK_TO`
     * has `transport.any` true while earning no buttons at all — which would
     * have taken down both car templates for that one player.
     */
    private fun Content.stripOf(buttons: List<CarTransport>): ActionStrip? {
        if (buttons.isEmpty()) return null

        val strip = ActionStrip.Builder()
        for (button in buttons) {
            strip.addAction(transportAction(button))
        }
        return strip.build()
    }

    /** One transport button as an [Action] — the same button whichever container it lands in. */
    private fun Content.transportAction(button: CarTransport): Action = when (button) {
        CarTransport.PREVIOUS -> action(R.drawable.ic_car_previous, null) {
            container.media.skipPrevious()
        }

        CarTransport.PLAY_PAUSE -> action(
            if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play,
            if (playing) R.string.car_pause else R.string.car_play,
        ) { container.media.togglePlayPause() }

        CarTransport.NEXT -> action(R.drawable.ic_car_next, null) {
            container.media.skipNext()
        }
    }

    /**
     * One button, with a title only where the templates allow one.
     *
     * This started out as icon *and* title on all three, for the screen
     * reader — `Action` has no content description of its own, so a title is
     * the only label there is. The library says no: a PaneTemplate's
     * action strip (ACTIONS_CONSTRAINTS_SIMPLE) permits exactly **one**
     * action with a custom title and throws on the second — and since
     * v1.0.28 the strip holds only the icon-only skips anyway, while
     * play/pause's title rides on its pane action, whose constraints
     * (ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION) allow up to two.
     * The surface template's strip (ACTIONS_CONSTRAINTS_NAVIGATION)
     * allows four titles, so the same single title works there too.
     */
    private fun action(icon: Int, label: Int?, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .apply { label?.let { setTitle(carContext.getString(it)) } }
            .setOnClickListener {
                onClick()
                // The player answers by publishing a new state, which the
                // next tick picks up — but a button that looks unchanged for
                // half a second reads as one that did not work.
                invalidate()
            }
            .build()

    private fun message(title: Int, body: Int): Template =
        MessageTemplate.Builder(carContext.getString(body))
            .setTitle(carContext.getString(title))
            .setHeaderAction(carPaneHeaderAction())
            .build()

    private companion object {

        const val TAG = "MyLrcAutoCarScreen"

        /**
         * How often the playhead is re-examined. Half a second is
         * imperceptible against a lyric line that lasts three, and it is a
         * comparison of two small values rather than any drawing.
         */
        const val TICK_MS = 500L

        /**
         * A floor between redraws, whatever the words do.
         *
         * The host throttles template updates and will drop or penalise an
         * app that pushes them too fast. A line every three seconds is
         * nowhere near that, but a fast verse can put two lines inside a
         * second, and the fix is to let the second one wait for the next tick.
         */
        const val MIN_REDRAW_GAP_MS = 1_000L

        /**
         * How long a foregrounded big screen presents the surface
         * template speculatively — the ask — before concluding the
         * host is not going to offer a surface this time and
         * returning the three-line pane (v1.0.29). Hosts that do
         * answer do so within about a second of the template going
         * up (the working runs' surface arrived ~1s after open);
         * five seconds is generous headroom, not a wait the driver
         * is expected to sit through often.
         */
        const val SURFACE_ASK_WINDOW_MS = 5_000L
    }
}

/** A transport button, named before it becomes an `Action` the host can validate. */
internal enum class CarTransport { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * Which transport buttons a session has earned, in the order they belong on screen.
 *
 * Separated out because the two mistakes it prevents are both invisible until
 * a particular player is playing: a session offering only a seek would
 * otherwise produce an empty action strip, which throws, and the count
 * decides whether a title can be afforded at all.
 */
internal fun carTransportFor(transport: Transport): List<CarTransport> = buildList {
    if (transport.skipPrevious) add(CarTransport.PREVIOUS)
    if (transport.playPause) add(CarTransport.PLAY_PAUSE)
    if (transport.skipNext) add(CarTransport.NEXT)
}

/**
 * Max actions in a `PaneTemplate`'s action strip: the Car App Library
 * 1.7.0's ACTIONS_CONSTRAINTS_SIMPLE (maxActions 2, maxCustomTitles 1),
 * enforced by `PaneTemplate.Builder.setActionStrip`. Read from the
 * library's own bytecode, not the docs — the docs do not print the
 * number, and v1.0.27 shipped three actions against it.
 */
internal const val PANE_TEMPLATE_MAX_STRIP_ACTIONS = 2

/**
 * Max actions a `Pane` itself may carry inside a `PaneTemplate`: the
 * library's ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION (maxActions
 * 2, maxCustomTitles 2, maxPrimaryActions 1), enforced by
 * `PaneTemplate.Builder.build` against `Pane.getActions()`.
 */
internal const val PANE_MAX_ACTIONS = 2

/**
 * Max actions in a `MapWithContentTemplate`'s action strip: the
 * library's ACTIONS_CONSTRAINTS_NAVIGATION (maxActions 4), enforced
 * by its `setActionStrip`. A full three-button transport fits, which
 * is why the surface template never hit the pane template's cap.
 */
internal const val MAP_TEMPLATE_MAX_STRIP_ACTIONS = 4

/** Where a pane template's transport buttons live, split by container. */
internal data class PaneTransport(
    /** Buttons for the template's action strip, in display order. */
    val strip: List<CarTransport>,
    /** Buttons carried by the pane itself, in display order. */
    val paneActions: List<CarTransport>,
)

/**
 * Splits a transport for a `PaneTemplate` across its two action
 * containers, so the pane builds at all (v1.0.28).
 *
 * A full transport is three buttons and the template's strip holds
 * at most [PANE_TEMPLATE_MAX_STRIP_ACTIONS] — the assembly that put
 * all three in the strip threw at `setActionStrip` every time (see
 * CarLyricsScreen.paneTransport for the device evidence). The skips
 * stay together in the strip, in order — the strip is where this
 * app's transport lives on every other template, and the pair reads
 * as one control — and play/pause, the one titled and
 * state-changing button, becomes the pane's own action, whose
 * container allows [PANE_MAX_ACTIONS]. Every earned button survives
 * exactly once; nothing scales with the song's line count, which is
 * the point — the pane's rows are previous/current/next however
 * long the song is.
 */
internal fun paneTransportFor(buttons: List<CarTransport>): PaneTransport =
    PaneTransport(
        strip = buttons.filter { it != CarTransport.PLAY_PAUSE },
        paneActions = buttons.filter { it == CarTransport.PLAY_PAUSE },
    )

/**
 * The state-driven collector's rule, extracted so it is unit-testable:
 * invalidate exactly when the recomposed content differs from what the
 * host is showing — including the first compose (shown == null), a
 * lookup finishing (None → Now), a track change, or play/pause — and
 * never on a position-only advance, which composes to identical content
 * (the 500ms snapshot ticker fires while playing, so this comparison is
 * the guard that keeps the collector from spamming invalidates).
 */
internal fun shouldInvalidateOnStateChange(
    next: CarLyricsScreen.Content,
    shown: CarLyricsScreen.Content?,
): Boolean = next != shown

/**
 * The header action every big-screen pane carries — the ONE wiring
 * point for it (v1.0.27).
 *
 * `Action.BACK` is the Car App Library's standard back action:
 * the host gives it the arrow, and tapping it makes the host call
 * back into the app (`IAppManager.onBackPressed` →
 * `CarContext`'s OnBackPressedDispatcher). The header cannot
 * carry the exit itself — the header constraint
 * (ACTIONS_CONSTRAINTS_HEADER, library bytecode) forbids
 * on-click listeners outright — so what a press DOES is decided
 * by the dispatcher: the session registers the callback that
 * finishes the car app (see CarSession.ensureBackCallback;
 * v1.0.29). Before that registration existed, a press fell
 * through to the library's fallback, `ScreenManager.pop()`,
 * which is a no-op on a one-screen stack: the dead arrow in the
 * 2026-11-13 car tests.
 *
 * Every pane in this screen sets its header through this
 * function, so the wiring cannot drift between the main panes
 * and the emergency pane, and a unit test pins that the value
 * handed over is the library's standard back action itself.
 */
internal fun carPaneHeaderAction(): Action = Action.BACK
