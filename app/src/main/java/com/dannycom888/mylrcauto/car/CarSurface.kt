package com.dannycom888.mylrcauto.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dannycom888.mylrcauto.MyLrcAutoApp.AppContainer
import com.dannycom888.mylrcauto.ui.theme.MyLrcAutoTheme

/**
 * The app's own UI, on the car's screen.
 *
 * The host hands over a `Surface` and the documented way to put Views on one
 * is a [VirtualDisplay] with a [Presentation] over it — which means a
 * [ComposeView], which means *this app's actual composables* rather than a
 * second implementation of them. That is the whole reason the car screen can
 * look like the phone: [CarLyricsContent] draws with the same renderer, and
 * there is no parallel version to keep in step.
 *
 * A `ComposeView` outside an activity needs someone to be its lifecycle and
 * its saved-state registry. The car [androidx.car.app.Session] is already a
 * `LifecycleOwner` and is made a `SavedStateRegistryOwner` for this, so the
 * composition lives and dies with the connection to the car and nothing has
 * to be torn down by hand.
 */
class CarSurface(
    private val session: CarSession,
    private val container: AppContainer,
    private val state: CarSurfaceState,
) : SurfaceCallback {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null

    init {
        // A connection that ends abruptly, a cable pulled, never delivers
        // onSurfaceDestroyed; the session ending is the last word, and the
        // display and presentation go with it.
        session.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = release()
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val carContext = session.carContext

        // A host may offer a surface again without having taken the last one
        // back — switching to another car app and returning does exactly
        // that. Letting the old display and presentation go first is the
        // difference between a redraw and a leaked virtual display per switch.
        release()

        // Width, height and dpi all come from the host: a car screen is not
        // a phone screen and guessing any of them would size the type wrong.
        val width = surfaceContainer.width.coerceAtLeast(1)
        val height = surfaceContainer.height.coerceAtLeast(1)
        val dpi = surfaceContainer.dpi.coerceAtLeast(1)

        val created = runCatching {
            carContext.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, 0)
        }.getOrElse { failure ->
            // A host that will not give us a display is a host we fall back
            // from, not one we crash on: the templated two-line screen is a
            // complete answer on its own.
            Log.w(TAG, "no virtual display for the car surface: ${failure.message}")
            return
        }
        display = created

        // The screen's template choice needs the granted width (in dp) to
        // tell a full-screen surface from a split-screen slot: a host that
        // only shows a corner of the app still reports the surface as live.
        state.onSurfaceSize(width / (dpi / 160f))

        // Read fresh at call time, never captured: the snapshot only updates
        // when the player publishes, and the lyrics list re-reads the
        // playhead itself on every frame.
        val positionMsProvider: () -> Long =
            { container.media.snapshot.value.playback.currentMs() }
        val onSeek: (Long) -> Unit = { container.media.seekTo(it) }

        val view = ComposeView(carContext).apply {
            setViewTreeLifecycleOwner(session)
            setViewTreeSavedStateRegistryOwner(session)
            setContent {
                // The app's dark theme + a Surface: without them the content
                // falls back to the default light scheme, so lyric lines
                // render black-on-black on the dark car background.
                MyLrcAutoTheme {
                    Surface(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val area by state.visibleArea.collectAsStateWithLifecycle()
                        CarLyricsContent(
                            container = container,
                            positionMsProvider = positionMsProvider,
                            onSeek = onSeek,
                            // Inside the host's own idea of what is visible: it draws
                            // a header and an action strip over this surface and says
                            // where, so the words go in the gap rather than under the
                            // furniture.
                            modifier = androidx.compose.ui.Modifier.padding(
                                insetsFor(area, width, height, dpi / 160f),
                            ),
                        )
                    }
                }
            }
        }

        val shown = Presentation(carContext, created.display).apply { setContentView(view) }
        presentation = shown

        // Only "live" if there is really something on the screen. Claiming
        // otherwise would leave the template showing a map with nothing drawn
        // on it, where the two-line fallback would have been a working screen.
        runCatching { shown.show() }
            .onSuccess { state.onLive(true) }
            .onFailure { failure ->
                Log.w(TAG, "the car presentation would not show: ${failure.message}")
                release()
            }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        state.onVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Deliberately ignored. The stable area is the smallest rectangle
        // that stays visible, and laying the lyrics out inside it would leave
        // them in a letterbox whenever the host's chrome faded away. The
        // visible area is the one that describes the screen as it is now.
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    /**
     * Give back the display and the surface it was drawing on.
     *
     * Order matters: the presentation holds the display, and the display
     * holds the surface the host wants back. Both calls are guarded because
     * either can be gone already — the host tearing the connection down is
     * not an orderly shutdown.
     */
    private fun release() {
        state.onLive(false)
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { display?.release() }
        display = null
    }

    private companion object {
        const val TAG = "MyLrcAutoCarSurface"
        const val DISPLAY_NAME = "mylrcauto-car"
    }
}

/** Turns the host's visible rectangle into padding, in the density the surface is drawn at. */
internal fun insetsFor(
    area: Rect?,
    widthPx: Int,
    heightPx: Int,
    density: Float,
): PaddingValues {
    if (area == null || area.isEmpty || widthPx <= 0 || heightPx <= 0) return PaddingValues(0.dp)
    fun px(value: Int) = (value / density).dp
    return PaddingValues(
        start = px(area.left.coerceAtLeast(0)),
        top = px(area.top.coerceAtLeast(0)),
        end = px((widthPx - area.right).coerceAtLeast(0)),
        bottom = px((heightPx - area.bottom).coerceAtLeast(0)),
    )
}
