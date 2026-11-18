package com.hackdecouverte.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLayersTest {

    @Test
    fun confidenceGoesFromRedThroughYellowToGreen() {
        val low = confidenceColor(0.0)
        val mid = confidenceColor(0.7)
        val high = confidenceColor(1.0)
        assertTrue("low is red", low.red > 0.8f && low.green < 0.3f)
        assertTrue("mid is yellow", mid.red > 0.8f && mid.green > 0.7f)
        assertTrue("high is green", high.green > 0.6f && high.red < 0.3f)
        assertEquals(low, confidenceColor(-3.0))   // clamped
        assertEquals(high, confidenceColor(4.0))
    }

    @Test
    fun everyLayerIsOnByDefault() {
        assertEquals(DebugLayer.entries.toSet(), DebugLayer.defaults)
    }
}
