package com.hackdecouverte.map

import com.hackdecouverte.map.MapTestSupport.corridorWalls
import com.hackdecouverte.map.MapTestSupport.grid
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2

class DirectionFieldTest {

    private fun deg(r: Double) = r * 180 / PI

    @Test
    fun clearanceInTwoMetreCorridorIsOneMetreOnCentreline() {
        val g = grid(corridorWalls(PointM(0.0, 0.0), PI / 2, 20.0), -12.0, -4.0, 12.0, 4.0)
        val d = DistanceField.compute(g)
        val c = d.at(PointM(0.0, 0.0))
        assertTrue("centre clearance $c", c in 0.95..1.05)
    }

    @Test
    fun axisFollowsStraightCorridor() {
        for (heading in listOf(PI / 2, Math.toRadians(40.5) + PI / 2)) {
            val g = grid(corridorWalls(PointM(0.0, 0.0), heading, 20.0), -12.0, -12.0, 12.0, 12.0)
            val f = DirectionField.compute(g, DistanceField.compute(g), emptyList())
            val p = PointM(0.0, 0.0)
            val err = deg(axialDiff(f.axisAt(p), heading))
            assertTrue("axis error $err°", err < 3.0)
            assertTrue("coherence ${f.coherenceAt(p)}", f.coherenceAt(p) >= 0.8)
        }
    }

    @Test
    fun coherenceIsLowInOpenHallAndNearDoor() {
        val room = listOf(
            MapTestSupport.bar(PointM(0.0, 6.15), PI / 2, 12.6, 0.3),
            MapTestSupport.bar(PointM(0.0, -6.15), PI / 2, 12.6, 0.3),
            MapTestSupport.bar(PointM(6.15, 0.0), 0.0, 12.6, 0.3),
            MapTestSupport.bar(PointM(-6.15, 0.0), 0.0, 12.6, 0.3)
        )
        val g = grid(room, -8.0, -8.0, 8.0, 8.0)
        val f = DirectionField.compute(g, DistanceField.compute(g), emptyList())
        assertTrue("hall coherence ${f.coherenceAt(PointM(0.0, 0.0))}", f.coherenceAt(PointM(0.0, 0.0)) < 0.3)

        val c = grid(corridorWalls(PointM(0.0, 0.0), PI / 2, 20.0), -12.0, -4.0, 12.0, 4.0)
        val door = PointM(5.0, 0.0)
        val fd = DirectionField.compute(c, DistanceField.compute(c), listOf(door))
        assertTrue(fd.coherenceAt(PointM(5.3, 0.0)) < 0.3)
        assertTrue(fd.coherenceAt(PointM(-5.0, 0.0)) >= 0.8)
    }

    @Test
    fun mbFloor2AxesMatchGraphCorridors() {
        val vector = FloorVectorPlan.parseSvg("MB-2", MapTestSupport.open("floor-plans/MB/MB-floor-02.svg")!!)
        val plan = FloorPlan.parse(MapTestSupport.open("floors/floorplan-MB-2.json")!!)
        val b = vector.viewBoundsM
        val g = grid(MapTestSupport.bigContours(vector), b.minEastM, b.minNorthM, b.maxEastM, b.maxNorthM)
        val f = DirectionField.compute(g, DistanceField.compute(g), emptyList())
        val errs = ArrayList<Double>()
        var samples = 0
        for (e in plan.edges) {
            val a = plan.node(e.fromId) ?: continue
            val z = plan.node(e.toId) ?: continue
            if (a.type != "corridor" || z.type != "corridor") continue
            val len = a.posM.distanceTo(z.posM)
            if (len < 3.0) continue
            val heading = atan2(z.posM.x - a.posM.x, z.posM.y - a.posM.y)
            var s = 1.5
            while (s <= len - 1.5) {
                val t = s / len
                val p = PointM(a.posM.x + (z.posM.x - a.posM.x) * t, a.posM.y + (z.posM.y - a.posM.y) * t)
                if (!g.isBlocked(p)) {
                    samples++
                    if (f.coherenceAt(p) >= 0.5) errs.add(deg(axialDiff(f.axisAt(p), heading)))
                }
                s += 0.5
            }
        }
        val coherentShare = errs.size.toDouble() / samples
        val median = errs.sorted()[errs.size / 2]
        assertTrue("coherent share $coherentShare over $samples samples", coherentShare >= 0.9)
        assertTrue("median axis error $median°", median <= 3.0)
    }
}
