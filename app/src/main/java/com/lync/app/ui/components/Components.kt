package com.lync.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.lync.app.R
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens

/** Press feedback used everywhere: a subtle spring scale. This is most of the "fluid" feel. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, scaleDown: Float = 0.96f): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleDown else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
fun Avatar(
    url: String?,
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
) {
    val c = Campus.colors
    val base = modifier
        .size(size)
        .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape).padding(3.dp) else Modifier)
        .clip(CircleShape)
        .background(c.surfaceAlt)
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text(initials, color = c.accent, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
        }
    }
    if (url.isNullOrBlank()) {
        Box(base, contentAlignment = Alignment.Center) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = base,
            loading = { fallback() }, error = { fallback() },
        )
    }
}

enum class PillStyle { Filled, Tonal, Outline }

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Filled,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
) {
    val c = Campus.colors
    val (bg, fg) = when (style) {
        PillStyle.Filled -> c.accent to c.onAccent
        PillStyle.Tonal -> c.surfaceAlt to c.text
        PillStyle.Outline -> Color.Transparent to c.text
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, c.border, CircleShape) else Modifier)
            .pressable(onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val c = Campus.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(Tokens.RadiusControl))
            .background(if (enabled) c.accent else c.surfaceAlt)
            .then(if (enabled && !loading) Modifier.pressable(onClick, 0.98f) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "…" else text,
            color = if (enabled) c.onAccent else c.textMuted,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Campus.colors.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * Large left-aligned page title, same as SwiftUI's `.navigationBarTitleDisplayMode(.large)`.
 * Every tab starts with one: no app bar chrome.
 */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(horizontal = Tokens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.displaySmall, color = Campus.colors.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 40pt round icon button on a soft surface. */
@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val c = Campus.colors
    Box(
        modifier.size(40.dp).clip(CircleShape).background(if (filled) c.text else c.surface).pressable(onClick, 0.9f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = if (filled) c.bg else c.text, modifier = Modifier.size(20.dp)) }
}

@Composable
fun LyncMark(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_lync_mark), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Logo mark + "Lync" set in the display face. */
@Composable
fun Wordmark(color: Color = Campus.colors.text, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LyncMark(26.dp, color)
        Spacer(Modifier.size(6.dp))
        Text("Lync", style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    val c = Campus.colors
    Text(
        stringResource(R.string.demo_mode),
        style = MaterialTheme.typography.labelSmall,
        color = c.textMuted,
        modifier = modifier.clip(CircleShape).background(c.surface).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** "3m", "2h", "4d" — compact like Instagram. */
fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - millis) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        s < 604800 -> "${s / 86400}d"
        else -> "${s / 604800}w"
    }
}
