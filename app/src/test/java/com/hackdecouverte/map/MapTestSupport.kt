package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Test helpers: synthetic wall geometry and raw MB grids. */
internal object MapTestSupport {

    /** Rectangle of [lengthM] along compass heading [headingRad], centred on [center], [widthM] thick. */
    fun bar(center: PointM, headingRad: Double, lengthM: Double, widthM: Double): List<PointM> {
        val ux = sin(headingRad); val uy = cos(headingRad) // along
        val vx = uy; val vy = -ux // right
        val a = lengthM / 2; val b = widthM / 2
        return listOf(
            PointM(center.x - ux * a - vx * b, center.y - uy * a - vy * b),
            PointM(center.x + ux * a - vx * b, center.y + uy * a - vy * b),
            PointM(center.x + ux * a + vx * b, center.y + uy * a + vy * b),
            PointM(center.x - ux * a + vx * b, center.y - uy * a + vy * b)
        )
    }

    /** Straight corridor of inner width [innerWidthM] along [headingRad], walls 0.3 m thick. */
    fun corridorWalls(center: PointM, headingRad: Double, lengthM: Double, innerWidthM: Double = 2.0): List<List<PointM>> {
        val off = innerWidthM / 2 + 0.15
        val rx = cos(headingRad); val ry = -sin(headingRad)
        return listOf(
            bar(PointM(center.x + rx * off, center.y + ry * off), headingRad, lengthM, 0.3),
            bar(PointM(center.x - rx * off, center.y - ry * off), headingRad, lengthM, 0.3)
        )
    }

    fun grid(walls: List<List<PointM>>, minE: Double, minN: Double, maxE: Double, maxN: Double, res: Double = 0.1): OccupancyGrid {
        val w = ceil((maxE - minE) / res).toInt()
        val h = ceil((maxN - minN) / res).toInt()
        val filled = OccupancyGrid.rasterize(walls, minE, minN, res, w, h)
        return OccupancyGrid(minE, minN, res, w, h, ByteArray(w * h) { if (filled[it]) OccupancyGrid.WALL else OccupancyGrid.FREE })
    }

    /** Contours of an aligned MB SVG whose largest extent is at least [minExtentM] (text removed). */
    fun bigContours(vector: FloorVectorPlan, minExtentM: Double = 0.75): List<List<PointM>> {
        val out = ArrayList<List<PointM>>()
        for (c in 0 until vector.contourCount) {
            val start = vector.contourStartPairIndex[c]
            val n = vector.contourPointCounts[c]
            val pts = (0 until n).map { k ->
                PointM(vector.packedEastNorthM[2 * (start + k)].toDouble(), vector.packedEastNorthM[2 * (start + k) + 1].toDouble())
            }
            val ext = maxOf(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })
            if (ext >= minExtentM) out.add(pts)
        }
        return out
    }

    fun open(path: String) = javaClass.classLoader!!.getResourceAsStream(path)
}
