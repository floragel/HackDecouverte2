package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import java.io.InputStream
import kotlin.math.atan2
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
    @Transient val posM: PointM = PointM(0.0, 0.0)
)

@Serializable
data class FloorEdge(
    @SerialName("from") val fromId: String,
    @SerialName("to") val toId: String,
    val accessible: Boolean = true
)

@Serializable
private data class PlanDims(val width: Double, val height: Double)

@Serializable
private data class FloorPlanDto(
    @SerialName("floor_id") val floorId: String,
    @SerialName("estimated_dimensions_meters") val dims: PlanDims,
    val nodes: List<FloorNode> = emptyList(),
    val edges: List<FloorEdge> = emptyList()
)

class FloorPlan(
    val floorId: String,
    val widthM: Double,
    val heightM: Double,
    val nodes: List<FloorNode>,
    val edges: List<FloorEdge>
) {
    private val nodeById: Map<String, FloorNode> = nodes.associateBy { it.id }

    val labeledNodes: List<FloorNode> =
        nodes.sortedWith(compareBy({ it.type == "corridor" }, { it.label }))

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

        fun parse(text: String): FloorPlan {
            val dto = json.decodeFromString(FloorPlanDto.serializer(), text)
            val nodes = dto.nodes.map {
                it.copy(posM = PointM(it.x * dto.dims.width, it.y * dto.dims.height))
            }
            return FloorPlan(dto.floorId, dto.dims.width, dto.dims.height, nodes, dto.edges)
        }

        fun parse(stream: InputStream): FloorPlan =
            parse(stream.bufferedReader(Charsets.UTF_8).readText())

        fun facingPsi(from: FloorNode, to: FloorNode): Double =
            atan2(to.posM.x - from.posM.x, -(to.posM.y - from.posM.y))
    }
}
