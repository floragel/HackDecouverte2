package com.lync.app.ui.scan

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lync.app.R
import com.lync.app.code.LyncCode
import com.lync.app.code.NfcReader
import com.lync.app.data.model.ConnectionState
import com.lync.app.data.model.Person
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.card.programLine
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Scan a Lync code with the camera, or tap phones together (NFC). Both end on the same result card.
 */
@Composable
fun ScannerScreen() {
    val c = Campus.colors
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val repo = LocalAppContainer.current.repository
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    var found by remember { mutableStateOf<Person?>(null) }
    var lookingUp by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    val onCode: (Long, String) -> Unit = { code, source ->
        if (found == null && !lookingUp) {
            lookingUp = true
            scope.launch {
                val person = runCatching { repo.profileByCode(code) }.getOrNull()
                if (person != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (person.connection == ConnectionState.NONE) runCatching { repo.connect(person.profile.id, source) }
                    found = runCatching { repo.profile(person.profile.id) }.getOrNull() ?: person
                } else notFound = true
                lookingUp = false
            }
        }
    }

    // NFC reader mode while this screen is open
    val activity = context as? Activity
    val reader = remember(activity) { activity?.let { NfcReader(it) { code -> onCode(code, "nfc") } } }
    DisposableEffect(reader) {
        reader?.start()
        onDispose { reader?.stop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) CameraAnalyzer(paused = found != null) { code -> onCode(code, "code") }

        // dim everything except the circle
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(0.96f, 1.02f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "s")
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val r = size.width * 0.36f
            val center = Offset(size.width / 2, size.height / 2)
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawCircle(Color.Transparent, radius = r, center = center, blendMode = BlendMode.Clear)
            drawCircle(Color.White, radius = r * pulse, center = center, style = Stroke(width = 3.dp.toPx()))
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).pressable({ nav.back() }), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (!hasCamera) R.string.scan_need_camera else R.string.scan_hint), style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            if (reader?.available == true) Row(
                Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Nfc, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (reader.enabled) R.string.scan_nfc_ready else R.string.scan_nfc_off), style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            if (notFound) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.net_scan_invalid), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Spacer(Modifier.height(24.dp))
        }

        AnimatedVisibility(found != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            found?.let { p ->
                Column(
                    Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp).clip(RoundedCornerShape(Tokens.RadiusCard)).background(c.bg).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(p.profile.avatarUrl, p.profile.initials, 84.dp, ring = c.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(p.profile.fullName, style = MaterialTheme.typography.headlineSmall, color = c.text)
                    Text(programLine(p.profile), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(if (p.connection == ConnectionState.CONNECTED) R.string.scan_connected else R.string.scan_requested, p.profile.firstName),
                        style = MaterialTheme.typography.titleSmall, color = c.accent,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.scan_again), { found = null; notFound = false }, Modifier.weight(1f), style = PillStyle.Tonal, contentPadding = PaddingValues(vertical = 12.dp))
                        Pill(stringResource(R.string.scan_view_profile), { val id = p.profile.id; nav.back(); nav.user(id) }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraAnalyzer(paused: Boolean, onCode: (Long) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var lastCode by remember { mutableStateOf<Long?>(null) }
    val pausedState = remember { mutableStateOf(paused) }
    pausedState.value = paused

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (!pausedState.value) {
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val stride = plane.rowStride
                            val w = image.width; val h = image.height
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val code = LyncCode.decode({ x, y -> bytes[y * stride + x].toInt() and 0xFF }, w, h)
                            // require the same code twice in a row to avoid false reads
                            if (code != null) {
                                if (code == lastCode) ContextCompat.getMainExecutor(context).execute { onCode(code) }
                                lastCode = code
                            }
                        }
                    } finally { image.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
