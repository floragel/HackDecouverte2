package com.lync.app.ui.card

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Device tilt in [-1, 1] (x = left/right, y = forward/back) from the gyroscope-fused
 * rotation vector, relative to how the user is holding the phone, in screen coordinates
 * (works in portrait and landscape). The neutral position slowly re-centers.
 *
 * iOS port: CMMotionManager.deviceMotion.attitude (pitch/roll) with the same mapping.
 */
@Composable
fun rememberTilt(): Offset {
    val context = LocalContext.current
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rot = FloatArray(9); val remapped = FloatArray(9); val orient = FloatArray(3)
        var basePitch = Float.NaN; var baseRoll = Float.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                var tx: Float; var ty: Float
                @Suppress("DEPRECATION") val displayRotation = wm.defaultDisplay.rotation
                if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR || e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    val (ax, ay) = when (displayRotation) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
                    SensorManager.getOrientation(remapped, orient)
                    val pitch = orient[1]; val roll = orient[2]
                    if (basePitch.isNaN()) { basePitch = pitch; baseRoll = roll }
                    basePitch += (pitch - basePitch) * 0.004f   // slow re-centering
                    baseRoll += (roll - baseRoll) * 0.004f
                    tx = ((roll - baseRoll) / 0.42f)
                    ty = ((pitch - basePitch) / 0.42f)
                } else {
                    // fallback: gravity in screen coordinates
                    val (gx, gy) = when (displayRotation) {
                        Surface.ROTATION_90 -> -e.values[1] to e.values[0]
                        Surface.ROTATION_270 -> e.values[1] to -e.values[0]
                        else -> e.values[0] to e.values[1]
                    }
                    tx = -gx / 9.81f * 1.6f
                    ty = (gy / 9.81f - 0.5f) * 1.6f
                }
                if (tx.isNaN() || ty.isNaN()) { basePitch = Float.NaN; return }
                x += (tx.coerceIn(-1f, 1f) - x) * 0.18f
                y += (ty.coerceIn(-1f, 1f) - y) * 0.18f
                if (x.isNaN() || y.isNaN()) { x = 0f; y = 0f }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        (rotationSensor ?: gravity)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }
    return Offset(x, y)
}

private val Sparkles: List<Triple<Float, Float, Float>> = run {
    // fixed pseudo-random positions (x, y, phase) so every card sparkles the same way
    var s = 7L
    fun rnd(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) % 10000) / 10000f }
    List(46) { Triple(rnd(), rnd(), rnd() * 2 * PI.toFloat()) }
}

/**
 * Holographic foil: a rainbow film whose angle follows the tilt, a moving glint,
 * twinkling sparkles and an edge light. [strength] 0..1.
 */
fun Modifier.hologram(rawTilt: Offset, strength: Float = 1f) = this.drawWithContent {
    drawContent()
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithContent
    val tilt = if (rawTilt.x.isNaN() || rawTilt.y.isNaN()) Offset.Zero else rawTilt
    val mag = (abs(tilt.x) + abs(tilt.y)).coerceIn(0f, 1.4f)
    val k = strength * (0.45f + 0.55f * mag)

    // 1. rainbow film, direction rotates with tilt
    val angle = atan2Safe(tilt.y, tilt.x) + PI.toFloat() / 4
    val dx = cos(angle) * w; val dy = sin(angle) * h
    val shift = (tilt.x + tilt.y) * 0.5f
    val film = listOf(0xFFFF6EC7, 0xFF7DF9FF, 0xFFB388FF, 0xFFFFF07D, 0xFF7DFFB2, 0xFFFF6EC7).map { Color(it).copy(alpha = 0.30f * k) }
    drawRect(
        Brush.linearGradient(film, start = Offset(w / 2 - dx * (0.9f + shift), h / 2 - dy * (0.9f + shift)), end = Offset(w / 2 + dx * (0.9f - shift), h / 2 + dy * (0.9f - shift))),
        blendMode = BlendMode.Overlay,
    )

    // 2. glint band sweeping across
    val g = (0.5f + tilt.x * 0.55f + tilt.y * 0.35f)
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent, (g - 0.12f).coerceIn(0f, 1f) to Color.Transparent,
            g.coerceIn(0f, 1f) to Color.White.copy(alpha = 0.30f * k),
            (g + 0.12f).coerceIn(0f, 1f) to Color.Transparent, 1f to Color.Transparent,
            start = Offset(0f, 0f), end = Offset(w, h),
        ),
        blendMode = BlendMode.Screen,
    )

    // 3. sparkles that light up at specific angles
    for ((sx, sy, phase) in Sparkles) {
        val b = max(0f, cos(phase + tilt.x * 7f + tilt.y * 5f)).let { it * it * it } * (0.2f + mag).coerceAtMost(1f)
        if (b < 0.05f) continue
        val c = Offset(sx * w, sy * h)
        val rr = (1.2f + 2.6f * b) * density
        drawCircle(Color.White.copy(alpha = 0.85f * b * strength), radius = rr, center = c, blendMode = BlendMode.Screen)
        drawLine(Color.White.copy(alpha = 0.5f * b * strength), Offset(c.x - rr * 3, c.y), Offset(c.x + rr * 3, c.y), strokeWidth = density * 0.8f, blendMode = BlendMode.Screen)
        drawLine(Color.White.copy(alpha = 0.5f * b * strength), Offset(c.x, c.y - rr * 3), Offset(c.x, c.y + rr * 3), strokeWidth = density * 0.8f, blendMode = BlendMode.Screen)
    }

    // 4. soft light following the tilt + edge highlight
    drawRect(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.20f * k), Color.Transparent), center = Offset(w * (0.5f + tilt.x * 0.6f), h * (0.5f + tilt.y * 0.6f)), radius = w * 0.5f),
        blendMode = BlendMode.Overlay,
    )
    drawRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f * k), Color.Transparent, Color.White.copy(alpha = 0.18f * k)),
            start = Offset(w * (0.5f - tilt.x), 0f), end = Offset(w * (0.5f + tilt.x), h)),
        style = Stroke(width = 1.5f * density),
    )
}

private fun atan2Safe(y: Float, x: Float): Float = if (x == 0f && y == 0f) 0f else kotlin.math.atan2(y, x)
