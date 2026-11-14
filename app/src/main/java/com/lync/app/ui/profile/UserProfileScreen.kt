package com.lync.app.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lync.app.R
import com.lync.app.data.model.CardPayload
import com.lync.app.data.model.ConnectionState
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.Person
import com.lync.app.data.model.ProfileStats
import com.lync.app.data.model.University
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.shareText
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Someone else's profile: their card, connect + follow, their posts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserProfileScreen(id: String) {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = com.lync.app.ui.motion.rememberHaptics()

    var person by remember { mutableStateOf<Person?>(null) }
    var uni by remember { mutableStateOf<University?>(null) }
    var stats by remember { mutableStateOf(ProfileStats(0, 0, 0)) }
    var posts by remember { mutableStateOf<List<FeedPost>>(emptyList()) }

    LaunchedEffect(id) {
        if (id == repo.me.value?.id) { nav.back(); return@LaunchedEffect }
        person = runCatching { repo.profile(id) }.getOrNull()
        person?.let { p ->
            uni = runCatching { repo.university(p.profile.universityId) }.getOrNull()
            stats = runCatching { repo.stats(id) }.getOrDefault(stats)
            posts = runCatching { repo.posts(id) }.getOrDefault(emptyList())
        }
    }

    val shareTpl = stringResource(R.string.share_card_text)
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
            Text(person?.profile?.username?.let { "@$it" } ?: "", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
            person?.let { p -> CircleIconButton(Icons.Outlined.IosShare, stringResource(R.string.a11y_share), { shareText(context, shareTpl.format(p.profile.fullName, CardPayload.encode(p.profile.id))) }) }
        }
        val p = person ?: return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { FlippableCard(p.profile, uni) }
            p.profile.bio?.let { bio -> item { Text(bio, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = 10.dp)) } }
            item { StatsRow(stats) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedContent(p.connection, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "c", modifier = Modifier.weight(1f)) { state ->
                        val (label, style) = when (state) {
                            ConnectionState.NONE -> R.string.net_connect to PillStyle.Filled
                            ConnectionState.INCOMING -> R.string.net_accept to PillStyle.Filled
                            ConnectionState.OUTGOING -> R.string.net_pending to PillStyle.Tonal
                            ConnectionState.CONNECTED -> R.string.net_connected to PillStyle.Outline
                        }
                        Pill(stringResource(label), {
                            if (state == ConnectionState.NONE || state == ConnectionState.INCOMING) haptics.confirm()
                            when (state) {
                                ConnectionState.NONE -> { person = p.copy(connection = ConnectionState.OUTGOING); scope.launch { runCatching { repo.connect(p.profile.id) } } }
                                ConnectionState.INCOMING -> { person = p.copy(connection = ConnectionState.CONNECTED); scope.launch { runCatching { repo.respond(p.profile.id, true) } } }
                                else -> Unit
                            }
                        }, Modifier.fillMaxWidth(), style = style, contentPadding = PaddingValues(vertical = 12.dp))
                    }
                    Pill(stringResource(if (p.following) R.string.net_following else R.string.net_follow), {
                        haptics.tick()
                        val follow = !p.following
                        person = p.copy(following = follow)
                        stats = stats.copy(followers = stats.followers + if (follow) 1 else -1)
                        scope.launch { runCatching { repo.setFollowing(p.profile.id, follow) } }
                    }, Modifier.weight(1f), style = if (p.following) PillStyle.Outline else PillStyle.Tonal, contentPadding = PaddingValues(vertical = 12.dp))
                }
            }
            if (p.sharedCourses > 0 || p.profile.interests.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = 8.dp)) {
                    if (p.sharedCourses > 0) {
                        Text(stringResource(R.string.net_shared_courses, p.sharedCourses), style = MaterialTheme.typography.titleSmall, color = c.accent)
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.profile.interests.forEach { Tag(it) }
                    }
                }
            }
            item { Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(0.5.dp).background(c.border)) }
            items(posts.chunked(3), key = { "r" + it.first().id }) { PostGridRow(it) }
        }
    }
}
