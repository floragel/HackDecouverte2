package com.lync.app.ui.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lync.app.R
import com.lync.app.data.CampusRepository
import com.lync.app.data.model.CardPayload
import com.lync.app.data.model.Course
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.ProfileStats
import com.lync.app.data.model.StatusPreset
import com.lync.app.data.model.University
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.pressable
import com.lync.app.ui.shareText
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileState(
    val stats: ProfileStats = ProfileStats(0, 0, 0),
    val courses: List<Course> = emptyList(),
    val posts: List<FeedPost> = emptyList(),
    val university: University? = null,
)

class ProfileViewModel(private val repo: CampusRepository) : ViewModel() {
    val me = repo.me
    private val _state = MutableStateFlow(ProfileState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val m = repo.me.value ?: return@launch
        val stats = runCatching { repo.stats() }.getOrDefault(ProfileStats(0, 0, 0))
        val courses = runCatching { repo.myCourses() }.getOrDefault(emptyList())
        val posts = runCatching { repo.posts(m.id) }.getOrDefault(emptyList())
        val uni = runCatching { repo.university(m.universityId) }.getOrNull()
        _state.update { ProfileState(stats, courses, posts, uni) }
    }

    fun setStatus(key: String?) = viewModelScope.launch { runCatching { repo.updateStatus(key) } }
}

private enum class ProfileTab { Posts, About }

@Composable
fun ProfileScreen() {
    val vm = campusViewModel { ProfileViewModel(it.repository) }
    val me by vm.me.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Campus.colors
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val p = me ?: return
    var tab by rememberSaveable { mutableStateOf(ProfileTab.Posts) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }

    val shareTpl = stringResource(R.string.share_card_text)
    val share: () -> Unit = { shareText(context, shareTpl.format(p.fullName, CardPayload.encode(p.id))) }

    LazyColumn(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                Text("@${p.username}", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                CircleIconButton(Icons.Outlined.IosShare, stringResource(R.string.profile_share_card), share)
                Spacer(Modifier.width(8.dp))
                CircleIconButton(Icons.Outlined.Settings, stringResource(R.string.profile_settings), { nav.settings() })
            }
        }
        item(key = "card") { FlippableCard(p, s.university) }
        item(key = "hint") { RotateHint() }
        p.bio?.let { bio ->
            item(key = "bio") { Text(bio, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = 4.dp)) }
        }
        item(key = "stats") { StatsRow(s.stats) }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.profile_edit), { nav.editProfile() }, Modifier.weight(1f), style = PillStyle.Tonal, contentPadding = PaddingValues(vertical = 12.dp))
                Pill(stringResource(R.string.profile_share_card), share, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp))
            }
        }
        item(key = "status") {
            LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatusPreset.entries) { preset ->
                    val on = p.statusKey == preset.key
                    Pill("${preset.emoji}  ${statusLabel(preset)}", { vm.setStatus(if (on) null else preset.key) },
                        style = if (on) PillStyle.Filled else PillStyle.Outline)
                }
            }
        }
        stickyTabs(tab) { tab = it }
        when (tab) {
            ProfileTab.Posts -> if (s.posts.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.profile_no_posts), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.feed_new_post), { nav.compose() }, style = PillStyle.Tonal)
                }
            } else items(s.posts.chunked(3), key = { "row" + it.first().id }) { PostGridRow(it) }
            ProfileTab.About -> item(key = "about") { AboutSection(s.courses, p.interests) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.stickyTabs(tab: ProfileTab, onTab: (ProfileTab) -> Unit) {
    stickyHeader(key = "tabs") {
        val c = Campus.colors
        Column(Modifier.fillMaxWidth().background(c.bg).padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter)) {
                ProfileTab.entries.forEach { t ->
                    val on = t == tab
                    val color by animateColorAsState(if (on) c.text else c.textMuted, label = "tab")
                    val bar by animateDpAsState(if (on) 2.dp else 0.dp, label = "bar")
                    Column(Modifier.weight(1f).pressable({ onTab(t) }, 0.97f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (t == ProfileTab.Posts) R.string.profile_posts else R.string.profile_about),
                            style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth(0.4f).height(bar).clip(CircleShape).background(c.text))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.border))
        }
    }
}

@Composable
private fun RotateHint() {
    val c = Campus.colors
    val t = rememberInfiniteTransition(label = "rot")
    val angle by t.animateFloat(0f, -90f, infiniteRepeatable(tween(1200, delayMillis = 600), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ScreenRotation, null, tint = c.textMuted, modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = angle })
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.profile_card_hint), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutSection(courses: List<Course>, interests: List<String>) {
    val c = Campus.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter)) {
        if (courses.isNotEmpty()) {
            Text(stringResource(R.string.profile_courses), style = MaterialTheme.typography.titleSmall, color = c.textMuted, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { courses.forEach { Tag(it.code) } }
        }
        if (interests.isNotEmpty()) {
            Text(stringResource(R.string.profile_interests), style = MaterialTheme.typography.titleSmall, color = c.textMuted, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { interests.forEach { Tag(it) } }
        }
    }
}
