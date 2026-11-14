package com.lync.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lync.app.R
import com.lync.app.data.model.Profile
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE signature moment (≈1.5 s): two avatars fly in from each side, the two links of the
 * Lync mark interlock between them, a ring bursts in the university color, then the shared
 * ground appears. Double haptic on the "click". Calls [onDone] when finished.
 *
 * iOS: same timeline with withAnimation(.spring) + phaseAnimator; haptic = UINotificationFeedbackGenerator(.success).
 */
@Composable
fun ConnectCelebration(me: Profile?, other: Profile, sharedCourses: Int, accent: Color, onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()

    val bg = remember { Animatable(0f) }
    val fly = remember { Animatable(if (reduced) 1f else 0f) }      // avatars travel to the center
    val links = remember { Animatable(if (reduced) 1f else 0f) }    // links interlock
    val click = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    val text = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(Unit) {
        bg.animateTo(1f, tween(if (reduced) 0 else 160))
        if (!reduced) {
            launch { fly.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 260f)) }
            delay(220)
            links.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            haptics.success()
            launch { ring.animateTo(1f, tween(620, easing = LinearOutSlowInEasing)) }
            click.animateTo(1.12f, tween(80))
            click.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f))
            text.animateTo(1f, tween(260))
            delay(520)
        } else {
            haptics.success()
            delay(900)
        }
        onDone()
    }

    Box(Modifier.fillMaxSize().background(Tokens.NightBg.copy(alpha = 0.94f * bg.value)), contentAlignment = Alignment.Center) {
        // burst ring
        Canvas(Modifier.size(320.dp)) {
            val r = ring.value
            if (r > 0f) {
                drawCircle(accent.copy(alpha = (1f - r) * 0.9f), radius = size.minDimension / 2 * (0.25f + 0.75f * r), style = Stroke(width = (10f * (1f - r) + 1f) * density))
                drawCircle(Color.White.copy(alpha = (1f - r) * 0.35f), radius = size.minDimension / 2 * (0.15f + 0.6f * r), style = Stroke(width = 2f * density))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 300.dp, height = 120.dp), contentAlignment = Alignment.Center) {
                val travel = 150f * (1f - fly.value)
                // me (left)
                Box(Modifier.offset(x = (-78 - travel).dp).graphicsLayer { alpha = fly.value.coerceIn(0f, 1f) }.clip(CircleShape).background(Color.White).padding3()) {
                    if (me != null) Avatar(me.avatarUrl, me.initials, 76.dp)
                }
                // them (right)
                Box(Modifier.offset(x = (78 + travel).dp).graphicsLayer { alpha = fly.value.coerceIn(0f, 1f) }.clip(CircleShape).background(accent).padding3()) {
                    Avatar(other.avatarUrl, other.initials, 76.dp)
                }
                // the two links meeting in the middle
                val l = links.value
                LyncMarkParts(
                    size = 64.dp, color = Color.White,
                    uprightOffset = (-300f * (1f - l)) to 0f,
                    horizontalOffset = (300f * (1f - l)) to 0f,
                    uprightAlpha = l, horizontalAlpha = l, scale = click.value,
                )
            }
            Spacer(Modifier.height(28.dp))
            Column(Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 24f }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.celebrate_title, other.firstName), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                if (sharedCourses > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(pluralStringResource(R.plurals.celebrate_courses, sharedCourses, sharedCourses), style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun Modifier.padding3() = this.then(Modifier.padding(3.dp))
