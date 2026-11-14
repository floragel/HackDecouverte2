package com.lync.app.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lync.app.R
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.InterestGroup
import com.lync.app.data.model.PostDraft
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.pressable
import com.lync.app.ui.compose.Field
import com.lync.app.ui.feed.CommentsSheet
import com.lync.app.ui.feed.PostCard
import com.lync.app.ui.shareText
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import com.lync.app.ui.motion.sharedElement
import kotlinx.coroutines.launch

@Composable
fun GroupsTab() {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<InterestGroup>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { groups = runCatching { repo.groups() }.getOrDefault(groups) } }

    val haptics = com.lync.app.ui.motion.rememberHaptics()
    val toggle: (InterestGroup) -> Unit = { g ->
        haptics.confirm()
        val join = !g.isMember
        groups = groups.map { if (it.id == g.id) it.copy(isMember = join, memberCount = it.memberCount + if (join) 1 else -1) else it }
        scope.launch { runCatching { repo.setMember(g.id, join) } }
    }
    val mine = groups.filter { it.isMember }
    val discover = groups.filterNot { it.isMember }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (mine.isNotEmpty()) {
            item { Header(stringResource(R.string.groups_yours)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mine, key = { "m" + it.id }) { g ->
                        Column(Modifier.width(118.dp).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface).pressable({ nav.group(g.id) }, 0.97f).padding(14.dp)) {
                            Text(g.emoji, fontSize = 30.sp, modifier = Modifier.sharedElement("group-${g.id}"))
                            Spacer(Modifier.height(10.dp))
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.groups_members, g.memberCount, g.memberCount), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.groups_discover), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                Pill(stringResource(R.string.groups_create), { creating = true }, style = PillStyle.Tonal, icon = Icons.Rounded.Add)
            }
        }
        items(discover, key = { it.id }) { g ->
            Row(Modifier.fillMaxWidth().pressable({ nav.group(g.id) }, 0.99f).padding(horizontal = Tokens.Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).sharedElement("group-${g.id}").clip(RoundedCornerShape(16.dp)).background(c.surface), contentAlignment = Alignment.Center) { Text(g.emoji, fontSize = 26.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(g.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.groups_members, g.memberCount, g.memberCount), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                }
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.groups_join), { toggle(g) })
            }
        }
    }

    if (creating) CreateGroupSheet(onDismiss = { creating = false }) { name, emoji, desc ->
        scope.launch {
            repo.createGroup(name, emoji, desc).onSuccess { g -> creating = false; groups = listOf(g) + groups; nav.group(g.id) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Campus.colors.text, modifier = Modifier.padding(start = Tokens.Gutter, top = 14.dp, bottom = 10.dp))
}

private val GroupEmojis = listOf("✨", "🧗", "🏀", "⚽", "🎮", "📚", "🎨", "🎸", "📷", "🍳", "🌱", "🤖", "♟️", "🏎️", "💃", "🎬")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    val c = Campus.colors
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(GroupEmojis.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.padding(horizontal = Tokens.Gutter).navigationBarsPadding().imePadding()) {
            Text(stringResource(R.string.groups_create_title), style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GroupEmojis) { e ->
                    Box(Modifier.size(46.dp).clip(CircleShape).background(if (e == emoji) c.accentSoft else c.surface).pressable({ emoji = e }), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 22.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Field(name, { name = it.take(40) }, stringResource(R.string.groups_name_hint))
            Spacer(Modifier.height(10.dp))
            Field(desc, { desc = it.take(140) }, stringResource(R.string.groups_desc_hint), minLines = 2)
            Spacer(Modifier.height(16.dp))
            Pill(stringResource(R.string.groups_create), { if (name.isNotBlank()) onCreate(name, emoji, desc) }, Modifier.fillMaxWidth(),
                style = if (name.isNotBlank()) PillStyle.Filled else PillStyle.Tonal, contentPadding = PaddingValues(vertical = 14.dp))
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun GroupScreen(id: String) {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf<InterestGroup?>(null) }
    var posts by remember { mutableStateOf<List<FeedPost>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var commentsFor by remember { mutableStateOf<FeedPost?>(null) }

    suspend fun reload() {
        group = runCatching { repo.group(id) }.getOrNull()
        posts = runCatching { repo.groupPosts(id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(id) { reload() }
    fun patch(pid: String, f: (FeedPost) -> FeedPost) { posts = posts.map { if (it.id == pid) f(it) else it } }
    val g = group
    val shareTpl = stringResource(R.string.groups_share_text)

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
        }
        if (g == null) return@Column
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                    Box(Modifier.size(76.dp).sharedElement("group-${g.id}").clip(RoundedCornerShape(24.dp)).background(c.surface), contentAlignment = Alignment.Center) { Text(g.emoji, fontSize = 38.sp) }
                    Spacer(Modifier.height(14.dp))
                    Text(g.name, style = MaterialTheme.typography.displaySmall, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text(g.description, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                            g.members.take(5).forEach { p -> Box(Modifier.clip(CircleShape).background(c.bg).padding(2.dp).pressable({ nav.user(p.id) })) { Avatar(p.avatarUrl, p.initials, 30.dp) } }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(pluralStringResource(R.plurals.groups_members, g.memberCount, g.memberCount), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(if (g.isMember) R.string.groups_joined else R.string.groups_join), {
                            val join = !g.isMember
                            group = g.copy(isMember = join, memberCount = g.memberCount + if (join) 1 else -1)
                            scope.launch { runCatching { repo.setMember(g.id, join) } }
                        }, Modifier.weight(1f), style = if (g.isMember) PillStyle.Outline else PillStyle.Filled, contentPadding = PaddingValues(vertical = 12.dp))
                        Pill(stringResource(R.string.a11y_share), { shareText(context, shareTpl.format(g.name, "https://lync.app/g/${g.id}")) },
                            Modifier.weight(1f), style = PillStyle.Tonal, contentPadding = PaddingValues(vertical = 12.dp))
                    }
                }
            }
            if (g.isMember) item {
                Row(Modifier.padding(horizontal = Tokens.Gutter, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(draft, { draft = it.take(220) }, stringResource(R.string.groups_post_hint)) }
                    Spacer(Modifier.width(8.dp))
                    Pill(stringResource(R.string.feed_publish), {
                        val t = draft.trim()
                        if (t.isNotEmpty()) scope.launch { repo.createPost(PostDraft.Text(t, 0), groupId = g.id).onSuccess { draft = ""; reload() } }
                    }, style = if (draft.isNotBlank()) PillStyle.Filled else PillStyle.Tonal)
                }
            }
            item { Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.border)) }
            if (posts.isEmpty()) item {
                Text(stringResource(R.string.groups_empty), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(Tokens.Gutter))
            }
            items(posts, key = { it.id }) { post ->
                PostCard(
                    post,
                    onAuthor = { nav.user(post.authorId) },
                    onLike = { val l = !post.likedByMe; patch(post.id) { it.copy(likedByMe = l, likeCount = it.likeCount + if (l) 1 else -1) }; scope.launch { runCatching { repo.setLiked(post.id, l) } } },
                    onDoubleTap = { if (!post.likedByMe) { patch(post.id) { it.copy(likedByMe = true, likeCount = it.likeCount + 1) }; scope.launch { runCatching { repo.setLiked(post.id, true) } } } },
                    onComments = { commentsFor = post },
                    onShare = { shareText(context, "https://lync.app/p/${post.id}") },
                    onVote = { opt -> scope.launch { runCatching { repo.vote(post.id, opt) }; reload() } },
                    onGoing = { val plan = post.plan; if (plan != null) scope.launch { runCatching { repo.setGoing(post.id, !plan.imGoing) }; reload() } },
                    onPerson = { nav.user(it) },
                )
            }
        }
    }
    commentsFor?.let { post ->
        CommentsSheet(post, load = { runCatching { repo.comments(post.id) }.getOrDefault(emptyList()) },
            add = { t -> repo.addComment(post.id, t).getOrNull() }, onDismiss = { commentsFor = null }, onPerson = { commentsFor = null; nav.user(it) })
    }
}
