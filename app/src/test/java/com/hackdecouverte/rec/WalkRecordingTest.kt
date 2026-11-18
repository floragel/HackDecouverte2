package com.hackdecouverte.rec

import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.loadIndoorMap
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRecordingTest {

    private val site = IndoorSite.STANISLAS

    private fun stanMap(floorId: String) =
        loadIndoorMap(FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset(floorId))!!)) { MapTestSupport.open(it) }

    private fun roundTrip(rec: Recording): Recording {
        val f = File.createTempFile("walk", ".txt.gz")
        try {
            GZIPOutputStream(f.outputStream()).bufferedWriter().use { w ->
                val writer = RecordingWriter(w)
                writer.header(rec.header)
                rec.events.forEach(writer::write)
            }
            return GZIPInputStream(f.inputStream()).bufferedReader().use(RecordingReader::read)
        } finally {
            f.delete()
        }
    }

    @Test
    fun everyKindOfLineSurvivesTheFile() {
        val header = RecordingHeader(
            siteId = "STAN", floorId = "STAN-1", startedWallMs = 1L, startedNs = 2L, stepSource = "HARDWARE",
            stepLengthM = 0.71, cadenceHz = 1.8, declinationDeg = -14.1,
            sensors = listOf(SensorInfo(15, "Game RV", "Acme", 1e-6f, 1f, 5000)), wifiRttSupported = true, note = "a, b"
        )
        val events = listOf(
            RecEvent.Sample(15, 10L, 3, true, 1200, floatArrayOf(0.1f, -0.2f, 0.3f, 0.9f)),
            RecEvent.Sample(2, 11L, 2, false, 800, floatArrayOf(12.5f, -3f, 40f)),
            RecEvent.Accuracy(11, 12L, 1),
            RecEvent.Fix(13L, 45.5, -73.6, 8.5, "fused", 52.0, null, 270.0, 1.2, -14.1, 9),
            RecEvent.Gnss(14L, listOf(RecEvent.Satellite(1, 12, 31.5f, 210f, 40f, true, 1575.42f), RecEvent.Satellite(6, 3, 18f, 90f, 12f, false, null))),
            RecEvent.WifiScan(15L, listOf(RecEvent.AccessPoint("aa:bb:cc:dd:ee:ff", -61, 5180, true, 120))),
            RecEvent.Rtt(16L, listOf(RecEvent.Range("aa:bb:cc:dd:ee:ff", 0, 4520, 300, -60, 8, 7))),
            RecEvent.Marker(17L, 1, 1.5, -2.5, "door, M-101"),
            RecEvent.Live(18L, 4, 1.0, 2.0, 0.5, "FOUND", 3.0, 4.0, 1.5),
            RecEvent.Live(19L, 5, 1.0, 2.0, 0.5, "SEARCHING", null, null, null),
            RecEvent.Note(20L, "pause", "screen off")
        )
        val back = roundTrip(Recording(header, events))
        assertEquals(header.copy(note = "a, b"), back.header)
        assertEquals(events.size, back.events.size)
        events.zip(back.events).forEachIndexed { i, (a, b) ->
            if (a is RecEvent.Marker) assertEquals("door  M-101", (b as RecEvent.Marker).label)
            else assertEquals("line $i", a, b)
        }
    }

    @Test
    fun aTruncatedLastLineIsSkipped() {
        val good = RecordingWriter.line(RecEvent.Marker(5L, 1, 1.0, 2.0, "m"))
        val text = """{"siteId":"STAN","floorId":"STAN-1","startedWallMs":0,"startedNs":0,"stepSource":"HARDWARE","stepLengthM":0.7,"declinationDeg":-14.2}""" +
            "\n$good\nS,15,77,3,p"
        val rec = RecordingReader.read(text.reader().buffered())
        assertEquals(1, rec.events.size)
        assertNull(RecordingReader.parse("Z,1,2"))
    }

    @Test
    fun replayOfACorridorWalkFindsTheWalkerAndTracesTheWalkBack() {
        val rec = roundTrip(SyntheticWalk.stanislasNorthWestAndBack(site.origin))
        val runs = (1..3).map { WalkReplay(rec, site.origin, ::stanMap, seed = it).run() }
        fun median(f: (ReplayResult) -> Double?) = runs.map { f(it) ?: Double.MAX_VALUE }.sorted()[1]
        val r = runs.first()

        assertTrue("PDR walked ${r.distanceM} m", r.distanceM in 40.0..44.0)
        assertTrue("${r.steps.size} steps", r.steps.size in 58..62) // the gait filter holds the first step or two
        // A straight corridor cannot tell a short stride from a long one: it may stay APPROXIMATE, but on the walker.
        val end = median { it.markers.last().onlineErrM }
        assertTrue("online error at the end $end", end < 3.0)
        // The traced-back walk also knows where the walk started, which the filter could not know then.
        val start = median { it.markers.first().smoothedErrM }
        assertTrue("smoothed start error $start", start < 3.0)
        // Raw PDR was perfect here (synthetic): from the first marker it stays on the truth.
        assertTrue(r.markers.last().pdrFromFirstErrM!! < 0.5)
        assertTrue(r.gpsFixesUsed >= 1)
    }

    @Test
    fun theDeclinationIsAddedToTheCompassSoTheWalkGoesTrueNorthWest() {
        val rec = SyntheticWalk.stanislasNorthWestAndBack(site.origin)
        val result = WalkReplay(rec, site.origin, ::stanMap, seed = 1, useGps = false).run()
        val p = result.steps[40].pdr
        val bearing = kotlin.math.atan2(p.x, p.y)
        assertTrue("bearing ${Math.toDegrees(bearing)}", SyntheticWalk.angleDiffDeg(bearing, Math.toRadians(-45.0)) < 3.0)
    }
}
