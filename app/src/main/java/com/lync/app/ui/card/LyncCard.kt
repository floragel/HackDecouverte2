package com.lync.app.ui.card

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lync.app.code.LyncCodeView
import com.lync.app.R
import com.lync.app.data.model.CardPayload
import com.lync.app.data.model.Profile
import com.lync.app.data.model.StatusPreset
import com.lync.app.data.model.University
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.LyncMark
import com.lync.app.ui.profile.statusLabel
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.DisplayFamily
import com.lync.app.ui.theme.TextFamily
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real ID-1 card ratio (85.6 × 54 mm). */
const val CARD_RATIO = 1.586f

/** "Computer Science, year 1" */
@Composable
fun programLine(p: Profile): String =
    listOfNotNull(p.program, p.year?.let { stringResource(R.string.profile_year_short, it) }).joinToString(", ")

/**
 * The Lync card front. Used at two sizes — profile header and landscape mode —
 * and everything scales from the card width (k), so both are pixel-identical in proportion.
 */
@Composable
fun LyncCardFront(p: Profile, uni: University?, modifier: Modifier = Modifier, tilt: Offset = Offset.Zero, shine: Float = 0f) {
    val c = Campus.colors
    BoxWithConstraints(modifier.aspectRatio(CARD_RATIO).clip(RoundedCornerShape(Tokens.RadiusCard))) {
        val k = maxWidth.value / 340f
        val deep = lerp(c.accent, Color.Black, 0.45f)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(c.accent, deep), start = Offset.Zero, end = Offset(maxWidth.value * 3, maxHeight.value * 3)))
                .then(if (shine > 0f) Modifier.hologram(tilt, shine) else Modifier),
        ) {
            // oversized watermark of the mark — the card's signature
            LyncMark((210 * k).dp, Color.White.copy(alpha = 0.08f), Modifier.align(Alignment.BottomEnd).offset(x = (40 * k - tilt.x * 14 * k).dp, y = (50 * k - tilt.y * 14 * k).dp))

            Column(Modifier.fillMaxSize().offset(x = (tilt.x * 3 * k).dp, y = (tilt.y * 3 * k).dp).padding((20 * k).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uni?.shortName ?: "", color = Color.White, fontFamily = DisplayFamily, fontSize = (17 * k).sp, letterSpacing = (-0.2).sp,
                        modifier = Modifier.weight(1f))
                    LyncMark((20 * k).dp, Color.White.copy(alpha = 0.9f))
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.clip(RoundedCornerShape((18 * k).dp)).background(Color.White.copy(alpha = 0.22f)).padding((2.5f * k).dp)) {
                        Avatar(p.avatarUrl, p.initials, (70 * k).dp, modifier = Modifier.clip(RoundedCornerShape((16 * k).dp)))
                    }
                    Spacer(Modifier.width((14 * k).dp))
                    Column(Modifier.weight(1f)) {
                        StatusPreset.of(p.statusKey)?.let {
                            Text(
                                "${it.emoji} ${statusLabel(it)}", color = Color.White, fontFamily = TextFamily, fontSize = (11.5f * k).sp, maxLines = 1,
                                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = (9 * k).dp, vertical = (4 * k).dp),
                            )
                            Spacer(Modifier.height((8 * k).dp))
                        }
                        Text(p.fullName, color = Color.White, fontFamily = DisplayFamily, fontSize = (22 * k).sp, lineHeight = (24 * k).sp,
                            letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(programLine(p), color = Color.White.copy(alpha = 0.78f), fontFamily = TextFamily, fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun LyncCardBack(p: Profile, uni: University?, modifier: Modifier = Modifier, tilt: Offset = Offset.Zero, shine: Float = 0f) {
    val c = Campus.colors
    BoxWithConstraints(
        modifier.aspectRatio(CARD_RATIO).clip(RoundedCornerShape(Tokens.RadiusCard)).background(Color.White)
            .then(if (shine > 0f) Modifier.hologram(tilt, shine * 0.6f) else Modifier),
    ) {
        val k = maxWidth.value / 340f
        Row(Modifier.fillMaxSize().padding((18 * k).dp), verticalAlignment = Alignment.CenterVertically) {
            val code = p.lyncCode
            Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (code != null) LyncCodeView(code, Modifier.fillMaxSize(), ink = Tokens.Night, data = c.accent)
            }
            Spacer(Modifier.width((18 * k).dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(uni?.shortName ?: "", color = Tokens.Slate, fontFamily = DisplayFamily, fontSize = (14 * k).sp)
                Column {
                    Text(stringResource(R.string.card_scan_me), color = Tokens.Night, fontFamily = DisplayFamily, fontSize = (20 * k).sp, lineHeight = (22 * k).sp, letterSpacing = (-0.3).sp)
                    Spacer(Modifier.height((6 * k).dp))
                    Text(stringResource(R.string.card_tap_nfc), color = Tokens.Slate, fontFamily = TextFamily, fontSize = (12 * k).sp, lineHeight = (15 * k).sp, maxLines = 3)
                }
                Text("@${p.username}", color = Tokens.Slate, fontFamily = TextFamily, fontSize = (11 * k).sp, maxLines = 1)
            }
        }
    }
}

