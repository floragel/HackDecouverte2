package com.lync.app.ui.card

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lync.app.R
import com.lync.app.code.NfcCard
import com.lync.app.data.model.Profile
import com.lync.app.data.model.University
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens

/**
 * Shown automatically, from ANY screen, when the phone is turned landscape.
 * Tap to flip (front = identity, back = QR to connect). Tilt = holographic shine.
 */
@Composable
fun StudentCardScreen(me: Profile) {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val university by produceState<University?>(null, me.universityId) { value = runCatching { repo.university(me.universityId) }.getOrNull() }
    val live by repo.me.collectAsStateWithLifecycle()
    val profile = live ?: me

    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, spring(dampingRatio = 0.75f, stiffness = 180f), label = "flip")
    val tilt = rememberTilt()
    val haptics = com.lync.app.ui.motion.rememberHaptics()
    val density = LocalDensity.current
    val context = LocalContext.current

    // While the card is on screen, this phone answers NFC taps with its Lync code.
    DisposableEffect(profile.lyncCode) {
        NfcCard.setBroadcasting(context, profile.lyncCode)
        onDispose { NfcCard.setBroadcasting(context, null) }
    }

    Box(Modifier.fillMaxSize().background(Tokens.NightBg), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.32f), Color.Transparent))))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxHeight(0.76f)
                    .graphicsLayer {
                        rotationY = rotation + tilt.x * 14f
                        rotationX = -tilt.y * 12f
                        cameraDistance = 14f * density.density
                    }
                    .shadow(28.dp, RoundedCornerShape(Tokens.RadiusCard))
                    .pressable({ haptics.tick(); flipped = !flipped }, 0.98f),
            ) {
                if (rotation <= 90f) {
                    LyncCardFront(profile, university, Modifier.fillMaxHeight(), tilt = tilt, shine = 1f)
                } else {
                    LyncCardBack(profile, university, Modifier.fillMaxHeight().graphicsLayer { rotationY = 180f }, tilt = tilt, shine = 1f)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.card_tap_flip), color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

