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
        originM.y - sinPsi * p.x + cosPsi * p.y
    )

    fun fromMap(m: PointM): PointM {
        val dx = m.x - originM.x
        val dy = m.y - originM.y
        return PointM(
            cosPsi * dx - sinPsi * dy,
            sinPsi * dx + cosPsi * dy
        )
    }

    fun mapHeading(pdrHeadingRad: Double): Double = wrapAnglePi(psiRad + pdrHeadingRad)

    fun localHeading(mapHeadingRad: Double): Double = wrapAnglePi(mapHeadingRad - psiRad)
}
