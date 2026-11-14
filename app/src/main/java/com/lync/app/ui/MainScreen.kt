package com.lync.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.lync.app.ui.compose.ComposeScreen
import com.lync.app.ui.profile.UserProfileScreen
import com.lync.app.ui.profile.EditProfileScreen
import com.lync.app.ui.settings.SettingsScreen
import com.lync.app.ui.scan.ScannerScreen
import com.lync.app.ui.stories.StoriesScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lync.app.R
import com.lync.app.ui.components.pressable
import com.lync.app.ui.feed.FeedScreen
import com.lync.app.ui.map.MapScreen
import com.lync.app.ui.network.NetworkScreen
import com.lync.app.ui.profile.ProfileScreen
import com.lync.app.ui.theme.Campus

private enum class Tab(val route: String, val label: Int, val icon: ImageVector, val iconSelected: ImageVector) {
    Home("home", R.string.tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    Network("network", R.string.tab_network, Icons.Outlined.People, Icons.Rounded.People),
    Map("map", R.string.tab_map, Icons.Outlined.Map, Icons.Rounded.Map),
    Profile("profile", R.string.tab_profile, Icons.Outlined.Person, Icons.Rounded.Person),
}

@Composable
fun MainScreen() {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route ?: Tab.Home.route
    val showBar = current in Routes.tabs

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Column(Modifier.fillMaxSize().background(Campus.colors.bg)) {
            NavHost(
                navController = nav,
                startDestination = Tab.Home.route,
                modifier = Modifier.weight(1f),
                enterTransition = {
                    if (targetState.destination.route in Routes.tabs) fadeIn(tween(180))
                    else slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200))
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = {
                    if (initialState.destination.route in Routes.tabs) fadeOut(tween(160))
                    else slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200))
                },
            ) {
                composable(Tab.Home.route) { FeedScreen() }
                composable(Tab.Network.route) { NetworkScreen() }
                composable(Tab.Map.route) { MapScreen() }
                composable(Tab.Profile.route) { ProfileScreen() }
                composable(
                    Routes.COMPOSE, arguments = listOf(navArgument("story") { type = NavType.BoolType; defaultValue = false }),
                    enterTransition = { slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(220)) },
                    popExitTransition = { slideOutVertically(tween(280)) { it / 2 } + fadeOut(tween(200)) },
                ) { ComposeScreen(storyMode = it.arguments?.getBoolean("story") == true) }
                composable(Routes.USER) { UserProfileScreen(it.arguments?.getString("id").orEmpty()) }
                composable(
                    Routes.STORIES,
                    enterTransition = { scaleIn(tween(260), initialScale = 0.9f) + fadeIn(tween(200)) },
                    popExitTransition = { scaleOut(tween(220), targetScale = 0.9f) + fadeOut(tween(180)) },
                ) { StoriesScreen(it.arguments?.getString("id").orEmpty()) }
                composable(Routes.EDIT) { EditProfileScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.SCAN) { ScannerScreen() }
                composable(Routes.EVENT) { com.lync.app.ui.network.EventDetailScreen(it.arguments?.getString("id").orEmpty()) }
                composable(Routes.GROUP) { com.lync.app.ui.network.GroupScreen(it.arguments?.getString("id").orEmpty()) }
            }
            AnimatedVisibility(showBar, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                BottomBar(current) { tab ->
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: String, onSelect: (Tab) -> Unit) {
    val c = Campus.colors
    Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = c.border, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                val selected = current == tab.route
                val tint by animateColorAsState(if (selected) c.text else c.textMuted, label = "tint")
                val dotWidth by animateDpAsState(if (selected) 16.dp else 0.dp, label = "dot")
                Column(
                    Modifier.weight(1f).pressable({ onSelect(tab) }, 0.9f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(if (selected) tab.iconSelected else tab.icon, stringResource(tab.label), tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall, color = tint)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(width = dotWidth, height = 4.dp).clip(CircleShape).background(c.accent)) // fixed height: no layout jump
                }
            }
        }
    }
}
