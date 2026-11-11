package com.dannycom888.mylrcauto

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import com.dannycom888.mylrcauto.ui.SkipNextPathData
import com.dannycom888.mylrcauto.ui.SkipPreviousPathData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previous/next transport icons must be exact horizontal mirrors of
 * each other (|◀ vs ▶|), same style and size.
 *
 * Regression: the old SkipPrevious path ("M6,6h2v12H6zm3.5,6l8.5,6V6z")
 * looked right per the SVG spec, but on the phone its triangle rendered
 * 6 units too low — a tall triangle leaning right, its bottom below the
 * bar (user screenshot 2026-11-10). Cause: the Compose path parser
 * resolves a relative moveto (m) after a close (z) against the point
 * before the close, not the subpath start. So every subpath here must
 * start with an ABSOLUTE moveto (M); the test below asserts that too.
 */
class TransportIconsTest {

    private data class Pt(val x: Float, val y: Float)

    /** Flatten parsed nodes to absolute polygons, following the SVG spec
     * (after a close, the current point is the subpath start). Only the
     * straight-line commands these icons use are supported; anything else
     * fails loudly so a new command type is noticed, not silently skipped. */
    private fun polygons(pathData: String): List<Set<Pt>> {
        val nodes = PathParser().parsePathString(pathData).toNodes()
        val out = mutableListOf<MutableSet<Pt>>()
        var cur: Pt? = null
        var start: Pt? = null
        var poly: MutableSet<Pt>? = null
        fun moveTo(p: Pt) {
            cur = p
            start = p
            poly = mutableSetOf(p)
            out.add(poly!!)
        }
        fun lineTo(p: Pt) {
            cur = p
            poly!!.add(p)
        }
        for (node in nodes) {
            when (node) {
                is PathNode.MoveTo -> moveTo(Pt(node.x, node.y))
                is PathNode.RelativeMoveTo -> {
                    val c = cur!!
                    moveTo(Pt(c.x + node.dx, c.y + node.dy))
                }
                is PathNode.LineTo -> lineTo(Pt(node.x, node.y))
                is PathNode.RelativeLineTo -> {
                    val c = cur!!
                    lineTo(Pt(c.x + node.dx, c.y + node.dy))
                }
                is PathNode.HorizontalTo -> lineTo(Pt(node.x, cur!!.y))
                is PathNode.RelativeHorizontalTo -> {
                    val c = cur!!
                    lineTo(Pt(c.x + node.dx, c.y))
                }
                is PathNode.VerticalTo -> lineTo(Pt(cur!!.x, node.y))
                is PathNode.RelativeVerticalTo -> {
                    val c = cur!!
                    lineTo(Pt(c.x, c.y + node.dy))
                }
                is PathNode.Close -> cur = start
                else -> throw AssertionError("unsupported path node in icon: $node")
            }
        }
        return out
    }

    private fun round1(p: Pt): Pt =
        Pt((p.x * 10).toInt() / 10f, (p.y * 10).toInt() / 10f)

    private fun normalized(polys: List<Set<Pt>>): Set<Set<Pt>> =
        polys.map { poly -> poly.map(::round1).toSet() }.toSet()

    @Test
    fun previousIsExactHorizontalMirrorOfNext() {
        val prev = normalized(polygons(SkipPreviousPathData))
        val next = normalized(polygons(SkipNextPathData))
        // Each icon is a bar + a triangle; the two shapes must mirror.
        assertEquals(2, prev.size)
        assertEquals(2, next.size)
        val nextMirrored = next.map { poly ->
            poly.map { Pt(24f - it.x, it.y) }.toSet()
        }.toSet()
        assertEquals(nextMirrored, prev)
    }

    @Test
    fun everySubpathStartsWithAbsoluteMoveTo() {
        // A relative moveto (m) after a close (z) is resolved against the
        // point before the close, which shifted the old previous triangle
        // down by 6 units on the phone. No icon path may use that pattern.
        for (pathData in listOf(SkipPreviousPathData, SkipNextPathData)) {
            val nodes = PathParser().parsePathString(pathData).toNodes()
            var atSubpathStart = true
            for (node in nodes) {
                when (node) {
                    is PathNode.MoveTo -> atSubpathStart = false
                    is PathNode.RelativeMoveTo -> {
                        assertTrue(
                            "relative moveto after a close in icon path: $pathData",
                            !atSubpathStart,
                        )
                        atSubpathStart = false
                    }
                    is PathNode.Close -> atSubpathStart = true
                    else -> atSubpathStart = false
                }
            }
        }
    }
}
