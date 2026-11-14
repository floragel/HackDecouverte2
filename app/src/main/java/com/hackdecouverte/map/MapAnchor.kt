package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.wrapAnglePi
import kotlin.math.cos
import kotlin.math.sin

data class MapAnchor(val originM: PointM, val psiRad: Double) {
    private val cosPsi = cos(psiRad)
    private val sinPsi = sin(psiRad)

    fun toMap(p: PointM): PointM = PointM(
        originM.x + cosPsi * p.x + sinPsi * p.y,
        originM.y + sinPsi * p.x - cosPsi * p.y
    )

    fun mapHeading(pdrHeadingRad: Double): Double = wrapAnglePi(psiRad + pdrHeadingRad)
}
