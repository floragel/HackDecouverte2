package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class ZoneClusteringTest {

    private fun gaussian(r: Random) = sqrt(-2 * ln(r.nextDouble().coerceAtLeast(1e-12))) * cos(2 * PI * r.nextDouble())

    @Test
    fun twoSeparateCloudsGiveTwoZonesHeaviestFirst() {
        val r = Random(2)
        val n = 1000
        val e = DoubleArray(n); val no = DoubleArray(n); val h = DoubleArray(n); val w = DoubleArray(n)
        for (i in 0 until n) {
            val first = i < 700
            e[i] = (if (first) 0.0 else 20.0) + gaussian(r) * 0.5
            no[i] = gaussian(r) * 0.5
            h[i] = if (first) 0.0 else PI / 2
            w[i] = 1.0 / n
        }
        val zones = ZoneClustering.zones(e, no, h, w, n)
        assertEquals(2, zones.size)
        assertEquals(0.7, zones[0].weight, 1e-9)
        assertTrue(zones[0].centerM.distanceTo(PointM(0.0, 0.0)) < 0.2)
        assertTrue(zones[1].centerM.distanceTo(PointM(20.0, 0.0)) < 0.2)
        assertEquals(PI / 2, zones[1].headingRad, 1e-6)
        assertTrue(zones[0].radiusM in 0.9..1.6) // 2.45 σ with σ = 0.5
    }

    @Test
    fun compactCloudIsFound() {
        val r = Random(4)
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = r)
        val pts = List(500) { PointM(gaussian(r) * 0.3, 5.0 + gaussian(r) * 0.5) }
        f.initAt(pts, List(pts.size) { 0.0 })
        assertEquals(LocState.FOUND, f.estimate().state)
    }

    @Test
    fun mainZoneWalkingAcrossCorridorRaisesAlarm() {
        val r = Random(6)
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = r)
        val pts = List(500) { PointM(-0.8 + gaussian(r) * 0.05, 5.0 + gaussian(r) * 0.3) }
        f.initAt(pts, List(pts.size) { 0.0 })
        repeat(5) { f.onStep(0.1, PI / 2) } // east, across a north–south corridor
        assertTrue("alarms ${f.alarms}", f.alarms >= 1)
        assertNotEquals(LocState.FOUND, f.estimate().state)
    }
}
