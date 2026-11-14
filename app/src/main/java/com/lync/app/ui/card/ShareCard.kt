package com.lync.app.ui.card

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lync.app.R
import com.lync.app.data.model.Profile
import com.lync.app.data.model.University
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.Wordmark
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shareable picture: card front on top, card back (Lync code) below, on the brand night blue.
 * What you see in the sheet is exactly what gets exported.
 */
@Composable
fun ShareCardImage(p: Profile, uni: University?, modifier: Modifier = Modifier) {
    val c = Campus.colors
    val tilt = Offset(0.35f, -0.25f) // frozen holographic angle so the picture has its sheen
    Column(
        modifier
            .background(Tokens.NightBg)
            .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.35f), Color.Transparent), radius = 900f))
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LyncCardFront(p, uni, Modifier.fillMaxWidth(), tilt = tilt, shine = 0.8f)
        Spacer(Modifier.height(16.dp))
        LyncCardBack(p, uni, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Wordmark(Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardSheet(p: Profile, uni: University?, onDismiss: () -> Unit) {
    val c = Campus.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter).navigationBarsPadding()) {
            Text(stringResource(R.string.share_card_title), style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(14.dp))
            // recorded into a graphics layer so it can be exported as a bitmap
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusMedia))
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) { ShareCardImage(p, uni, Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(16.dp))
            Pill(stringResource(R.string.share_card_button), {
                if (busy) return@Pill
                busy = true
                scope.launch {
                    runCatching {
                        val bitmap = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                        shareBitmap(context, bitmap, "lync-${p.username}.png")
                    }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Outlined.IosShare, contentPadding = PaddingValues(vertical = 14.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Writes the PNG in the app cache and opens the system share sheet with the image only. */
private suspend fun shareBitmap(context: Context, bitmap: Bitmap, name: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, name).also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
