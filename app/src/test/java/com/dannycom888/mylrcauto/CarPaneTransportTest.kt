package com.dannycom888.mylrcauto

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapWithContentTemplate
import com.dannycom888.mylrcauto.car.CarGlance
import com.dannycom888.mylrcauto.car.CarTransport
import com.dannycom888.mylrcauto.car.MAP_TEMPLATE_MAX_STRIP_ACTIONS
import com.dannycom888.mylrcauto.car.PANE_MAX_ACTIONS
import com.dannycom888.mylrcauto.car.PANE_TEMPLATE_MAX_STRIP_ACTIONS
import com.dannycom888.mylrcauto.car.carPaneHeaderAction
import com.dannycom888.mylrcauto.car.carTransportFor
import com.dannycom888.mylrcauto.car.paneTransportFor
import com.dannycom888.mylrcauto.lyrics.LyricLine
import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.LyricsState
import com.dannycom888.mylrcauto.media.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The v1.0.28 regression: the big screen's fallback pane never built.
 *
 * Device evidence (Settings → "Big screen render", 2026-11-13
 * 07:39:36, v1.0.27, 《小薇》/黄品源， 26 lyric lines): the pane that
 * came back was the EMERGENCY pane, with the fallback reason
 * `IllegalArgumentException: Action list exceeded max…`. The Car App
 * Library 1.7.0 validates a `PaneTemplate`'s action strip at
 * `setActionStrip` against ACTIONS_CONSTRAINTS_SIMPLE — at most 2
 * actions — and the fallback put the full three-button transport
 * (previous, play/pause, next) in that strip. Every full-transport
 * fallback pane threw, the catch in `onGetTemplate` answered with
 * the emergency pane, and the surface template was never reached
 * through that path. (The surface path survived because
 * `MapWithContentTemplate`'s strip allows 4.)
 *
 * These tests pin the fix two ways: the split itself
 * ([paneTransportFor]) stays within both containers' caps for every
 * transport a player can offer, and the REAL library builders —
 * driven exactly as CarLyricsScreen's fallback drives them, over a
 * real 26-line document — accept the result. The pre-fix assembly
 * is pinned too: three actions in a PaneTemplate strip throw the
 * exact exception the car recorded.
 */
class CarPaneTransportTest {

    private val fullTransport = Transport(
        playPause = true,
        skipNext = true,
        skipPrevious = true,
        seek = false,
    )

    // ---- the split ---------------------------------------------------------

    @Test
    fun fullTransportSplitsSkipsToTheStripAndPlayPauseToThePane() {
        val split = paneTransportFor(carTransportFor(fullTransport))

        assertEquals(
            listOf(CarTransport.PREVIOUS, CarTransport.NEXT),
            split.strip,
        )
        assertEquals(listOf(CarTransport.PLAY_PAUSE), split.paneActions)
    }

    @Test
    fun everyTransportCombinationStaysWithinTheCapsAndLosesNothing() {
        for (playPause in listOf(false, true)) {
            for (skipNext in listOf(false, true)) {
                for (skipPrevious in listOf(false, true)) {
                    val buttons = carTransportFor(
                        Transport(
                            playPause = playPause,
                            skipNext = skipNext,
                            skipPrevious = skipPrevious,
                        ),
                    )
                    val split = paneTransportFor(buttons)

                    assertTrue(
                        "strip $split exceeds the PaneTemplate cap",
                        split.strip.size <= PANE_TEMPLATE_MAX_STRIP_ACTIONS,
                    )
                    assertTrue(
                        "pane actions $split exceed the pane cap",
                        split.paneActions.size <= PANE_MAX_ACTIONS,
                    )
                    // Nothing lost, nothing duplicated, order kept:
                    // the strip holds the skips in their original
                    // relative order and play/pause is the only pane
                    // action, so re-interleaving is just filtering.
                    assertEquals(
                        buttons.filter { it != CarTransport.PLAY_PAUSE },
                        split.strip,
                    )
                    assertEquals(
                        buttons.filter { it == CarTransport.PLAY_PAUSE },
                        split.paneActions,
                    )
                    assertEquals(buttons.size, split.strip.size + split.paneActions.size)
                }
            }
        }
    }

    @Test
    fun theFullTransportStillFitsTheSurfaceTemplatesStrip() {
        // Why the bug only ever showed on the fallback: the surface
        // template's strip cap is 4, the full transport is 3.
        assertTrue(carTransportFor(fullTransport).size <= MAP_TEMPLATE_MAX_STRIP_ACTIONS)
    }

    // ---- the real builders, driven as the screen drives them ---------------

    @Test
    fun aTwentySixLineSongBuildsTheRealFallbackPane() {
        // The device case: 26 lines, full transport, no surface.
        val template = buildFallbackLyricsPane(lineCount = 26, transport = fullTransport)

        assertEquals(2, template.actionStrip!!.actions.size)
        assertEquals(1, template.pane.actions.size)
        assertSame(Action.BACK, template.headerAction)
        // Previous / current / next — three rows however long the song is.
        assertEquals(3, template.pane.rows.size)
    }

    @Test
    fun theBuildDoesNotDependOnTheLineCount() {
        for (lineCount in listOf(1, 2, 3, 25, 26, 27, 100)) {
            val template = buildFallbackLyricsPane(lineCount, fullTransport)

            assertTrue(template.actionStrip!!.actions.size <= PANE_TEMPLATE_MAX_STRIP_ACTIONS)
            assertTrue(template.pane.actions.size <= PANE_MAX_ACTIONS)
            assertTrue(template.pane.rows.size <= 3)
        }
    }

    @Test
    fun boundaryTransportCombinationsBuild() {
        // Skips only: both in the strip, no pane action.
        var template = buildFallbackLyricsPane(
            lineCount = 26,
            transport = Transport(skipPrevious = true, skipNext = true),
        )
        assertEquals(2, template.actionStrip!!.actions.size)
        assertEquals(0, template.pane.actions.size)

        // Play/pause only: no strip at all (an empty strip throws),
        // the button rides on the pane.
        template = buildFallbackLyricsPane(
            lineCount = 26,
            transport = Transport(playPause = true),
        )
        assertNull(template.actionStrip)
        assertEquals(1, template.pane.actions.size)

        // One skip + play/pause.
        template = buildFallbackLyricsPane(
            lineCount = 26,
            transport = Transport(playPause = true, skipNext = true),
        )
        assertEquals(1, template.actionStrip!!.actions.size)
        assertEquals(1, template.pane.actions.size)

        // A seek-only session earns no buttons: bare pane, no strip.
        template = buildFallbackLyricsPane(
            lineCount = 26,
            transport = Transport(seek = true),
        )
        assertNull(template.actionStrip)
        assertEquals(0, template.pane.actions.size)
    }

    @Test
    fun theNeutralPaneShapeBuildsToo() {
        // CarLyricsScreen's other PaneTemplate: a title/artist row
        // plus the same split transport.
        val pane = Pane.Builder()
            .addRow(
                Row.Builder().setTitle("song title").addText("artist").build(),
            )
        val split = paneTransportFor(carTransportFor(fullTransport))
        split.paneActions.forEach { pane.addAction(testAction(it)) }

        val template = PaneTemplate.Builder(pane.build())
            .setTitle("MyLRC Auto")
            .setHeaderAction(carPaneHeaderAction())
        stripOf(split.strip)?.let { template.setActionStrip(it) }

        val built = template.build()
        assertEquals(2, built.actionStrip!!.actions.size)
        assertEquals(1, built.pane.actions.size)
    }

    // ---- the defect, pinned against the real library -----------------------

    @Test
    fun thePreFixAssemblyThrowsTheExactDeviceException() {
        // The v1.0.27 fallback assembly: the whole three-button
        // transport in the PaneTemplate's action strip.
        val strip = ActionStrip.Builder().apply {
            carTransportFor(fullTransport).forEach { addAction(testAction(it)) }
        }.build()

        val template = PaneTemplate.Builder(
            Pane.Builder().addRow(Row.Builder().setTitle("line").build()).build(),
        ).setTitle("song title").setHeaderAction(carPaneHeaderAction())

        try {
            template.setActionStrip(strip)
            fail("a 3-action strip in a PaneTemplate must throw — that is the v1.0.27 bug")
        } catch (e: IllegalArgumentException) {
            // The message the car's render record showed, untruncated.
            assertEquals("Action list exceeded max number of 2 actions", e.message)
        }
    }

    @Test
    fun theSameThreeButtonStripIsLegalOnTheSurfaceTemplate() {
        // The other half of the device picture: with a surface, the
        // same strip built fine — MapWithContentTemplate validates
        // against ACTIONS_CONSTRAINTS_NAVIGATION (max 4).
        val strip = ActionStrip.Builder().apply {
            carTransportFor(fullTransport).forEach { addAction(testAction(it)) }
        }.build()
        val content = PaneTemplate.Builder(
            Pane.Builder().addRow(Row.Builder().setTitle("song title").build()).build(),
        ).setTitle("MyLRC Auto").setHeaderAction(carPaneHeaderAction()).build()

        val template = MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setActionStrip(strip)
            .build()

        assertEquals(3, template.actionStrip!!.actions.size)
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * One button as an [Action] for the JVM. Production's strip
     * buttons are icon-only and its pane action carries the title;
     * an icon needs a Context/Bitmap the JVM does not have, so the
     * strip buttons carry an EMPTY title instead — the library's
     * validator counts a title only when it is non-empty, so the
     * two profiles present identical counts to every check that
     * threw on the car.
     */
    private fun testAction(button: CarTransport): Action =
        Action.Builder()
            .setTitle(if (button == CarTransport.PLAY_PAUSE) "Play/Pause" else "")
            .setOnClickListener { }
            .build()

    private fun stripOf(buttons: List<CarTransport>): ActionStrip? {
        if (buttons.isEmpty()) return null
        return ActionStrip.Builder().apply {
            buttons.forEach { addAction(testAction(it)) }
        }.build()
    }

    /**
     * The fallback lyrics pane, assembled exactly as
     * `CarLyricsScreen` assembles it since v1.0.28: rows from the
     * real glance over a document with [lineCount] timed lines,
     * transport from the real [carTransportFor] split by the real
     * [paneTransportFor], header through the real wiring point.
     */
    private fun buildFallbackLyricsPane(lineCount: Int, transport: Transport): PaneTemplate {
        val document = LyricsDocument(
            lines = (0 until lineCount).map { LyricLine(it * 5_000L, "line ${it + 1}") },
            sourceName = "test.lrc",
        )
        val glance = CarGlance.of(
            state = LyricsState.Loaded(document),
            hasTrack = true,
            permissionGranted = true,
            positionMs = if (lineCount > 1) (lineCount / 2) * 5_000L else 0L,
        ) as CarGlance.Now

        val pane = Pane.Builder()
        glance.previous?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        pane.addRow(Row.Builder().setTitle(glance.line ?: "instrumental").build())
        glance.next?.let { pane.addRow(Row.Builder().setTitle(it).build()) }

        val split = paneTransportFor(carTransportFor(transport))
        split.paneActions.forEach { pane.addAction(testAction(it)) }

        val template = PaneTemplate.Builder(pane.build())
            .setTitle("song title")
            .setHeaderAction(carPaneHeaderAction())
        stripOf(split.strip)?.let { template.setActionStrip(it) }
        return template.build()
    }
}
