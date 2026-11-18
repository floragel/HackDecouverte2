package com.hackdecouverte.rec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.Writer

/**
 * A walk recorded on the phone, to be replayed through the same PDR + localization code on a computer
 * (pure Kotlin, no Android). File = gzip text: line 1 is the JSON [RecordingHeader], then one event per
 * line, in the order the app received them. All times are `elapsedRealtimeNanos` (CLOCK_BOOTTIME),
 * the clock the whole app runs on.
 *
 * ```
 * S,type,t,acc,p|x,lagUs,v0,v1,…   sensor sample; p = fed to the PDR pipeline, x = extra (research only);
 *                                   lagUs = arrival − timestamp (the live clock at processing time)
 * A,type,t,acc                      sensor accuracy change
 * G,t,lat,lon,accM,provider,altM,vAccM,bearingDeg,speed,declDeg,sats   fix as the pipeline got it
 * N,t,c:svid:cn0:azDeg:elDeg:used:fMHz,…            GNSS satellites (no fix needed: works indoors)
 * W,t,bssid/rssi/freqMHz/ftm/ageMs,…                Wi-Fi scan (no SSID: hotspot names are personal)
 * R,t,bssid/status/distMm/sdMm/rssi/attempted/ok,…  Wi-Fi RTT (802.11mc FTM) ranging
 * M,t,id,eastM,northM,label         ground truth: the walker tapped where they really are
 * L,t,steps,pdrX,pdrY,headingRad,state,eastM,northM,radiusM   what the phone estimated live
 * E,t,name,detail                   anything else (pause, floor change, note)
 * ```
 * Empty field = unknown. Fields never contain commas or line breaks ([clean]).
 */
@Serializable
data class RecordingHeader(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val siteId: String,
    val floorId: String,
    val startedWallMs: Long,
    val startedNs: Long,
    val device: String = "",
    val sdk: Int = 0,
    val appVersion: String = "",
    /** HARDWARE, FALLBACK or NONE: which step source the PDR used. */
    val stepSource: String,
    val stepLengthM: Double,
    val cadenceHz: Double? = null,
    val declinationDeg: Double,
    val sensors: List<SensorInfo> = emptyList(),
    val wifiRttSupported: Boolean = false,
    val note: String = ""
) {
    companion object {
        const val FORMAT = "hackdecouverte-walk"
        const val VERSION = 1
    }
}

@Serializable
data class SensorInfo(
    val type: Int,
    val name: String,
    val vendor: String,
    val resolution: Float,
    val maxRange: Float,
    val minDelayUs: Int,
    val fifoMax: Int = 0
)

sealed interface RecEvent {
    val tNs: Long

    data class Sample(
        val type: Int, override val tNs: Long, val accuracy: Int, val pipeline: Boolean,
        val lagUs: Long, val values: FloatArray
    ) : RecEvent {
        override fun equals(other: Any?) = other is Sample && type == other.type && tNs == other.tNs &&
            accuracy == other.accuracy && pipeline == other.pipeline && lagUs == other.lagUs && values.contentEquals(other.values)
        override fun hashCode() = 31 * (31 * type + tNs.hashCode()) + values.contentHashCode()
    }

    data class Accuracy(val type: Int, override val tNs: Long, val accuracy: Int) : RecEvent

    data class Fix(
        override val tNs: Long, val lat: Double, val lon: Double, val accuracyM: Double, val provider: String,
        val altitudeM: Double?, val verticalAccuracyM: Double?, val bearingDeg: Double?, val speedMps: Double,
        val declinationDeg: Double, val satellitesUsed: Int
    ) : RecEvent

    data class Satellite(
        val constellation: Int, val svid: Int, val cn0DbHz: Float, val azimuthDeg: Float,
        val elevationDeg: Float, val usedInFix: Boolean, val carrierMHz: Float?
    )

    data class Gnss(override val tNs: Long, val satellites: List<Satellite>) : RecEvent

    data class AccessPoint(val bssid: String, val rssi: Int, val freqMHz: Int, val ftmResponder: Boolean, val ageMs: Long)

    data class WifiScan(override val tNs: Long, val aps: List<AccessPoint>) : RecEvent

    data class Range(
        val bssid: String, val status: Int, val distanceMm: Int, val stdDevMm: Int, val rssi: Int,
        val attempted: Int, val successful: Int
    )

    data class Rtt(override val tNs: Long, val ranges: List<Range>) : RecEvent

    data class Marker(override val tNs: Long, val id: Int, val eastM: Double, val northM: Double, val label: String) : RecEvent

    data class Live(
        override val tNs: Long, val steps: Int, val pdrX: Double, val pdrY: Double, val headingRad: Double,
        val state: String, val eastM: Double?, val northM: Double?, val radiusM: Double?
    ) : RecEvent

    data class Note(override val tNs: Long, val name: String, val detail: String) : RecEvent
}

data class Recording(val header: RecordingHeader, val events: List<RecEvent>) {
    val markers: List<RecEvent.Marker> get() = events.filterIsInstance<RecEvent.Marker>()
    val fixes: List<RecEvent.Fix> get() = events.filterIsInstance<RecEvent.Fix>()
    val endNs: Long get() = events.maxOfOrNull { it.tNs } ?: header.startedNs
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** No separators inside a field. */
internal fun clean(s: String): String = s.replace(Regex("[,\\n\\r/:;]"), " ").trim()

private fun Double?.f(): String = this?.takeIf { it.isFinite() }?.toString() ?: ""
private fun Float?.f(): String = this?.takeIf { it.isFinite() }?.toString() ?: ""

/** Writes one recording (header first, then events); the caller owns [out] (gzip stream, buffering). */
class RecordingWriter(private val out: Writer) {
    private var headerWritten = false

    fun header(h: RecordingHeader) {
        check(!headerWritten) { "header already written" }
        out.write(json.encodeToString(RecordingHeader.serializer(), h))
        out.write("\n")
        headerWritten = true
    }

    fun write(e: RecEvent) {
        check(headerWritten) { "header first" }
        out.write(line(e))
        out.write("\n")
    }

    fun flush() = out.flush()

    companion object {
        fun line(e: RecEvent): String = when (e) {
            is RecEvent.Sample -> buildString {
                append("S,").append(e.type).append(',').append(e.tNs).append(',').append(e.accuracy)
                append(if (e.pipeline) ",p," else ",x,").append(e.lagUs)
                for (v in e.values) append(',').append(v)
            }
            is RecEvent.Accuracy -> "A,${e.type},${e.tNs},${e.accuracy}"
            is RecEvent.Fix -> listOf(
                "G", e.tNs, e.lat, e.lon, e.accuracyM, clean(e.provider), e.altitudeM.f(), e.verticalAccuracyM.f(),
                e.bearingDeg.f(), e.speedMps, e.declinationDeg, e.satellitesUsed
            ).joinToString(",")
            is RecEvent.Gnss -> (listOf("N", e.tNs.toString()) + e.satellites.map {
                "${it.constellation}:${it.svid}:${it.cn0DbHz}:${it.azimuthDeg}:${it.elevationDeg}:${if (it.usedInFix) 1 else 0}:${it.carrierMHz.f()}"
            }).joinToString(",")
            is RecEvent.WifiScan -> (listOf("W", e.tNs.toString()) + e.aps.map {
                "${clean(it.bssid.replace(':', '-'))}/${it.rssi}/${it.freqMHz}/${if (it.ftmResponder) 1 else 0}/${it.ageMs}"
            }).joinToString(",")
            is RecEvent.Rtt -> (listOf("R", e.tNs.toString()) + e.ranges.map {
                "${clean(it.bssid.replace(':', '-'))}/${it.status}/${it.distanceMm}/${it.stdDevMm}/${it.rssi}/${it.attempted}/${it.successful}"
            }).joinToString(",")
            is RecEvent.Marker -> "M,${e.tNs},${e.id},${e.eastM},${e.northM},${clean(e.label)}"
            is RecEvent.Live -> listOf(
                "L", e.tNs, e.steps, e.pdrX, e.pdrY, e.headingRad, clean(e.state), e.eastM.f(), e.northM.f(), e.radiusM.f()
            ).joinToString(",")
            is RecEvent.Note -> "E,${e.tNs},${clean(e.name)},${clean(e.detail)}"
        }
    }
}

object RecordingReader {

    /** Parses a whole recording; a truncated last line (app killed mid-write) and unknown lines are skipped. */
    fun read(reader: BufferedReader): Recording {
        val first = reader.readLine() ?: error("empty recording")
        val header = json.decodeFromString(RecordingHeader.serializer(), first)
        require(header.format == RecordingHeader.FORMAT) { "not a walk recording: ${header.format}" }
        val events = ArrayList<RecEvent>()
        while (true) {
            val l = reader.readLine() ?: break
            runCatching { parse(l) }.getOrNull()?.let(events::add)
        }
        return Recording(header, events)
    }

    private fun String.d(): Double? = takeIf { it.isNotEmpty() }?.toDouble()
    private fun String.fl(): Float? = takeIf { it.isNotEmpty() }?.toFloat()
    private fun String.mac() = replace('-', ':')

    fun parse(line: String): RecEvent? {
        val p = line.split(',')
        return when (p[0]) {
            "S" -> RecEvent.Sample(
                p[1].toInt(), p[2].toLong(), p[3].toInt(), p[4] == "p", p[5].toLong(),
                FloatArray(p.size - 6) { p[6 + it].toFloat() }
            )
            "A" -> RecEvent.Accuracy(p[1].toInt(), p[2].toLong(), p[3].toInt())
            "G" -> RecEvent.Fix(
                p[1].toLong(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5], p[6].d(), p[7].d(), p[8].d(),
                p[9].toDouble(), p[10].toDouble(), p[11].toInt()
            )
            "N" -> RecEvent.Gnss(p[1].toLong(), p.drop(2).filter { it.isNotEmpty() }.map {
                val s = it.split(':')
                RecEvent.Satellite(s[0].toInt(), s[1].toInt(), s[2].toFloat(), s[3].toFloat(), s[4].toFloat(), s[5] == "1", s[6].fl())
            })
            "W" -> RecEvent.WifiScan(p[1].toLong(), p.drop(2).filter { it.isNotEmpty() }.map {
                val s = it.split('/')
                RecEvent.AccessPoint(s[0].mac(), s[1].toInt(), s[2].toInt(), s[3] == "1", s[4].toLong())
            })
            "R" -> RecEvent.Rtt(p[1].toLong(), p.drop(2).filter { it.isNotEmpty() }.map {
                val s = it.split('/')
                RecEvent.Range(s[0].mac(), s[1].toInt(), s[2].toInt(), s[3].toInt(), s[4].toInt(), s[5].toInt(), s[6].toInt())
            })
            "M" -> RecEvent.Marker(p[1].toLong(), p[2].toInt(), p[3].toDouble(), p[4].toDouble(), p.getOrElse(5) { "" })
            "L" -> RecEvent.Live(
                p[1].toLong(), p[2].toInt(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6],
                p[7].d(), p[8].d(), p[9].d()
            )
            "E" -> RecEvent.Note(p[1].toLong(), p[2], p.getOrElse(3) { "" })
            else -> null
        }
    }
}
