package com.lync.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/** The two links of the Lync mark (1024×1024 viewBox) — same paths as res/drawable/ic_lync_mark.xml & design/lync-mark.svg. */
object LyncPaths {
    const val UPRIGHT = "M546 558H434V297a71 71 0 0 0-142 0v307c-42 44-60 120-34 194-48-32-78-88-78-150V297a183 183 0 0 1 366 0Z"
    const val HORIZONTAL = "M435 552h231a179 179 0 0 1 0 358H435a179 179 0 0 1 0-358Zm3 110a69 69 0 0 0 0 138h225a69 69 0 0 0 0-138Z"
}

/**
 * Draws the mark with each link at its own offset (in viewBox units) / alpha.
 * 0 offset + alpha 1 = the logo at rest.
 */
@Composable
fun LyncMarkParts(
    size: Dp,
    color: Color,
    uprightOffset: Pair<Float, Float>,
    horizontalOffset: Pair<Float, Float>,
    uprightAlpha: Float,
    horizontalAlpha: Float,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val upright = remember { PathParser().parsePathString(LyncPaths.UPRIGHT).toPath() }
    val horizontal = remember { PathParser().parsePathString(LyncPaths.HORIZONTAL).toPath().apply { fillType = PathFillType.EvenOdd } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 1024f * scale
        withTransform({
            translate(this@Canvas.size.width / 2f, this@Canvas.size.height / 2f)
            scale(k, k, pivot = Offset.Zero)
            translate(-512f, -512f)
        }) {
            translate(uprightOffset.first, uprightOffset.second) { drawPath(upright, color.copy(alpha = color.alpha * uprightAlpha)) }
            translate(horizontalOffset.first, horizontalOffset.second) { drawPath(horizontal, color.copy(alpha = color.alpha * horizontalAlpha)) }
        }
    }
}

/**
 * Launch animation. The Android 12+ system splash already shows the assembled mark, so we start
 * from that exact state (same size and position) and do the "click": the links pull apart and
 * snap back together with a small bounce. ~550 ms. Skipped when reduced motion is on.
 */
@Composable
fun LyncLogoIntro(size: Dp, color: Color, reduced: Boolean, onDone: () -> Unit) {
    val upY = remember { Animatable(0f) }
    val hzX = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduced) { onDone(); return@LaunchedEffect }
        kotlinx.coroutines.delay(120)
        // pull apart
        launch { upY.animateTo(-90f, tween(170, easing = FastOutSlowInEasing)) }
        hzX.animateTo(140f, tween(170, easing = FastOutSlowInEasing))
        // snap together
        launch { upY.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 700f)) }
        hzX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 700f))
        pop.animateTo(1.07f, tween(60))
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f))
        onDone()
    }
    LyncMarkParts(size, color, 0f to upY.value, hzX.value to 0f, 1f, 1f, scale = pop.value)
}
