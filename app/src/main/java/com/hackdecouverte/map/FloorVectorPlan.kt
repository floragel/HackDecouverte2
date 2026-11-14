package com.hackdecouverte.map

import java.io.InputStream

data class MetricBounds(
    val minEastM: Double,
    val minNorthM: Double,
    val maxEastM: Double,
    val maxNorthM: Double
) {
    val widthM: Double get() = maxEastM - minEastM
    val heightM: Double get() = maxNorthM - minNorthM
}

/**
 * Pure-Kotlin vector representation of an aligned building floor plan SVG
 * from the `mb-floor-plans` branch.
 *
 * Coordinates in [packedEastNorthM] are stored as interleaved `(eastM, northM)` pairs
 * in True-North metric coordinates (`+x` = East, `+y` = True North, `(0, 0)` = elevator core).
 */
class FloorVectorPlan(
    val floorId: String,
    val viewBoundsM: MetricBounds,
    val contentBoundsM: MetricBounds,
    val packedEastNorthM: FloatArray,
    val contourStartPairIndex: IntArray,
    val contourPointCounts: IntArray,
    val contourClosed: BooleanArray
) {
    val contourCount: Int get() = contourPointCounts.size
    val totalPoints: Int get() = packedEastNorthM.size / 2

    companion object {
        fun parseSvg(floorId: String, stream: InputStream): FloorVectorPlan =
            parseSvg(floorId, stream.bufferedReader(Charsets.UTF_8).readText())

        fun parseSvg(floorId: String, svgText: String): FloorVectorPlan {
            val viewBox = parseViewBox(svgText) ?: MetricBounds(-44.0, -40.0, 50.0, 34.0)
            val wallsIdx = svgText.indexOf("id=\"walls-and-text\"").let {
                if (it >= 0) it else svgText.indexOf("id='walls-and-text'")
            }
            val searchFrom = if (wallsIdx >= 0) wallsIdx else 0
            val matrix = parseMatrix(svgText, searchFrom)
                ?: doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

            val pathStart = svgText.indexOf("<path", searchFrom)
            require(pathStart >= 0) { "No <path> found in floor SVG for $floorId" }
            val dAttrIdx = findPathDAttribute(svgText, pathStart)
            require(dAttrIdx >= 0) { "No d attribute on <path> in floor SVG for $floorId" }
            val quoteChar = svgText[dAttrIdx]
            val dEnd = svgText.indexOf(quoteChar, dAttrIdx + 1)
            require(dEnd > dAttrIdx) { "Unterminated path d attribute in $floorId" }

            return decodePathData(
                floorId = floorId,
                viewBox = viewBox,
                m = matrix,
                s = svgText,
                start = dAttrIdx + 1,
                end = dEnd
            )
        }

        private fun parseViewBox(svg: String): MetricBounds? {
            val idx = svg.indexOf("viewBox=")
            if (idx < 0) return null
            val q = svg.getOrNull(idx + 8) ?: return null
            if (q != '"' && q != '\'') return null
            val end = svg.indexOf(q, idx + 9)
            if (end < 0) return null
            val parts = svg.substring(idx + 9, end).trim().split(Regex("[\\s,]+"))
            if (parts.size < 4) return null
            val minX = parts[0].toDoubleOrNull() ?: return null
            val minSvgY = parts[1].toDoubleOrNull() ?: return null
            val w = parts[2].toDoubleOrNull() ?: return null
            val h = parts[3].toDoubleOrNull() ?: return null
            // SVG y = -north => north in [-(minSvgY + h), -minSvgY]
            return MetricBounds(
                minEastM = minX,
                minNorthM = -(minSvgY + h),
                maxEastM = minX + w,
                maxNorthM = -minSvgY
            )
        }

        private fun parseMatrix(svg: String, fromIndex: Int): DoubleArray? {
            val tagEnd = svg.indexOf('>', fromIndex)
            if (tagEnd < 0) return null
            val header = svg.substring(fromIndex, tagEnd)
            val mIdx = header.indexOf("matrix(")
            if (mIdx < 0) return null
            val closeIdx = header.indexOf(')', mIdx + 7)
            if (closeIdx < 0) return null
            val nums = header.substring(mIdx + 7, closeIdx)
                .trim()
                .split(Regex("[\\s,]+"))
                .mapNotNull { it.toDoubleOrNull() }
            return if (nums.size == 6) nums.toDoubleArray() else null
        }

        private fun findPathDAttribute(svg: String, pathStart: Int): Int {
            var i = pathStart + 5
            val len = svg.length
            while (i < len) {
                val ch = svg[i]
                if (ch == '>') return -1
                if ((ch == 'd' || ch == 'D') && svg[i - 1].isWhitespace()) {
                    var j = i + 1
                    while (j < len && svg[j].isWhitespace()) j++
                    if (j < len && svg[j] == '=') {
                        j++
                        while (j < len && svg[j].isWhitespace()) j++
                        if (j < len && (svg[j] == '"' || svg[j] == '\'')) return j
                    }
                }
                i++
            }
            return -1
        }

        private fun decodePathData(
            floorId: String,
            viewBox: MetricBounds,
            m: DoubleArray,
            s: String,
            start: Int,
            end: Int
        ): FloorVectorPlan {
            val a = m[0]
            val b = m[1]
            val c = m[2]
            val d = m[3]
            val e = m[4]
            val f = m[5]

            var coords = FloatArray(16_384)
            var coordLen = 0
            var starts = IntArray(512)
            var counts = IntArray(512)
            var closedFlags = BooleanArray(512)
            var contourLen = 0

            var curStartPair = -1
            var curPairs = 0
            var cmd = 'M'
            var curRawX = 0.0
            var curRawY = 0.0

            var minE = Double.POSITIVE_INFINITY
            var minN = Double.POSITIVE_INFINITY
            var maxE = Double.NEGATIVE_INFINITY
            var maxN = Double.NEGATIVE_INFINITY

            fun ensureCoordCap(extraFloats: Int) {
                if (coordLen + extraFloats > coords.size) {
                    coords = coords.copyOf(maxOf(coords.size * 2, coordLen + extraFloats))
                }
            }

            fun ensureContourCap() {
                if (contourLen + 1 > starts.size) {
                    val next = starts.size * 2
                    starts = starts.copyOf(next)
                    counts = counts.copyOf(next)
                    closedFlags = closedFlags.copyOf(next)
                }
            }

            fun finishContour(closed: Boolean) {
                if (curStartPair >= 0 && curPairs >= 2) {
                    ensureContourCap()
                    starts[contourLen] = curStartPair
                    counts[contourLen] = curPairs
                    closedFlags[contourLen] = closed
                    contourLen++
                } else if (curStartPair >= 0 && curPairs == 1) {
                    coordLen -= 2
                }
                curStartPair = -1
                curPairs = 0
            }

            fun emitRawPoint(rx: Double, ry: Double) {
                val east = a * rx + c * ry + e
                val north = -(b * rx + d * ry + f)
                if (east < minE) minE = east
                if (east > maxE) maxE = east
                if (north < minN) minN = north
                if (north > maxN) maxN = north
                ensureCoordCap(2)
                coords[coordLen++] = east.toFloat()
                coords[coordLen++] = north.toFloat()
                curPairs++
            }

            var idx = start
            fun skipSep() {
                while (idx < end) {
                    val ch = s[idx]
                    if (ch == ' ' || ch == ',' || ch == '\n' || ch == '\r' || ch == '\t') idx++ else break
                }
            }

            fun readNumber(): Double? {
                skipSep()
                if (idx >= end) return null
                val first = s[idx]
                if (!(first in '0'..'9' || first == '-' || first == '+' || first == '.')) return null
                val numStart = idx
                if (first == '-' || first == '+') idx++
                var seenDot = false
                while (idx < end) {
                    val ch = s[idx]
                    if (ch in '0'..'9') {
                        idx++
                    } else if (ch == '.' && !seenDot) {
                        seenDot = true
                        idx++
                    } else if (ch == 'e' || ch == 'E') {
                        idx++
                        if (idx < end && (s[idx] == '+' || s[idx] == '-')) idx++
                    } else {
                        break
                    }
                }
                return s.substring(numStart, idx).toDoubleOrNull()
            }

            while (idx < end) {
                skipSep()
                if (idx >= end) break
                val ch = s[idx]
                if (ch.isLetter()) {
                    idx++
                    when (ch) {
                        'Z', 'z' -> {
                            finishContour(closed = true)
                            continue
                        }
                        'M', 'm' -> {
                            finishContour(closed = false)
                            curStartPair = coordLen / 2
                            cmd = ch
                        }
                        else -> cmd = ch
                    }
                }
                when (cmd) {
                    'M' -> {
                        val x = readNumber() ?: continue
                        val y = readNumber() ?: continue
                        if (curStartPair < 0) curStartPair = coordLen / 2
                        curRawX = x
                        curRawY = y
                        emitRawPoint(curRawX, curRawY)
                        cmd = 'L'
                    }
                    'm' -> {
                        val dx = readNumber() ?: continue
                        val dy = readNumber() ?: continue
                        if (curStartPair < 0) curStartPair = coordLen / 2
                        curRawX += dx
                        curRawY += dy
                        emitRawPoint(curRawX, curRawY)
                        cmd = 'l'
                    }
                    'L' -> {
                        val x = readNumber() ?: continue
                        val y = readNumber() ?: continue
                        curRawX = x
                        curRawY = y
                        emitRawPoint(curRawX, curRawY)
                    }
                    'l' -> {
                        val dx = readNumber() ?: continue
                        val dy = readNumber() ?: continue
                        curRawX += dx
                        curRawY += dy
                        emitRawPoint(curRawX, curRawY)
                    }
                    'H' -> {
                        val x = readNumber() ?: continue
                        curRawX = x
                        emitRawPoint(curRawX, curRawY)
                    }
                    'h' -> {
                        val dx = readNumber() ?: continue
                        curRawX += dx
                        emitRawPoint(curRawX, curRawY)
                    }
                    'V' -> {
                        val y = readNumber() ?: continue
                        curRawY = y
                        emitRawPoint(curRawX, curRawY)
                    }
                    'v' -> {
                        val dy = readNumber() ?: continue
                        curRawY += dy
                        emitRawPoint(curRawX, curRawY)
                    }
                    else -> {
                        readNumber()
                    }
                }
            }
            finishContour(closed = false)

            val contentBounds = if (minE.isFinite()) {
                MetricBounds(minE, minN, maxE, maxN)
            } else {
                viewBox
            }

            return FloorVectorPlan(
                floorId = floorId,
                viewBoundsM = viewBox,
                contentBoundsM = contentBounds,
                packedEastNorthM = coords.copyOf(coordLen),
                contourStartPairIndex = starts.copyOf(contourLen),
                contourPointCounts = counts.copyOf(contourLen),
                contourClosed = closedFlags.copyOf(contourLen)
            )
        }
    }
}
