package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MbVectorAndOsmTest {

    @Test
    fun parsesAllSevenAlignedMbFloorVectorSvgsInSharedMetricFrame() {
        for (floorNum in 1..7) {
            val path = String.format("floor-plans/MB/MB-floor-%02d.svg", floorNum)
            val stream = javaClass.classLoader!!.getResourceAsStream(path)
            assertNotNull("Missing SVG asset $path", stream)
            val vectorPlan = FloorVectorPlan.parseSvg("MB-$floorNum", stream!!)
            assertEquals("MB-$floorNum", vectorPlan.floorId)
            assertEquals(-44.0, vectorPlan.viewBoundsM.minEastM, 1e-6)
            assertEquals(50.0, vectorPlan.viewBoundsM.maxEastM, 1e-6)
            assertEquals(-40.0, vectorPlan.viewBoundsM.minNorthM, 1e-6)
            assertEquals(34.0, vectorPlan.viewBoundsM.maxNorthM, 1e-6)
            assertTrue("Expected > 200 contours on MB-$floorNum", vectorPlan.contourCount > 200)
            assertTrue("Expected > 5000 points on MB-$floorNum", vectorPlan.totalPoints > 5000)

            // Building spans roughly [-41, +47] E and [-37, +31] N around (0, 0) elevator core
            assertTrue(vectorPlan.contentBoundsM.minEastM in -44.0..-25.0)
            assertTrue(vectorPlan.contentBoundsM.maxEastM in 35.0..50.0)
            assertTrue(vectorPlan.contentBoundsM.minNorthM in -40.0..-25.0)
            assertTrue(vectorPlan.contentBoundsM.maxNorthM in 25.0..34.0)
        }
    }

    @Test
    fun parsesBundledOpenStreetMapCampusSurroundings() {
        val stream = javaClass.classLoader!!.getResourceAsStream("osm/mb_surroundings.json")
        assertNotNull("Missing osm/mb_surroundings.json", stream)
        val osm = OsmSurroundings.parseJson(stream!!)
        assertEquals(GeoOrigin.MB_ELEVATOR_CORE.latitude, osm.origin.latitude, 1e-5)
        assertEquals(GeoOrigin.MB_ELEVATOR_CORE.longitude, osm.origin.longitude, 1e-5)
        assertTrue(osm.buildings.size > 100)
        assertTrue(osm.roads.size > 50)
        assertTrue(osm.buildings.any { it.isFocusBuilding && it.ref == "MB" })
        assertTrue(osm.roads.any { it.name.contains("Guy", ignoreCase = true) })
        assertTrue(osm.roads.any { it.name.contains("Maisonneuve", ignoreCase = true) })
    }

    @Test
    fun parsesLiveOpenStreetMapXmlAndRoundTripsGeoOrigin() {
        val origin = GeoOrigin.MB_ELEVATOR_CORE
        val testPt = PointM(25.0, -40.0)
        val wgs = origin.toWgs84(testPt)
        val back = origin.toEastNorthM(wgs)
        assertEquals(testPt.x, back.x, 1e-4)
        assertEquals(testPt.y, back.y, 1e-4)

        val n1 = origin.toWgs84(PointM(-10.0, -10.0))
        val n2 = origin.toWgs84(PointM(10.0, -10.0))
        val n3 = origin.toWgs84(PointM(10.0, 10.0))
        val n4 = origin.toWgs84(PointM(-10.0, 10.0))
        val sampleXml = """
            <osm version="0.6">
              <node id="1" lat="${n1.latitude}" lon="${n1.longitude}"/>
              <node id="2" lat="${n2.latitude}" lon="${n2.longitude}"/>
              <node id="3" lat="${n3.latitude}" lon="${n3.longitude}"/>
              <node id="4" lat="${n4.latitude}" lon="${n4.longitude}"/>
              <node id="5" lat="${origin.latitude}" lon="${origin.longitude}">
                <tag k="railway" v="subway_entrance"/>
                <tag k="name" v="Guy-Concordia"/>
              </node>
              <way id="10">
                <nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/>
                <tag k="building" v="university"/>
                <tag k="ref" v="MB"/>
              </way>
              <way id="11">
                <nd ref="1"/><nd ref="3"/>
                <tag k="highway" v="primary"/>
                <tag k="name" v="Rue Guy"/>
              </way>
            </osm>
        """.trimIndent()

        val parsed = OsmSurroundings.parseOsmXml(sampleXml, origin)
        assertEquals(1, parsed.buildings.size)
        assertTrue(parsed.buildings.single().isFocusBuilding)
        assertEquals(1, parsed.roads.size)
        assertEquals("Rue Guy", parsed.roads.single().name)
        assertEquals(1, parsed.pois.size)
    }
}
