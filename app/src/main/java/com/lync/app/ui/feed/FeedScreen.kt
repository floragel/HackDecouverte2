package com.lync.app.ui.feed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.lync.app.R
import com.lync.app.data.CampusRepository
import com.lync.app.data.model.Comment
import com.lync.app.data.model.FeedPost
import com.lync.app.data.model.Plan
import com.lync.app.data.model.Poll
import com.lync.app.data.model.PostKind
import com.lync.app.data.model.StatusPreset
import com.lync.app.data.model.StoryGroup
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.DemoBadge
import com.lync.app.ui.components.Wordmark
import com.lync.app.ui.components.pressable
import com.lync.app.ui.components.timeAgo
import com.lync.app.ui.compose.gradientOf
import com.lync.app.ui.compose.planActivityLabel
import com.lync.app.ui.shareText
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.DisplayFamily
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class FeedState(
    val posts: List<FeedPost> = emptyList(),
    val stories: List<StoryGroup> = emptyList(),
    val refreshing: Boolean = false,
    val firstLoad: Boolean = true,
)

class FeedViewModel(private val repo: CampusRepository) : ViewModel() {
    private val _state = MutableStateFlow(FeedState())
    val state = _state.asStateFlow()
    val isDemo = repo.isDemo
    val me = repo.me

    init { refresh() }

    fun refresh(pull: Boolean = false) {
        _state.update { it.copy(refreshing = pull && !it.firstLoad) }
        viewModelScope.launch {
            val posts = runCatching { repo.feed() }.getOrDefault(_state.value.posts)
            val stories = runCatching { repo.stories() }.getOrDefault(_state.value.stories)
            _state.update { FeedState(posts, stories, refreshing = false, firstLoad = false) }
        }
    }

    private fun patch(id: String, f: (FeedPost) -> FeedPost) = _state.update { s -> s.copy(posts = s.posts.map { if (it.id == id) f(it) else it }) }

    /** Optimistic: UI updates instantly, server catches up. */
    fun toggleLike(post: FeedPost, forceLike: Boolean = false) {
        val liked = if (forceLike) true else !post.likedByMe
        if (liked == post.likedByMe) return
        patch(post.id) { it.copy(likedByMe = liked, likeCount = it.likeCount + if (liked) 1 else -1) }
        viewModelScope.launch { runCatching { repo.setLiked(post.id, liked) } }
    }

    fun vote(post: FeedPost, optionId: String) {
        val poll = post.poll ?: return
        if (poll.myVote != null) return
        patch(post.id) { it.copy(poll = poll.copy(myVote = optionId, options = poll.options.map { o -> if (o.id == optionId) o.copy(votes = o.votes + 1) else o })) }
        viewModelScope.launch { runCatching { repo.vote(post.id, optionId) } }
    }

    fun toggleGoing(post: FeedPost) {
        val plan = post.plan ?: return
        val m = repo.me.value ?: return
        val going = !plan.imGoing
        patch(post.id) { it.copy(plan = plan.copy(imGoing = going, going = plan.going.filterNot { p -> p.id == m.id } + if (going) listOf(m) else emptyList())) }
        viewModelScope.launch { runCatching { repo.setGoing(post.id, going) } }
    }

    suspend fun comments(postId: String) = runCatching { repo.comments(postId) }.getOrDefault(emptyList())

    suspend fun addComment(postId: String, text: String): Comment? {
        val c = repo.addComment(postId, text).getOrNull() ?: return null
        patch(postId) { it.copy(commentCount = it.commentCount + 1) }
        return c
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val vm = campusViewModel { FeedViewModel(it.repository) }
    val s by vm.state.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val c = Campus.colors
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var commentsFor by remember { mutableStateOf<FeedPost?>(null) }

    // refresh when coming back from the composer / stories
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!s.firstLoad) vm.refresh() }
    val shareTpl = stringResource(R.string.share_post_text)

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.width(8.dp))
            if (vm.isDemo) DemoBadge()
            Spacer(Modifier.weight(1f))
            CircleIconButton(Icons.Rounded.Add, stringResource(R.string.feed_new_post), { nav.compose() })
        }

        PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pull = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "stories") {
                    StoriesRow(me, s.stories, onMine = { mine -> if (mine != null) nav.stories(mine.profile.id) else nav.compose(story = true) }, onOpen = { nav.stories(it) })
                }
                if (s.firstLoad) items(3) { PostSkeleton() }
                items(s.posts, key = { it.id }) { post ->
                    PostCard(
                        post,
                        onAuthor = { if (!post.isOrganization) nav.user(post.authorId) },
                        onLike = { vm.toggleLike(post) },
                        onDoubleTap = { vm.toggleLike(post, forceLike = true) },
                        onComments = { commentsFor = post },
                        onShare = { shareText(context, shareTpl.format(post.authorName, post.caption ?: post.poll?.question ?: "", "https://lync.app/p/${post.id}")) },
                        onVote = { vm.vote(post, it) },
                        onGoing = { vm.toggleGoing(post) },
                        onPerson = { nav.user(it) },
                    )
                }
            }
        }
    }

    commentsFor?.let { post -> CommentsSheet(post, vm, onDismiss = { commentsFor = null }, onPerson = { commentsFor = null; nav.user(it) }) }
}

/** Stories (24h) — "Your story" first, unseen ones get the university-colored ring. */
@Composable
private fun StoriesRow(me: com.lync.app.data.model.Profile?, groups: List<StoryGroup>, onMine: (StoryGroup?) -> Unit, onOpen: (String) -> Unit) {
    val c = Campus.colors
    val mine = groups.firstOrNull { it.profile.id == me?.id }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (me != null) item(key = "me") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp).pressable({ onMine(mine) }, 0.94f)) {
                Box {
                    Avatar(me.avatarUrl, me.initials, 66.dp, ring = if (mine != null) c.accent else c.border)
                    if (mine == null) Box(
                        Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(c.bg).padding(2.dp).clip(CircleShape).background(c.text),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, null, tint = c.bg, modifier = Modifier.size(15.dp)) }
                }
                Spacer(Modifier.height(5.dp))
                Text(stringResource(R.string.story_yours), style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
            }
        }
        items(groups.filter { it.profile.id != me?.id }, key = { it.profile.id }) { g ->
            val preset = StatusPreset.of(g.profile.statusKey)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp).pressable({ onOpen(g.profile.id) }, 0.94f)) {
                Box {
                    Avatar(g.profile.avatarUrl, g.profile.initials, 66.dp, ring = if (g.seen) c.border else c.accent)
                    if (preset != null) Box(
                        Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(c.bg).padding(2.dp).clip(CircleShape).background(c.surface),
                        contentAlignment = Alignment.Center,
                    ) { Text(preset.emoji, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(5.dp))
                Text(g.profile.firstName, style = MaterialTheme.typography.labelMedium, color = if (g.seen) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.border))
}

@Composable
private fun PostCard(
    post: FeedPost,
    onAuthor: () -> Unit,
    onLike: () -> Unit,
    onDoubleTap: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    onVote: (String) -> Unit,
    onGoing: () -> Unit,
    onPerson: (String) -> Unit,
) {
    val c = Campus.colors
    val scope = rememberCoroutineScope()
    val burst = remember { Animatable(0f) }
    val doubleTap = Modifier.pointerInput(post.id) {
        detectTapGestures(onDoubleTap = {
            onDoubleTap()
            scope.launch {
                burst.snapTo(0f)
                burst.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
                burst.animateTo(0f, tween(250, delayMillis = 250))
            }
        })
    }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(horizontal = 16.dp).pressable(onAuthor, 0.98f), verticalAlignment = Alignment.CenterVertically) {
            Avatar(post.authorAvatar, post.authorName.take(2).uppercase(), 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.authorName, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
                    if (post.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Verified, null, tint = c.accent, modifier = Modifier.size(14.dp)) }
                }
                Text(if (post.sponsored) stringResource(R.string.feed_sponsored) else "@${post.authorHandle}", style = MaterialTheme.typography.bodySmall,
                    color = if (post.sponsored) c.accent else c.textMuted)
            }
            Text(timeAgo(post.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Spacer(Modifier.height(10.dp))

        val heart: @Composable () -> Unit = {
            Icon(Icons.Rounded.Favorite, null, tint = Color.White,
                modifier = Modifier.size(96.dp).graphicsLayer { scaleX = burst.value; scaleY = burst.value; alpha = burst.value.coerceIn(0f, 1f) })
        }
        when (post.kind) {
            PostKind.PHOTO -> Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface).then(doubleTap),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = post.imageUrl, contentDescription = post.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    loading = { Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.surface, c.surfaceAlt)))) },
                    error = { Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.3f), c.accent2.copy(alpha = 0.3f))))) },
                )
                heart()
            }
            PostKind.TEXT -> Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(gradientOf(post.textStyle)).then(doubleTap).padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(post.caption.orEmpty(), color = Color.White, fontFamily = DisplayFamily, fontSize = 26.sp, lineHeight = 30.sp, textAlign = TextAlign.Center)
                heart()
            }
            PostKind.POLL -> post.poll?.let { PollBlock(it, onVote) }
            PostKind.PLAN -> post.plan?.let { PlanBlock(it, onGoing, onPerson) }
        }

        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val likeScale by animateFloatAsState(if (post.likedByMe) 1f else 0.9f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "like")
            Action(onLike) {
                Icon(if (post.likedByMe) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, stringResource(R.string.a11y_like),
                    tint = if (post.likedByMe) c.accent else c.text, modifier = Modifier.size(26.dp).graphicsLayer { scaleX = likeScale; scaleY = likeScale })
            }
            Action(onComments) { Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.a11y_comment), tint = c.text, modifier = Modifier.size(24.dp)) }
            Action(onShare) { Icon(Icons.Outlined.IosShare, stringResource(R.string.a11y_share), tint = c.text, modifier = Modifier.size(24.dp)) }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(pluralStringResource(R.plurals.likes, post.likeCount, post.likeCount), style = MaterialTheme.typography.titleSmall, color = c.text)
            if (post.kind == PostKind.PHOTO || post.kind == PostKind.PLAN) post.caption?.let {
                Spacer(Modifier.height(2.dp))
                Text(buildAnnotatedString {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(post.authorHandle); pop(); append("  "); append(it)
                }, style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            if (post.commentCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(pluralStringResource(R.plurals.view_comments, post.commentCount, post.commentCount), style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted, modifier = Modifier.pressable(onComments, 0.98f))
            }
        }
    }
}

@Composable
private fun Action(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).pressable(onClick, 0.8f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PollBlock(poll: Poll, onVote: (String) -> Unit) {
    val c = Campus.colors
    val voted = poll.myVote != null
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface).padding(18.dp)) {
        Text(poll.question, style = MaterialTheme.typography.titleLarge, color = c.text)
        Spacer(Modifier.height(14.dp))
        poll.options.forEach { o ->
            val pct = if (poll.total == 0) 0f else o.votes.toFloat() / poll.total
            val fill by animateFloatAsState(if (voted) pct else 0f, tween(600), label = "fill")
            val mine = o.id == poll.myVote
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).height(46.dp).clip(RoundedCornerShape(14.dp)).background(c.bg)
                    .border(1.dp, if (mine) c.accent else c.border, RoundedCornerShape(14.dp))
                    .then(if (!voted) Modifier.pressable({ onVote(o.id) }, 0.98f) else Modifier),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(if (mine) c.accentSoft.copy(alpha = 0.22f) else c.surfaceAlt))
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.text, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (mine) { Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                    if (voted) Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = c.text)
                }
            }
        }
        val left = ((poll.endsAtMillis - System.currentTimeMillis()) / 3_600_000).coerceAtLeast(0)
        Text(
            pluralStringResource(R.plurals.votes, poll.total, poll.total) + ", " + if (left > 0) stringResource(R.string.poll_left, left) else stringResource(R.string.poll_closed),
            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
        )
    }
}

@Composable
private fun PlanBlock(plan: Plan, onGoing: () -> Unit, onPerson: (String) -> Unit) {
    val c = Campus.colors
    val full = plan.spots != null && plan.going.size >= plan.spots && !plan.imGoing
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.bg), contentAlignment = Alignment.Center) { Text(plan.activity.emoji, fontSize = 28.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(planActivityLabel(plan.activity), style = MaterialTheme.typography.titleLarge, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(planTime(plan.startsAtMillis), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Place, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(plan.place, style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                plan.going.take(4).forEach { p ->
                    Box(Modifier.clip(CircleShape).background(c.surface).padding(2.dp).pressable({ onPerson(p.id) })) {
                        Avatar(p.avatarUrl, p.initials, 30.dp)
                    }
                }
            }
            Text(
                if (plan.spots != null) stringResource(R.string.plan_going_spots, plan.going.size, plan.spots) else pluralStringResource(R.plurals.plan_going, plan.going.size, plan.going.size),
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            val bg by animateColorAsState(if (plan.imGoing) c.bg else c.accent, label = "going")
            AnimatedContent(plan.imGoing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "g") { going ->
                Row(
                    Modifier.clip(CircleShape).background(bg).border(1.dp, if (going) c.border else Color.Transparent, CircleShape)
                        .then(if (!full) Modifier.pressable(onGoing) else Modifier).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (going) { Icon(Icons.Rounded.Check, null, tint = c.text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(stringResource(if (going) R.string.plan_going_me else if (full) R.string.plan_full else R.string.plan_join),
                        style = MaterialTheme.typography.labelLarge, color = if (going) c.text else c.onAccent)
                }
            }
        }
    }
}

@Composable
private fun planTime(millis: Long): String {
    val diffMin = (millis - System.currentTimeMillis()) / 60_000
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    return when {
        diffMin <= 5 -> stringResource(R.string.plan_now)
        diffMin < 60 -> stringResource(R.string.plan_in_min, diffMin, clock)
        diffMin < 20 * 60 -> stringResource(R.string.plan_today, clock)
        else -> stringResource(R.string.plan_tomorrow, clock)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(post: FeedPost, vm: FeedViewModel, onDismiss: () -> Unit, onPerson: (String) -> Unit) {
    val c = Campus.colors
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(post.id) { comments = vm.comments(post.id) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = Tokens.Gutter))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                if (comments.isEmpty()) item {
                    Text(stringResource(R.string.comments_empty), style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                        modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = 24.dp))
                }
                items(comments, key = { it.id }) { cm ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = 10.dp)) {
                        Box(Modifier.pressable({ onPerson(cm.author.id) })) { Avatar(cm.author.avatarUrl, cm.author.initials, 34.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(buildAnnotatedString {
                                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(cm.author.username); pop()
                                append("  "); append(cm.text)
                            }, style = MaterialTheme.typography.bodyMedium, color = c.text)
                            Text(timeAgo(cm.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(CircleShape).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.comments_hint), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    BasicTextField(draft, { draft = it.take(300) }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                CircleIconButton(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.comments_send), {
                    val text = draft.trim()
                    if (text.isNotEmpty()) scope.launch {
                        vm.addComment(post.id, text)?.let { comments = comments + it; draft = "" }
                    }
                }, filled = draft.isNotBlank())
            }
        }
    }
}

@Composable
private fun PostSkeleton() {
    val c = Campus.colors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(width = 120.dp, height = 12.dp).clip(CircleShape).background(c.surface))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface))
    }
}
