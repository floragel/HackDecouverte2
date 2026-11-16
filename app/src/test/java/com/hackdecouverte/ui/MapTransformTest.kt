package com.hackdecouverte.ui

import com.hackdecouverte.pdr.PointM
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTransformTest {

    private fun transform(
        focus: PointM = PointM(0.0, 0.0),
        panX: Double = 0.0,
        panY: Double = 0.0,
        pxPerMeter: Double = 100.0,
        rotation: Double = 0.0,
        cx: Double = 400.0,
        cy: Double = 300.0
    ) = MapTransform(focus, panX, panY, pxPerMeter, rotation, cx, cy)

    @Test
    fun worldToScreenPlacesFocusAtCenter() {
        val t = transform()
        val s = t.worldToScreen(PointM(0.0, 0.0))
        assertEquals(400.0, s.x, 1e-9)
        assertEquals(300.0, s.y, 1e-9)
    }

    @Test
    fun forwardMapsUpInFixedFrame() {
        val t = transform()
        val s = t.worldToScreen(PointM(0.0, 2.0))
        assertEquals(400.0, s.x, 1e-9)
        assertEquals(100.0, s.y, 1e-9)
    }

    @Test
    fun headUpNinetyDegreesPutsEastOnTop() {
        val t = transform(rotation = -PI / 2)
        val s = t.worldToScreen(PointM(3.0, 0.0))
        assertEquals(400.0, s.x, 1e-6)
        assertTrue(s.y < 300.0)
        assertEquals(0.0, s.y, 1e-6)
    }

    @Test
    fun panShiftsProjectionByExactOffset() {
        val t = transform(panX = 55.0, panY = -30.0)
        val s = t.worldToScreen(PointM(0.0, 0.0))
        assertEquals(455.0, s.x, 1e-9)
        assertEquals(270.0, s.y, 1e-9)
    }

    @Test
    fun screenToWorldRoundTripsWithRotationAndPan() {
        val t = transform(
            focus = PointM(4.0, -2.0),
            panX = 70.0,
            panY = -45.0,
            pxPerMeter = 80.0,
            rotation = 0.7
        )
        val p = PointM(3.2, 5.7)
        val back = t.screenToWorld(t.worldToScreen(p))
        assertEquals(p.x, back.x, 1e-9)
        assertEquals(p.y, back.y, 1e-9)
    }

    @Test
    fun frozenFocusKeepsAnchorUnderNewPosition() {
        val anchor = PointM(2.0, 3.0)
        val t = transform(focus = anchor, panX = 0.0, panY = 0.0)
        val before = t.worldToScreen(anchor)
        val moved = transform(focus = anchor, panX = 0.0, panY = 0.0, rotation = 0.0)
        val after = moved.worldToScreen(anchor)
        assertEquals(before.x, after.x, 1e-9)
        assertEquals(before.y, after.y, 1e-9)
    }

    @Test
    fun viewBoundsContainInverseOfEveryViewportCorner() {
        val t = transform(
            focus = PointM(10.0, 10.0),
            panX = 900.0,
            panY = -700.0,
            rotation = -PI / 2
        )
        val bounds = t.viewBoundsWorld(800.0, 600.0, marginM = 0.0)
        val corners = listOf(
            PointM(0.0, 0.0),
            PointM(800.0, 0.0),
            PointM(0.0, 600.0),
            PointM(800.0, 600.0)
        )
        for (corner in corners) {
            val w = t.screenToWorld(corner)
            assertTrue(w.x >= bounds.minX - 1e-9 && w.x <= bounds.maxX + 1e-9)
            assertTrue(w.y >= bounds.minY - 1e-9 && w.y <= bounds.maxY + 1e-9)
        }
        val focusScreen = t.worldToScreen(PointM(10.0, 10.0))
        val back = t.screenToWorld(focusScreen)
        assertEquals(10.0, back.x, 1e-9)
        assertEquals(10.0, back.y, 1e-9)
        assertTrue(abs(bounds.maxX - bounds.minX) > 1.0)
    }
}
