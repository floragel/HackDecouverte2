package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Spec §6.6: after a hold change the phone→walking angle is uncertain again, positions are kept. */
class HoldChangeTest {

    private fun foundAtCorner(seed: Int): LocalizationFilter {
        // L corridor: north from (0, 0) to the corner at (0, 20), then east. Start known, walk to the corner.
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0), Random(seed))
        f.initAt(List(1500) { PointM(0.0, 0.0) }, List(1500) { 0.0 })
        repeat(28) { f.onStep(0.7, 0.0) }
        return f
    }

    @Test
    fun aWellBridgedHoldChangeKeepsTheWalkerFound() {
        val f = foundAtCorner(3)
        f.onHoldChanged()
        repeat(25) { f.onStep(0.7, Math.PI / 2) } // turned east at the corner, the PDR saw it
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(17.5, 20.0)) < 2.5)
    }

    @Test
    fun aTurnHiddenByTheHoldChangeIsRecovered() {
        // The user turned east while pocketing the phone: the bridge hid the turn, the PDR still says "north".
        // Going back south fits the walls too until the south end (~29 steps): after that only east remains.
        val f = foundAtCorner(4)
        f.onHoldChanged()
        repeat(34) { f.onStep(0.7, 0.0) }
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(23.8, 20.0)) < 3.0)
    }
}
