package com.hackdecouverte.pdr

/**
 * Android's step detector misses steps (often the first few of a walk, sometimes in a bag or during
 * a fast turn); the step counter runs the same pedometer with more look-back and catches them. Each
 * counter reading is compared, once [settleNs] has passed (the counter is late and batched, the
 * detector may lag too), with the detector steps up to that reading's own time: a deficit of at least
 * [minDeficit] steps is handed back to the PDR, at most [maxPerReading] per reading. The counter being
 * behind never removes steps. Pure Kotlin; times are sensor timestamps.
 */
class StepReconciler(
    private val settleNs: Long = 2_500_000_000L,
    private val toleranceNs: Long = 250_000_000L,
    private val minDeficit: Int = 2,
    private val maxPerReading: Int = 8
) {
    private var baseTotal: Long? = null
    private var baseNs = 0L
    private val detectorTimes = ArrayList<Long>()
    private val pending = ArrayDeque<Pair<Long, Long>>()
    private var recovered = 0L

    /** Steps handed back since the sensors were registered. */
    var totalRecovered = 0
        private set

    /** (counted, detected) at each settled reading, for [countRatio]. */
    private val tallies = ArrayDeque<Pair<Long, Int>>()

    /**
     * Counter steps / detector steps over the last ~[RATIO_WINDOW_STEPS] counted steps, once at least
     * [RATIO_MIN_STEPS] are counted; null before. Below 1: the detector counts steps that are not there.
     */
    fun countRatio(): Double? {
        val last = tallies.lastOrNull() ?: return null
        val first = tallies.firstOrNull { last.first - it.first <= RATIO_WINDOW_STEPS } ?: return null
        val counted = last.first - first.first
        val detected = last.second - first.second
        if (counted < RATIO_MIN_STEPS || detected <= 0) return null
        return counted.toDouble() / detected
    }

    fun reset() {
        baseTotal = null
        detectorTimes.clear()
        pending.clear()
        tallies.clear()
        recovered = 0
    }

    fun onDetector(timestampNs: Long) {
        if (baseTotal == null || timestampNs <= baseNs) return
        if (detectorTimes.size >= MAX_DETECTOR_TIMES) return
        // Nearly always in order; keep the list sorted anyway.
        var i = detectorTimes.size
        while (i > 0 && detectorTimes[i - 1] > timestampNs) i--
        detectorTimes.add(i, timestampNs)
    }

    /** [total]: the counter's value (steps since boot) at [timestampNs] (time of the last counted step). */
    fun onCounter(timestampNs: Long, total: Long) {
        if (baseTotal == null) {
            baseTotal = total
            baseNs = timestampNs
            return
        }
        pending.addLast(timestampNs to total)
    }

    /** Missed steps to add now ([nowNs]: current sensor time). */
    fun poll(nowNs: Long): Int {
        var add = 0
        while (pending.isNotEmpty() && pending.first().first <= nowNs - settleNs) {
            val (t, total) = pending.removeFirst()
            val base = baseTotal ?: return add
            val counted = total - base
            if (counted < 0) { // counter restarted (reboot): start over from here
                baseTotal = total
                baseNs = t
                detectorTimes.clear()
                tallies.clear()
                recovered = 0
                continue
            }
            val detected = detectedUpTo(t + toleranceNs)
            tallies.addLast(counted to detected)
            while (tallies.size > 400) tallies.removeFirst()
            val deficit = counted - detected - recovered
            if (deficit >= minDeficit) {
                val k = minOf(deficit, maxPerReading.toLong()).toInt()
                recovered += k
                totalRecovered += k
                add += k
            }
        }
        return add
    }

    private fun detectedUpTo(t: Long): Int {
        var lo = 0
        var hi = detectorTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (detectorTimes[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private companion object {
        const val MAX_DETECTOR_TIMES = 50_000
        const val RATIO_WINDOW_STEPS = 60
        const val RATIO_MIN_STEPS = 20
    }
}
