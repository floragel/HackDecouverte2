package com.lync.app.ui.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lync.app.R
import com.lync.app.data.model.PlanActivity
import com.lync.app.data.model.PostDraft
import com.lync.app.data.model.TextStyles
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.DisplayFamily
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private enum class Kind(val label: Int, val icon: ImageVector) {
    Photo(R.string.compose_photo, Icons.Outlined.Image),
    Text(R.string.compose_text, Icons.Outlined.TextFields),
    Poll(R.string.compose_poll, Icons.Outlined.BarChart),
    Plan(R.string.compose_plan, Icons.Outlined.Event),
}

fun gradientOf(style: Int) = TextStyles[style.mod(TextStyles.size)].let { Brush.linearGradient(listOf(Color(it.first), Color(it.second))) }

@Composable
fun planActivityLabel(a: PlanActivity): String = stringResource(
    when (a) {
        PlanActivity.GYM -> R.string.plan_gym
        PlanActivity.LIBRARY -> R.string.plan_library
        PlanActivity.COFFEE -> R.string.plan_coffee
        PlanActivity.LUNCH -> R.string.plan_lunch
        PlanActivity.STUDY -> R.string.plan_study
        PlanActivity.SPORT -> R.string.plan_sport
        PlanActivity.PARTY -> R.string.plan_party
        PlanActivity.OTHER -> R.string.plan_other
    }
)

private val PlaceSuggestions = listOf("Webster Library", "Le Gym, EV", "Hall cafeteria", "JMSB atrium", "Loyola PERFORM", "Grey Nuns reading room")

/**
 * One screen for every kind of post. [storyMode] = 24h story (photo or text only).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(storyMode: Boolean) {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(if (storyMode) Kind.Text else Kind.Photo) }
    var publishing by remember { mutableStateOf(false) }

    // drafts per kind (kept when switching tabs)
    var image by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var style by remember { mutableIntStateOf(2) }
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var hours by remember { mutableIntStateOf(24) }
    var activity by remember { mutableStateOf(PlanActivity.GYM) }
    var whenIndex by remember { mutableIntStateOf(1) }
    var place by remember { mutableStateOf("") }
    var spots by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) image = it }

    val valid = when (kind) {
        Kind.Photo -> image != null
        Kind.Text -> text.isNotBlank()
        Kind.Poll -> question.isNotBlank() && options.count { it.isNotBlank() } >= 2
        Kind.Plan -> place.isNotBlank()
    }

    fun startTime(): Long {
        val cal = Calendar.getInstance()
        when (whenIndex) {
            0 -> Unit
            1 -> cal.add(Calendar.MINUTE, 30)
            2 -> cal.add(Calendar.HOUR_OF_DAY, 1)
            3 -> { cal.set(Calendar.HOUR_OF_DAY, 19); cal.set(Calendar.MINUTE, 0) }
            else -> { cal.add(Calendar.DAY_OF_YEAR, 1); cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0) }
        }
        return cal.timeInMillis
    }

    val publish: () -> Unit = {
        publishing = true
        scope.launch {
            val bytes = image?.let { uri -> withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }
            val result = if (storyMode) {
                repo.addStory(text.takeIf { kind == Kind.Text || it.isNotBlank() }?.ifBlank { null }, if (kind == Kind.Photo) bytes else null, style)
            } else when (kind) {
                Kind.Photo -> repo.createPost(PostDraft.Photo(bytes ?: ByteArray(0), caption))
                Kind.Text -> repo.createPost(PostDraft.Text(text.trim(), style))
                Kind.Poll -> repo.createPost(PostDraft.PollDraft(question.trim(), options.filter { it.isNotBlank() }.map { it.trim() }, hours))
                Kind.Plan -> repo.createPost(PostDraft.PlanDraft(activity, place.trim(), startTime(), spots, note.trim()))
            }
            publishing = false
            if (result.isSuccess) nav.back()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).pressable({ nav.back() }), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = c.text)
            }
            Text(stringResource(if (storyMode) R.string.compose_story_title else R.string.feed_new_post), style = MaterialTheme.typography.titleLarge,
                color = c.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Pill(stringResource(if (storyMode) R.string.compose_share_story else R.string.feed_publish), { if (valid && !publishing) publish() },
                style = if (valid) PillStyle.Filled else PillStyle.Tonal)
        }

        // kind switcher
        LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(if (storyMode) listOf(Kind.Text, Kind.Photo) else Kind.entries) { k ->
                val on = k == kind
                val bg by animateColorAsState(if (on) c.text else c.surface, label = "k")
                val fg by animateColorAsState(if (on) c.bg else c.text, label = "kf")
                Row(Modifier.clip(CircleShape).background(bg).pressable({ kind = k }).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(k.icon, null, tint = fg, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(k.label), style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        AnimatedContent(kind, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "kind", modifier = Modifier.weight(1f)) { k ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter)) {
                when (k) {
                    Kind.Photo -> {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(if (storyMode) 9f / 14f else 4f / 5f).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface)
                                .pressable({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, 0.98f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (image == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.AddPhotoAlternate, null, tint = c.textMuted, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.compose_pick_photo), style = MaterialTheme.typography.titleSmall, color = c.textMuted)
                            } else AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(14.dp))
                        if (!storyMode) Field(caption, { caption = it }, stringResource(R.string.feed_caption_hint), minLines = 2)
                    }
                    Kind.Text -> {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(if (storyMode) 9f / 14f else 1f).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(gradientOf(style)).padding(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (text.isEmpty()) Text(stringResource(R.string.compose_text_hint), color = Color.White.copy(alpha = 0.6f), fontFamily = DisplayFamily, fontSize = 28.sp, textAlign = TextAlign.Center)
                            BasicTextField(
                                text, { if (it.length <= 220) text = it },
                                textStyle = TextStyle(color = Color.White, fontFamily = DisplayFamily, fontSize = 28.sp, lineHeight = 32.sp, textAlign = TextAlign.Center),
                                cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), ) {
                            TextStyles.indices.forEach { i ->
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(gradientOf(i))
                                        .border(if (i == style) 3.dp else 0.dp, c.bg, CircleShape)
                                        .border(if (i == style) 5.dp else 0.dp, c.text, CircleShape)
                                        .pressable({ style = i }),
                                )
                            }
                        }
                    }
                    Kind.Poll -> {
                        BigField(question, { question = it }, stringResource(R.string.compose_poll_question))
                        Spacer(Modifier.height(16.dp))
                        options.forEachIndexed { i, opt ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                Box(Modifier.weight(1f)) { Field(opt, { options[i] = it.take(60) }, stringResource(R.string.compose_poll_option, i + 1)) }
                                if (options.size > 2) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.size(36.dp).clip(CircleShape).background(c.surface).pressable({ options.removeAt(i) }), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Remove, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if (options.size < 4) {
                            Row(Modifier.clip(CircleShape).pressable({ options.add("") }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.compose_poll_add), style = MaterialTheme.typography.labelLarge, color = c.accent)
                            }
                        }
                        Label(stringResource(R.string.compose_poll_duration))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "1h", 6 to "6h", 24 to "24h", 72 to "3d").forEach { (h, l) ->
                                Pill(l, { hours = h }, style = if (hours == h) PillStyle.Filled else PillStyle.Tonal)
                            }
                        }
                    }
                    Kind.Plan -> {
                        Label(stringResource(R.string.compose_plan_what), top = 0.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
                            PlanActivity.entries.forEach { a ->
                                val on = a == activity
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(Tokens.RadiusControl))
                                        .background(if (on) c.accentSoft else c.surface)
                                        .border(1.5.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(Tokens.RadiusControl))
                                        .pressable({ activity = a }).padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(a.emoji, fontSize = 24.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(planActivityLabel(a), style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
                                }
                            }
                        }
                        Label(stringResource(R.string.compose_plan_when))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(R.string.when_now, R.string.when_30, R.string.when_1h, R.string.when_tonight, R.string.when_tomorrow).forEachIndexed { i, l ->
                                Pill(stringResource(l), { whenIndex = i }, style = if (whenIndex == i) PillStyle.Filled else PillStyle.Tonal)
                            }
                        }
                        Label(stringResource(R.string.compose_plan_where))
                        Field(place, { place = it.take(60) }, stringResource(R.string.compose_plan_place_hint), leading = Icons.Rounded.Place)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlaceSuggestions.forEach { s -> Pill(s, { place = s }, style = if (place == s) PillStyle.Filled else PillStyle.Outline, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) }
                        }
                        Label(stringResource(R.string.compose_plan_spots))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Stepper(Icons.Rounded.Remove) { spots = spots?.let { if (it <= 2) null else it - 1 } }
                            Text(spots?.toString() ?: stringResource(R.string.compose_plan_no_limit), style = MaterialTheme.typography.titleMedium, color = c.text,
                                textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
                            Stepper(Icons.Rounded.Add) { spots = (spots ?: 1).plus(1).coerceAtMost(30) }
                        }
                        Label(stringResource(R.string.compose_plan_note))
                        Field(note, { note = it.take(140) }, stringResource(R.string.compose_plan_note_hint), minLines = 2)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Stepper(icon: ImageVector, onClick: () -> Unit) {
    val c = Campus.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).pressable(onClick, 0.9f), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Label(text: String, top: androidx.compose.ui.unit.Dp = 22.dp) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Campus.colors.textMuted, modifier = Modifier.padding(top = top, bottom = 10.dp))
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, minLines: Int = 1, leading: ImageVector? = null) {
    val c = Campus.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = if (minLines > 1) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (leading != null) { Icon(leading, null, tint = c.textMuted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                minLines = minLines, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BigField(value: String, onChange: (String) -> Unit, hint: String) {
    val c = Campus.colors
    Box {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.headlineSmall, color = c.textMuted)
        BasicTextField(value, { onChange(it.take(120)) }, textStyle = MaterialTheme.typography.headlineSmall.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}
