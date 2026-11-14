package com.lync.app.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * A number whose changed digits roll up (increase) or down (decrease), digit by digit.
 * iOS: Text(value, format: .number).contentTransition(.numericText(value:)).
 */
@Composable
fun RollingNumber(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val text = value.toString()
    Row(modifier) {
        // key digits from the right so 99 -> 100 rolls nicely
        text.forEachIndexed { i, ch ->
            val place = text.length - i
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else {
                        val up = targetState > initialState
                        (slideInVertically(tween(Motion.SHORT + 40)) { if (up) it else -it } + fadeIn(tween(Motion.SHORT))) togetherWith
                            (slideOutVertically(tween(Motion.SHORT + 40)) { if (up) -it else it } + fadeOut(tween(Motion.SHORT)))
                    }.using(SizeTransform(clip = true))
                },
                label = "digit$place",
            ) { d -> Text(d.toString(), style = style, color = color) }
        }
    }
}
