package com.lync.app.ui.network

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lync.app.R
import com.lync.app.data.CampusRepository
import com.lync.app.data.model.CardPayload
import com.lync.app.data.model.ConnectionState
import com.lync.app.data.model.Organization
import com.lync.app.data.model.Person
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.SectionHeader
import com.lync.app.ui.components.ScreenHeader
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.theme.Tokens
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetworkState(
    val requests: List<Person> = emptyList(),
    val classmates: List<Person> = emptyList(),
    val suggestions: List<Person> = emptyList(),
    val clubs: List<Organization> = emptyList(),
    val followingClubs: Set<String> = emptySet(),
    val query: String = "",
    val results: List<Person> = emptyList(),
)

class NetworkViewModel(private val repo: CampusRepository) : ViewModel() {
    private val _state = MutableStateFlow(NetworkState())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        val requests = runCatching { repo.requests() }.getOrDefault(emptyList())
        val classmates = runCatching { repo.classmates() }.getOrDefault(emptyList())
        val suggestions = runCatching { repo.suggestions() }.getOrDefault(emptyList())
        val clubs = runCatching { repo.clubs() }.getOrDefault(emptyList())
        _state.update { it.copy(requests = requests, classmates = classmates, suggestions = suggestions, clubs = clubs) }
    }

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250) // debounce
            val r = runCatching { repo.search(q) }.getOrDefault(emptyList())
            _state.update { it.copy(results = r) }
        }
    }

    /** Optimistic update across every list the person appears in. */
    private fun patch(id: String, f: (Person) -> Person) = _state.update { s ->
        val m: (List<Person>) -> List<Person> = { l -> l.map { if (it.profile.id == id) f(it) else it } }
        s.copy(classmates = m(s.classmates), suggestions = m(s.suggestions), results = m(s.results))
    }

    fun connect(p: Person, source: String = "app") {
        patch(p.profile.id) { it.copy(connection = ConnectionState.OUTGOING) }
        viewModelScope.launch { runCatching { repo.connect(p.profile.id, source) } }
    }

    fun respond(p: Person, accept: Boolean) {
        _state.update { s -> s.copy(requests = s.requests.filterNot { it.profile.id == p.profile.id }) }
        viewModelScope.launch { runCatching { repo.respond(p.profile.id, accept) } }
    }

    fun toggleClub(o: Organization) {
        val follow = o.id !in _state.value.followingClubs
        _state.update { it.copy(followingClubs = if (follow) it.followingClubs + o.id else it.followingClubs - o.id) }
        viewModelScope.launch { runCatching { repo.setFollowing(o.id, follow) } }
    }
}

@Composable
fun NetworkScreen() {
    val vm = campusViewModel { NetworkViewModel(it.repository) }
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Campus.colors

    val nav = com.lync.app.ui.LocalNavigator.current
    val scan: () -> Unit = { nav.scan() }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenHeader(stringResource(R.string.net_title), trailing = {
            CircleIconButton(Icons.Rounded.QrCodeScanner, stringResource(R.string.net_scan), scan, filled = true)
        })
        var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
        com.lync.app.ui.components.Segmented(
            listOf(stringResource(R.string.net_tab_people), stringResource(R.string.net_tab_events), stringResource(R.string.net_tab_groups)),
            tab, { tab = it }, Modifier.padding(horizontal = Tokens.Gutter, vertical = 6.dp),
        )
        AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
            when (t) {
                1 -> EventsTab()
                2 -> GroupsTab()
                else -> PeopleTab(vm, s)
            }
        }
    }
}

@Composable
private fun PeopleTab(vm: NetworkViewModel, s: NetworkState) {
    val nav = com.lync.app.ui.LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        SearchBar(s.query, vm::search)

        AnimatedContent(targetState = s.query.isBlank(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "search") { idle ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!idle) {
                    items(s.results, key = { "r" + it.profile.id }) { PersonRow(it, { vm.connect(it) }, { nav.user(it.profile.id) }) }
                } else {
                    if (s.requests.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.net_requests)) { CountBadge(s.requests.size) } }
                        items(s.requests, key = { "q" + it.profile.id }) { RequestRow(it, { vm.respond(it, true) }, { vm.respond(it, false) }, { nav.user(it.profile.id) }) }
                    }
                    if (s.classmates.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.net_in_your_classes)) }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(s.classmates, key = { "c" + it.profile.id }) { ClassmateCard(it, { nav.user(it.profile.id) }) { vm.connect(it) } }
                            }
                        }
                    }
                    if (s.clubs.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.net_clubs)) }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(s.clubs, key = { it.id }) { ClubChip(it, it.id in s.followingClubs) { vm.toggleClub(it) } }
                            }
                        }
                    }
                    if (s.suggestions.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.net_suggested)) }
                        items(s.suggestions, key = { "s" + it.profile.id }) { PersonRow(it, { vm.connect(it) }, { nav.user(it.profile.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    val c = Campus.colors
    Row(
        Modifier.padding(horizontal = Tokens.Gutter, vertical = 4.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.net_search), color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Rounded.Close, null, tint = c.textMuted, modifier = Modifier.size(18.dp).pressable({ onChange("") }))
    }
}

@Composable
private fun CountBadge(n: Int) {
    val c = Campus.colors
    Text("$n", color = c.onAccent, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun ConnectButton(p: Person, onConnect: () -> Unit) {
    AnimatedContent(targetState = p.connection, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "conn") { state ->
        when (state) {
            ConnectionState.NONE, ConnectionState.INCOMING -> Pill(stringResource(R.string.net_connect), onConnect)
            ConnectionState.OUTGOING -> Pill(stringResource(R.string.net_pending), {}, style = PillStyle.Tonal)
            ConnectionState.CONNECTED -> Pill(stringResource(R.string.net_connected), {}, style = PillStyle.Outline)
        }
    }
}

@Composable
private fun subtitle(p: Person): String = com.lync.app.ui.card.programLine(p.profile)

@Composable
private fun PersonRow(p: Person, onConnect: () -> Unit, onOpen: () -> Unit) {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().pressable(onOpen, 0.99f).padding(horizontal = Tokens.Gutter, vertical = 8.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.profile.avatarUrl, p.profile.initials, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.profile.fullName, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
            Text(subtitle(p), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.mutuals > 0) Text(stringResource(R.string.net_mutuals, p.mutuals), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        ConnectButton(p, onConnect)
    }
}

@Composable
private fun RequestRow(p: Person, onAccept: () -> Unit, onDecline: () -> Unit, onOpen: () -> Unit) {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().pressable(onOpen, 0.99f).padding(horizontal = Tokens.Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.profile.avatarUrl, p.profile.initials, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.profile.fullName, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
            Text(subtitle(p), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
        }
        Pill(stringResource(R.string.net_accept), onAccept)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.surface).pressable(onDecline), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, stringResource(R.string.net_decline), tint = c.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ClassmateCard(p: Person, onOpen: () -> Unit, onConnect: () -> Unit) {
    val c = Campus.colors
    Column(
        Modifier.width(160.dp).clip(RoundedCornerShape(Tokens.RadiusMedia)).background(c.surface).pressable(onOpen, 0.98f).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(p.profile.avatarUrl, p.profile.initials, 64.dp)
        Spacer(Modifier.height(10.dp))
        Text(p.profile.fullName, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.profile.program ?: "", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.net_shared_courses, p.sharedCourses), style = MaterialTheme.typography.labelMedium, color = c.accent,
            modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        ConnectButton(p, onConnect)
    }
}

@Composable
private fun ClubChip(o: Organization, following: Boolean, onToggle: () -> Unit) {
    val c = Campus.colors
    Row(
        Modifier.clip(CircleShape).background(c.surface).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(o.avatarUrl, o.name.take(2).uppercase(), 36.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
                if (o.verified) { Spacer(Modifier.width(3.dp)); Icon(Icons.Rounded.Verified, null, tint = c.accent, modifier = Modifier.size(13.dp)) }
            }
            Text("@${o.handle}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Pill(
            stringResource(if (following) R.string.net_following else R.string.net_follow), onToggle,
            style = if (following) PillStyle.Outline else PillStyle.Filled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
