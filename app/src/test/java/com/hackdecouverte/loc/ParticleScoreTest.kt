package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/** Per-hypothesis confidence for the debug map: recent average of how plausible its steps were. */
class ParticleScoreTest {

    @Test
    fun hittingAWallLowersTheScoreFollowingTheCorridorKeepsItHigh() {
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = Random(1))
        f.initAt(listOf(PointM(0.5, 0.0), PointM(0.5, 0.0)), listOf(0.0, PI / 2)) // along / into the wall at x = 1
        f.onStep(0.7, 0.0)
        val (along, intoWall) = f.sampleParticles(2).let { ps -> ps.sortedByDescending { it.positionM.y } }
        assertTrue("along ${along.score}", along.score > 0.99)
        assertTrue("into wall ${intoWall.score}", intoWall.score < 0.85)
        assertTrue(intoWall.weight < 0.05 && along.weight > 0.95)
        assertEquals(0.0, along.headingRad, 0.2)
    }

    @Test
    fun scoresStayInRangeAndWeightsSumToOne() {
        val map = SyntheticMaps.lCorridor()
        val random = Random(3)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 1000, random)
        f.initAt(pts, List(pts.size) { random.nextDouble(0.0, 2 * PI) })
        repeat(15) { f.onStep(0.7, 0.3) }
        val all = f.sampleParticles(f.size())
        assertEquals(f.size(), all.size)
        assertEquals(1.0, all.sumOf { it.weight }, 1e-6)
        assertTrue(all.all { it.score in 0.0..1.0 })
        assertTrue("some hypotheses must have been hurt", all.any { it.score < 0.9 })
    }
}
