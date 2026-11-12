package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.car.CarLyricsScreen
import com.dannycom888.mylrcauto.car.shouldInvalidateOnStateChange
import com.dannycom888.mylrcauto.media.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-driven redraw rule (v1.0.11): in split-screen this host drops
 * the screen to STOPPED, so the STARTED-gated tick loop stops running and
 * the template in the slot goes stale — it keeps showing whatever it
 * showed when the app left full-screen, instead of following the song. A
 * lifecycleScope collector (not STARTED-gated) recomputes on every
 * media-snapshot / lyrics-state / permission emission and invalidates
 * exactly when the recomposed content differs. These tests pin that
 * comparison: the 500ms snapshot ticker fires while playing, so a
 * position-only advance — which composes to identical content — must not
 * invalidate, while a lookup finishing, a line change, a track change or
 * play/pause must.
 */
class StateDrivenRedrawTest {

    private fun content(
        glance: CarGlance = CarGlance.None,
        title: String = "Monica",
        artist: String = "张国荣",
        transport: Transport = Transport(playPause = true, skipNext = true, skipPrevious = true),
        playing: Boolean = true,
        lookingTimedOut: Boolean = false,
        onSurface: Boolean = false,
    ) = CarLyricsScreen.Content(
        glance = glance,
        title = title,
        artist = artist,
        transport = transport,
        playing = playing,
        lookingTimedOut = lookingTimedOut,
        onSurface = onSurface,
    )

    private val now = CarGlance.Now(
        previous = "上一句",
        line = "正在唱的这一句",
        next = "下一句",
        instrumental = false,
    )

    @Test
    fun `first compose invalidates`() {
        assertTrue(shouldInvalidateOnStateChange(content(), shown = null))
    }

    @Test
    fun `identical content does not invalidate`() {
        // A position-only advance from the 500ms snapshot ticker: the
        // composed content is the same, so no invalidate.
        assertFalse(shouldInvalidateOnStateChange(content(), shown = content()))
    }

    @Test
    fun `lookup finishing invalidates`() {
        // A lookup finishing while the screen is covered: None -> Now
        // must invalidate, or the slot keeps showing the old template
        // instead of the lyrics.
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(glance = now),
                shown = content(glance = CarGlance.None),
            ),
        )
    }

    @Test
    fun `line change invalidates`() {
        val later = now.copy(previous = "正在唱的这一句", line = "下一句", next = "再下一句")
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(glance = later),
                shown = content(glance = now),
            ),
        )
    }

    @Test
    fun `track change invalidates`() {
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(title = "一千年以后", artist = "林俊杰"),
                shown = content(),
            ),
        )
    }

    @Test
    fun `play pause toggle invalidates`() {
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(playing = false),
                shown = content(playing = true),
            ),
        )
    }

    @Test
    fun `surface flip invalidates`() {
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(onSurface = true),
                shown = content(onSurface = false),
            ),
        )
    }

    @Test
    fun `looking timing out invalidates`() {
        assertTrue(
            shouldInvalidateOnStateChange(
                next = content(glance = CarGlance.Looking, lookingTimedOut = true),
                shown = content(glance = CarGlance.Looking, lookingTimedOut = false),
            ),
        )
    }
}
