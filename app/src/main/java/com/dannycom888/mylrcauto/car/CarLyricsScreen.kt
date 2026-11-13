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
        // Guarded: a template that throws takes the whole car app down with
        // the host's "unexpected error" dialog, and a bad bitmap or a bad
        // line is not worth that. The answer is a neutral loading pane —
        // loading state only, no error text: the big screen must never show
        // any prompt at all, and a template-build failure is a momentary
        // state the next tick recomposes away, not something to announce.
        // The next tick recomposes and replaces it.
        return try {
            val content = compose()
            shown = content
            content.toTemplate()
        } catch (e: Exception) {
            Log.w(TAG, "template build failed, showing a loading pane instead", e)
            PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
                .setTitle(carContext.getString(R.string.app_name))
                .setHeaderAction(Action.BACK)
                .build()
        }
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
            onSurface = chooseTemplate(
                surfaceLive = surface.live.value,
                apiLevel = carContext.carAppApiLevel,
                surfaceWidthDp = surface.surfaceWidthDp.value,
                visibleAreaEmpty = surface.visibleArea.value?.isEmpty == true,
                screenStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                visibleAreaReported = surface.visibleArea.value != null,
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
            .setHeaderAction(Action.BACK)
            .build()

        val template = MapWithContentTemplate.Builder()
            .setContentTemplate(content)

        transportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    private fun Content.fallback(): Template = when (glance) {
        is CarGlance.Now -> lyrics(glance)
        // Still looking: the loading pane, for as long as it takes.
        // There is no timeout that gives up and says there are no
        // lyrics — a fresh process walks 1,092 .lrc files before the
        // first one resolves, and the lookup is still running.
        CarGlance.Looking -> PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()

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
            .build()
        val template = PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.BACK)
        transportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    private fun Content.lyrics(glance: CarGlance.Now): Template {
        val pane = Pane.Builder()

        // Same three rows as the on-surface template: previous, current,
        // next. The middle position identifies the current line, so the
        // "Next:" prefix is gone.
        glance.previous?.let { previous ->
            pane.addRow(Row.Builder().setTitle(previous).build())
        }

        val current = Row.Builder()
            .setTitle(
                glance.line?.takeIf { it.isNotBlank() }
                    ?: carContext.getString(R.string.car_instrumental),
            )
        pane.addRow(current.build())

        glance.next?.let { next ->
            pane.addRow(Row.Builder().setTitle(next).build())
        }

        val template = PaneTemplate.Builder(pane.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        transportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    /**
     * The transport row, or nothing at all.
     *
     * Null rather than an empty strip, and that is not tidiness:
     * `ActionStrip.Builder.build()` throws "Action strip must contain at
     * least one action", and a session advertising only `ACTION_SEEK_TO`
     * has `transport.any` true while earning no buttons at all — which would
     * have taken down both car templates for that one player.
     */
    private fun Content.transportStrip(): ActionStrip? {
        val wanted = carTransportFor(transport)
        if (wanted.isEmpty()) return null

        val strip = ActionStrip.Builder()
        for (button in wanted) {
            strip.addAction(
                when (button) {
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
                },
            )
        }
        return strip.build()
    }

    /**
     * One button, with a title only where the templates allow one.
     *
     * This started out as icon *and* title on all three, for the screen
     * reader — `Action` has no content description of its own, so a title is
     * the only label there is. The library says no:
     * `PaneTemplate.setActionStrip` validates against
     * `ACTIONS_CONSTRAINTS_SIMPLE`, which permits exactly **one** action
     * with a custom title and throws on the second. So the one title goes to
     * play/pause, the button whose meaning changes and the one most worth
     * announcing, and the skips are icon-only. Both templates in use here
     * allow at least one, so the same row works in each.
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
            .setHeaderAction(Action.BACK)
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
