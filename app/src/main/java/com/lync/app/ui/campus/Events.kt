package com.lync.app.ui.network

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.lync.app.R
import com.lync.app.data.model.CampusEvent
import com.lync.app.data.model.EventCategory
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.pressable
import com.lync.app.ui.shareText
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun eventCategoryLabel(c: EventCategory): String = stringResource(
    when (c) {
        EventCategory.SOCIAL -> R.string.evcat_social
        EventCategory.ACADEMIC -> R.string.evcat_academic
        EventCategory.SPORT -> R.string.evcat_sport
        EventCategory.CAREER -> R.string.evcat_career
        EventCategory.CULTURE -> R.string.evcat_culture
        EventCategory.TECH -> R.string.evcat_tech
    }
)

/** "Today, 5:00 PM" / "Tomorrow, 6:00 PM" / "Fri 20 Nov, 10:00 AM" */
@Composable
fun eventWhen(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val days = cal.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR) + 365 * (cal.get(Calendar.YEAR) - today.get(Calendar.YEAR))
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    return when (days) {
        0 -> stringResource(R.string.plan_today, time)
        1 -> stringResource(R.string.plan_tomorrow, time)
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(Date(millis)) + ", " + time
    }
}

@Composable
fun EventsTab() {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<CampusEvent>>(emptyList()) }
    var filter by remember { mutableStateOf<EventCategory?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { events = runCatching { repo.events() }.getOrDefault(events) } }

    val rsvp: (CampusEvent) -> Unit = { e ->
        val going = !e.imGoing
        events = events.map { if (it.id == e.id) it.copy(imGoing = going, goingCount = it.goingCount + if (going) 1 else -1) else it }
        scope.launch { runCatching { repo.setRsvp(e.id, going) } }
    }

    val shown = events.filter { filter == null || it.category == filter }
    val featured = shown.filter { it.featured }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.events_all), { filter = null }, style = if (filter == null) PillStyle.Filled else PillStyle.Tonal) }
                items(EventCategory.entries) { cat ->
                    Pill("${cat.emoji}  ${eventCategoryLabel(cat)}", { filter = if (filter == cat) null else cat }, style = if (filter == cat) PillStyle.Filled else PillStyle.Tonal)
                }
            }
        }
        if (featured.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featured, key = { "f" + it.id }) { e -> FeaturedEvent(e, { nav.event(e.id) }, { rsvp(e) }) }
                }
            }
        }
        item { Text(stringResource(R.string.events_upcoming), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(start = Tokens.Gutter, top = 18.dp, bottom = 6.dp)) }
        if (shown.isEmpty()) item {
            Text(stringResource(R.string.events_empty), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(Tokens.Gutter))
        }
        items(shown, key = { it.id }) { e -> EventRow(e, { nav.event(e.id) }, { rsvp(e) }) }
    }
}

@Composable
private fun FeaturedEvent(e: CampusEvent, onOpen: () -> Unit, onRsvp: () -> Unit) {
    Box(Modifier.width(300.dp).aspectRatio(1.35f).clip(RoundedCornerShape(Tokens.RadiusMedia)).pressable(onOpen, 0.98f)) {
        AsyncImage(e.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Text(stringResource(R.string.events_featured), style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.padding(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 4.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(eventWhen(e.startsAtMillis), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            Text(e.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.events_going, e.goingCount, e.goingCount), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                RsvpButton(e.imGoing, onRsvp, light = true)
            }
        }
    }
}

@Composable
private fun EventRow(e: CampusEvent, onOpen: () -> Unit, onRsvp: () -> Unit) {
    val c = Campus.colors
    val cal = Calendar.getInstance().apply { timeInMillis = e.startsAtMillis }
    Row(Modifier.fillMaxWidth().pressable(onOpen, 0.99f).padding(horizontal = Tokens.Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // date tile
        Column(Modifier.size(58.dp).clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(Date(e.startsAtMillis)).replace(".", ""), style = MaterialTheme.typography.labelMedium, color = c.accent)
            Text("${cal.get(Calendar.DAY_OF_MONTH)}", style = MaterialTheme.typography.headlineSmall, color = c.text)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(eventWhen(e.startsAtMillis), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            Text(e.place, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.friendsGoing.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FriendsGoing(e)
            }
        }
        Spacer(Modifier.width(8.dp))
        RsvpButton(e.imGoing, onRsvp)
    }
}

@Composable
private fun FriendsGoing(e: CampusEvent) {
    val c = Campus.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            e.friendsGoing.take(3).forEach { p -> Box(Modifier.clip(CircleShape).background(c.bg).padding(1.5.dp)) { Avatar(p.avatarUrl, p.initials, 20.dp) } }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (e.friendsGoing.size == 1) stringResource(R.string.events_friend_going, e.friendsGoing[0].firstName)
            else pluralStringResource(R.plurals.events_friends_going, e.friendsGoing.size - 1, e.friendsGoing[0].firstName, e.friendsGoing.size - 1),
            style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1,
        )
    }
}

@Composable
private fun RsvpButton(going: Boolean, onClick: () -> Unit, light: Boolean = false) {
    val c = Campus.colors
    AnimatedContent(going, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "rsvp") { g ->
        Row(
            Modifier.clip(CircleShape)
                .background(if (g) (if (light) Color.White.copy(alpha = 0.2f) else c.bg) else (if (light) Color.White else c.accent))
                .border(1.dp, if (g && !light) c.border else Color.Transparent, CircleShape)
                .pressable(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (g) { Icon(Icons.Rounded.Check, null, tint = if (light) Color.White else c.text, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)) }
            Text(stringResource(if (g) R.string.events_going_me else R.string.events_rsvp), style = MaterialTheme.typography.labelLarge,
                color = when { g && light -> Color.White; g -> c.text; light -> Tokens.Night; else -> c.onAccent })
        }
    }
}

@Composable
fun EventDetailScreen(id: String) {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var event by remember { mutableStateOf<CampusEvent?>(null) }
    LaunchedEffect(id) { event = runCatching { repo.event(id) }.getOrNull() }
    val e = event
    val shareTpl = stringResource(R.string.events_share_text)

    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (e != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.1f)) {
                AsyncImage(e.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.bg)))
            }
            Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                Text("${e.category.emoji}  ${eventCategoryLabel(e.category)}", style = MaterialTheme.typography.labelLarge, color = c.accent)
                Spacer(Modifier.height(6.dp))
                Text(e.title, style = MaterialTheme.typography.displaySmall, color = c.text)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.clip(CircleShape).pressable({}), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(e.organizer.avatarUrl, e.organizer.name.take(2), 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(e.organizer.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                    if (e.organizer.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Verified, null, tint = c.accent, modifier = Modifier.size(14.dp)) }
                }
                Spacer(Modifier.height(18.dp))
                InfoRow(Icons.Rounded.Schedule, eventWhen(e.startsAtMillis), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.endsAtMillis)).let { stringResource(R.string.events_until, it) })
                InfoRow(Icons.Rounded.Place, e.place, null)
                Spacer(Modifier.height(12.dp))
                Text(e.description, style = MaterialTheme.typography.bodyLarge, color = c.text)
                Spacer(Modifier.height(18.dp))
                Text(pluralStringResource(R.plurals.events_going, e.goingCount, e.goingCount), style = MaterialTheme.typography.titleSmall, color = c.textMuted)
                if (e.friendsGoing.isNotEmpty()) { Spacer(Modifier.height(8.dp)); FriendsGoing(e) }
                Spacer(Modifier.height(120.dp))
            }
        }
        Row(Modifier.statusBarsPadding().padding(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
        }
        if (e != null) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = Tokens.Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.Outlined.CalendarMonth, stringResource(R.string.events_add_calendar), {
                context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, e.title).putExtra(CalendarContract.Events.EVENT_LOCATION, e.place)
                    .putExtra(CalendarContract.Events.DESCRIPTION, e.description)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startsAtMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endsAtMillis))
            })
            CircleIconButton(Icons.Outlined.IosShare, stringResource(R.string.a11y_share), { shareText(context, shareTpl.format(e.title, e.place, "https://lync.app/e/${e.id}")) })
            Pill(stringResource(if (e.imGoing) R.string.events_going_me else R.string.events_rsvp), {
                val going = !e.imGoing
                event = e.copy(imGoing = going, goingCount = e.goingCount + if (going) 1 else -1)
                scope.launch { runCatching { repo.setRsvp(e.id, going) } }
            }, Modifier.weight(1f), style = if (e.imGoing) PillStyle.Outline else PillStyle.Filled, contentPadding = PaddingValues(vertical = 14.dp))
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?) {
    val c = Campus.colors
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
    }
}
