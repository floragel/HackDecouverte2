package com.lync.app.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lync.app.R
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.PostKind
import com.lync.app.data.model.Profile
import com.lync.app.data.model.ProfileStats
import com.lync.app.data.model.StatusPreset
import com.lync.app.data.model.University
import com.lync.app.ui.card.LyncCardBack
import com.lync.app.ui.card.LyncCardFront
import com.lync.app.ui.card.rememberTilt
import com.lync.app.ui.components.pressable
import com.lync.app.ui.compose.gradientOf
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.DisplayFamily
import com.lync.app.ui.theme.Tokens

/** The Lync card as a profile header: tilts with the phone, tap to flip to the Lync code. */
@Composable
fun FlippableCard(p: Profile, uni: University?, modifier: Modifier = Modifier) {
    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, spring(dampingRatio = 0.75f, stiffness = 200f), label = "flip")
    val density = LocalDensity.current
    val tilt = rememberTilt()
    val haptics = com.lync.app.ui.motion.rememberHaptics()
    Box(
        modifier.padding(horizontal = Tokens.Gutter, vertical = 4.dp).fillMaxWidth()
            .graphicsLayer { rotationY = rotation + tilt.x * 8f; rotationX = -tilt.y * 6f; cameraDistance = 14f * density.density }
            .pressable({ haptics.tick(); flipped = !flipped }, 0.98f),
    ) {
        if (rotation <= 90f) LyncCardFront(p, uni, Modifier.fillMaxWidth(), tilt = tilt, shine = 0.7f)
        else LyncCardBack(p, uni, Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f }, tilt = tilt, shine = 0.5f)
    }
}

@Composable
fun StatsRow(stats: ProfileStats, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = 6.dp)) {
        Stat(stats.posts, R.string.profile_posts, Modifier.weight(1f))
        Stat(stats.connections, R.string.profile_connections, Modifier.weight(1f))
        Stat(stats.followers, R.string.profile_followers, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(value: Int, label: Int, modifier: Modifier) {
    val c = Campus.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, color = c.text)
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
    }
}

/** One cell of the 3-column grid; every post kind gets a recognizable thumbnail. */
@Composable
fun PostTile(post: FeedPost, modifier: Modifier = Modifier) {
    val c = Campus.colors
    Box(modifier.aspectRatio(4f / 5f).background(c.surface), contentAlignment = Alignment.Center) {
        when (post.kind) {
            PostKind.PHOTO -> AsyncImage(post.imageUrl, post.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            PostKind.TEXT -> Box(Modifier.fillMaxSize().background(gradientOf(post.textStyle)).padding(10.dp), contentAlignment = Alignment.Center) {
                Text(post.caption.orEmpty(), color = Color.White, fontFamily = DisplayFamily, fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, maxLines = 5)
            }
            PostKind.POLL -> Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📊", fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text(post.poll?.question.orEmpty(), style = MaterialTheme.typography.labelMedium, color = c.text, textAlign = TextAlign.Center, maxLines = 3)
            }
            PostKind.PLAN -> Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(post.plan?.activity?.emoji ?: "✨", fontSize = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text(post.plan?.place.orEmpty(), style = MaterialTheme.typography.labelMedium, color = c.text, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
fun PostGridRow(row: List<FeedPost>) {
    Row(Modifier.padding(horizontal = 2.dp).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        row.forEach { PostTile(it, Modifier.weight(1f)) }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
fun statusLabel(p: StatusPreset): String = stringResource(
    when (p) {
        StatusPreset.COFFEE -> R.string.status_coffee
        StatusPreset.STUDY -> R.string.status_study
        StatusPreset.LUNCH -> R.string.status_lunch
        StatusPreset.BUSY -> R.string.status_busy
    }
)

@Composable
fun Tag(text: String, highlighted: Boolean = false) {
    val c = Campus.colors
    Text(text, style = MaterialTheme.typography.labelLarge, color = if (highlighted) c.accent else c.text,
        modifier = Modifier.clip(CircleShape).background(if (highlighted) c.accentSoft else c.surface).padding(horizontal = 14.dp, vertical = 8.dp))
}
