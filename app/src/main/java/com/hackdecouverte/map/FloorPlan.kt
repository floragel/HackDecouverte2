package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.angleDelta
import com.hackdecouverte.pdr.wrapAnglePi
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class FloorNode(
    val id: String,
    val type: String,
    val label: String = "",
    val name: String = "",
    @SerialName("vertical_group_id") val verticalGroupId: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    @SerialName("east_m") val eastM: Double? = null,
    @SerialName("north_m") val northM: Double? = null,
    @Transient val posM: PointM = PointM(0.0, 0.0)
)

@Serializable
data class FloorEdge(
    @SerialName("from") val fromId: String,
    @SerialName("to") val toId: String,
    val accessible: Boolean = true
)

@Serializable
data class GeoTransform(
    val m00: Double,
    val m01: Double,
    val tx: Double,
    val m10: Double,
    val m11: Double,
    val ty: Double
) {
    fun apply(x: Double, y: Double): PointM =
        PointM(m00 * x + m01 * y + tx, m10 * x + m11 * y + ty)
}

@Serializable
private data class PlanDims(val width: Double, val height: Double)

@Serializable
private data class FloorPlanDto(
    @SerialName("floor_id") val floorId: String,
    @SerialName("svg_asset") val svgAsset: String? = null,
    @SerialName("coordinate_note") val coordinateNote: String? = null,
    @SerialName("estimated_dimensions_meters") val dims: PlanDims = PlanDims(100.0, 100.0),
    @SerialName("geo_transform") val geoTransform: GeoTransform? = null,
    val nodes: List<FloorNode> = emptyList(),
    val edges: List<FloorEdge> = emptyList()
)

class FloorPlan(
    val floorId: String,
    val widthM: Double,
    val heightM: Double,
    val nodes: List<FloorNode>,
    val edges: List<FloorEdge>,
    val svgAsset: String? = null,
    val isGeoReferenced: Boolean = false
) {
    private val nodeById: Map<String, FloorNode> = nodes.associateBy { it.id }

    val labeledNodes: List<FloorNode> =
        nodes
            .filter { it.label.isNotBlank() || it.name.isNotBlank() }
            .sortedWith(compareBy({ it.type == "corridor" }, { it.label.ifBlank { it.name } }))

    /**
     * Dominant orthogonal corridor orientation in `[−π/4, +π/4]` radians relative to `+y` (North).
     * Used so 90° axis stabilization aligns with the real building street grid (e.g. ~40.8° in Montréal)
     * as well as axis-aligned test plans (`0.0`).
     */
    val dominantCorridorAxisRad: Double = computeDominantAxis()

    private fun computeDominantAxis(): Double {
        var sumSin = 0.0
        var sumCos = 0.0
        for (edge in edges) {
            val a = nodeById[edge.fromId] ?: continue
            val b = nodeById[edge.toId] ?: continue
            if (a.type != "corridor" || b.type != "corridor") continue
            val dx = b.posM.x - a.posM.x
            val dy = b.posM.y - a.posM.y
            val len = hypot(dx, dy)
            if (len < 1.0) continue
            val h4 = 4.0 * atan2(dx, dy)
            sumSin += len * sin(h4)
            sumCos += len * cos(h4)
        }
        return if (hypot(sumSin, sumCos) > 1e-3) atan2(sumSin, sumCos) / 4.0 else 0.0
    }

    fun node(id: String): FloorNode? = nodeById[id]

    fun neighbors(nodeId: String): List<FloorNode> {
        val out = ArrayList<FloorNode>()
        for (edge in edges) {
            val other = when (nodeId) {
                edge.fromId -> edge.toId
                edge.toId -> edge.fromId
                else -> continue
            }
            nodeById[other]?.let { out.add(it) }
        }
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val NOTE_OFFSET_REGEX = Regex(
            """east\s*=\s*x\*width\s*\+\s*(-?\d+(?:\.\d+)?)\s*,\s*north\s*=\s*-\(y\*height\s*\+\s*(-?\d+(?:\.\d+)?)\)"""
        )

        fun defaultSvgAssetForFloor(floorId: String): String? {
            if (!floorId.startsWith("MB-")) return null
            val num = floorId.substringAfter("MB-").toIntOrNull() ?: return null
            if (num !in 1..7) return null
            return String.format(java.util.Locale.US, "floor-plans/MB/MB-floor-%02d.svg", num)
        }

        fun parse(text: String): FloorPlan {
            val dto = json.decodeFromString(FloorPlanDto.serializer(), text)
            val gt = dto.geoTransform
            val noteOffsets = dto.coordinateNote?.let { note ->
                NOTE_OFFSET_REGEX.find(note)?.let { m ->
                    val eastOff = m.groupValues[1].toDoubleOrNull()
                    val svgYOff = m.groupValues[2].toDoubleOrNull()
                    if (eastOff != null && svgYOff != null) eastOff to svgYOff else null
                }
            }
            val anyDirectMetric = dto.nodes.any { it.eastM != null && it.northM != null }
            val nodes = dto.nodes.map { n ->
                val p = when {
                    n.eastM != null && n.northM != null -> PointM(n.eastM, n.northM)
                    gt != null -> gt.apply(n.x, n.y)
                    noteOffsets != null -> PointM(
                        n.x * dto.dims.width + noteOffsets.first,
                        -(n.y * dto.dims.height + noteOffsets.second)
                    )
                    else -> PointM(n.x * dto.dims.width, (1.0 - n.y) * dto.dims.height)
                }
                n.copy(posM = p)
            }
            return FloorPlan(
                floorId = dto.floorId,
                widthM = dto.dims.width,
                heightM = dto.dims.height,
                nodes = nodes,
                edges = dto.edges,
                svgAsset = dto.svgAsset ?: defaultSvgAssetForFloor(dto.floorId),
                isGeoReferenced = gt != null || noteOffsets != null || anyDirectMetric
            )
        }

        fun parse(stream: InputStream): FloorPlan =
            parse(stream.bufferedReader(Charsets.UTF_8).readText())

        fun facingPsi(from: FloorNode, to: FloorNode): Double =
            atan2(to.posM.x - from.posM.x, to.posM.y - from.posM.y)
    }

    data class CorridorMatch(
        val projectedPointM: PointM,
        val distanceToEdgeM: Double,
        val alignedHeadingRad: Double?
    )

    fun matchCorridor(
        pointM: PointM,
        headingRad: Double,
        maxLateralM: Double = 2.0,
        endMarginM: Double = 2.0,
        maxHeadingErrRad: Double = Math.toRadians(20.0)
    ): CorridorMatch? {
        var best: CorridorMatch? = null
        for (edge in edges) {
            if (!edge.accessible) continue
            val a = nodeById[edge.fromId] ?: continue
            val b = nodeById[edge.toId] ?: continue
            if (a.type != "corridor" || b.type != "corridor") continue
            val ab = b.posM - a.posM
            val len = ab.norm()
            if (len < 0.5) continue
            val u = ab * (1.0 / len)
            val fwd = atan2(u.x, u.y)
            val rev = wrapAnglePi(fwd + PI)
            val dFwd = abs(angleDelta(fwd, headingRad))
            val dRev = abs(angleDelta(rev, headingRad))
            if (minOf(dFwd, dRev) > maxHeadingErrRad) continue
            val ap = pointM - a.posM
            val along = ap.x * u.x + ap.y * u.y
            if (along < -endMarginM || along > len + endMarginM) continue
            val lateral = abs(ap.x * u.y - ap.y * u.x)
            if (lateral > maxLateralM) continue
            if (best == null || lateral < best.distanceToEdgeM) {
                best = CorridorMatch(
                    projectedPointM = a.posM + u * along,
                    distanceToEdgeM = lateral,
                    alignedHeadingRad = if (dFwd <= dRev) fwd else rev
                )
            }
        }
        return best
    }
}
