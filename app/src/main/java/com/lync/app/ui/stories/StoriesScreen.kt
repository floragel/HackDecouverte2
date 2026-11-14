package com.lync.app.ui.stories

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lync.app.R
import com.lync.app.data.model.StoryGroup
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.pressable
import com.lync.app.ui.components.timeAgo
import com.lync.app.ui.compose.gradientOf
import com.lync.app.ui.theme.DisplayFamily
import com.lync.app.ui.theme.Tokens

/** Full-screen stories: tap right = next, left = previous, swipe down = close. 5 s per story. */
@Composable
fun StoriesScreen(startProfileId: String) {
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    var groups by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var g by remember { mutableIntStateOf(0) }
    var i by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        groups = runCatching { repo.stories() }.getOrDefault(emptyList())
        g = groups.indexOfFirst { it.profile.id == startProfileId }.coerceAtLeast(0)
        if (groups.isEmpty()) nav.back()
    }
    BackHandler { nav.back() }

    val group = groups.getOrNull(g) ?: return
    val story = group.stories.getOrNull(i) ?: return

    fun next() {
        if (i < group.stories.lastIndex) i++
        else if (g < groups.lastIndex) { g++; i = 0 } else nav.back()
    }
    fun prev() {
        if (i > 0) i-- else if (g > 0) { g--; i = 0 }
    }

    LaunchedEffect(group.profile.id) { runCatching { repo.markStorySeen(group.profile.id) } }
    LaunchedEffect(g, i, paused) {
        if (paused) { progress.stop(); return@LaunchedEffect }
        if (progress.value >= 1f) progress.snapTo(0f)
        progress.animateTo(1f, tween(((1f - progress.value) * 5000).toInt(), easing = LinearEasing))
        progress.snapTo(0f)
        next()
    }

    Box(
        Modifier.fillMaxSize().background(Tokens.NightBg)
            .graphicsLayer { translationY = dragY.coerceAtLeast(0f); val s = 1f - (dragY / 3000f).coerceIn(0f, 0.1f); scaleX = s; scaleY = s }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { if (dragY > 260f) nav.back() else dragY = 0f }) { _, d -> dragY += d }
            }
            .pointerInput(g, i) {
                detectTapGestures(
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onTap = { o -> if (o.x < size.width * 0.33f) prev() else next() },
                )
            },
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 6.dp, bottom = 8.dp).clip(RoundedCornerShape(Tokens.RadiusMedia))) {
            if (story.imageUrl != null) {
                AsyncImage(story.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (story.text != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            } else {
                Box(Modifier.fillMaxSize().background(gradientOf(story.textStyle)))
            }
            story.text?.let {
                Text(
                    it, color = Color.White, fontFamily = DisplayFamily, fontSize = if (story.imageUrl == null) 34.sp else 24.sp,
                    lineHeight = if (story.imageUrl == null) 38.sp else 28.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(if (story.imageUrl == null) Alignment.Center else Alignment.BottomCenter).padding(32.dp),
                )
            }

            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.stories.indices.forEach { k ->
                        Box(Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                            val f = when { k < i -> 1f; k == i -> progress.value; else -> 0f }
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.pressable({ nav.back(); nav.user(group.profile.id) })) { Avatar(group.profile.avatarUrl, group.profile.initials, 34.dp) }
                    Spacer(Modifier.width(10.dp))
                    Text(group.profile.firstName, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(timeAgo(story.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clip(CircleShape).pressable({ nav.back() }), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White)
                    }
                }
            }
        }
    }
}
