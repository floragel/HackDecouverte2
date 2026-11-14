package com.hackdecouverte.pdr

sealed interface PathSegment {
    val start: PointM
    val end: PointM

    fun eval(t: Double): PointM

    fun derivative(t: Double): PointM

    data class Line(override val start: PointM, override val end: PointM) : PathSegment {
        override fun eval(t: Double): PointM = start + (end - start) * t

        override fun derivative(t: Double): PointM = end - start
    }

    data class Quad(
        override val start: PointM,
        val control: PointM,
        override val end: PointM
    ) : PathSegment {
        override fun eval(t: Double): PointM {
            val u = 1.0 - t
            return start * (u * u) + control * (2.0 * u * t) + end * (t * t)
        }

        override fun derivative(t: Double): PointM =
            (control - start) * (2.0 * (1.0 - t)) + (end - control) * (2.0 * t)
    }
}

object PathGeometry {
    private fun mid(a: PointM, b: PointM) = PointM((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

    fun smoothedSegments(points: List<PointM>): List<PathSegment> {
        val n = points.size
        if (n < 2) return emptyList()
        if (n == 2) return listOf(PathSegment.Line(points[0], points[1]))
        val out = ArrayList<PathSegment>(n)
        out.add(PathSegment.Line(points[0], mid(points[0], points[1])))
        for (i in 1 until n - 1) {
            out.add(
                PathSegment.Quad(
                    mid(points[i - 1], points[i]),
                    points[i],
                    mid(points[i], points[i + 1])
                )
            )
        }
        out.add(PathSegment.Line(mid(points[n - 2], points[n - 1]), points[n - 1]))
        return out
    }

    fun pathLength(points: List<PointM>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += points[i - 1].distanceTo(points[i])
        return total
    }
}

class MilestoneTracker(private val intervalM: Double = 5.0) {
    private var nextM = intervalM
    private val done = ArrayList<Milestone>()

    val milestones: List<Milestone>
        get() = done.toList()

    fun reset() {
        nextM = intervalM
        done.clear()
    }

    fun onSegment(from: PointM, fromDistanceM: Double, to: PointM, toDistanceM: Double) {
        val span = toDistanceM - fromDistanceM
        if (span <= 0.0) return
        while (nextM - toDistanceM <= 1e-9) {
            val f = ((nextM - fromDistanceM) / span).coerceIn(0.0, 1.0)
            done.add(Milestone(from + (to - from) * f, nextM))
            nextM += intervalM
        }
    }

    companion object {
        fun compute(
            points: List<PointM>,
            distancesM: List<Double>,
            intervalM: Double = 5.0
        ): List<Milestone> {
            val tracker = MilestoneTracker(intervalM)
            val n = minOf(points.size, distancesM.size)
            for (i in 1 until n) {
                tracker.onSegment(points[i - 1], distancesM[i - 1], points[i], distancesM[i])
            }
            return tracker.milestones
        }
    }
}
