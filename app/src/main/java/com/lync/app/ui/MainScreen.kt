package com.lync.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lync.app.ui.indoor.IndoorDevScreen
import com.lync.app.ui.indoor.IndoorScreen
import com.lync.app.ui.settings.SettingsScreen
import com.lync.app.ui.theme.Campus

/**
 * Building navigation only: « Find a room » is the home screen; settings and the developer
 * test screen open on top of it. No bottom bar.
 */
@Composable
fun MainScreen() {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavHost(
            navController = nav,
            startDestination = Routes.INDOOR,
            modifier = Modifier.fillMaxSize().background(Campus.colors.bg),
            enterTransition = { slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)) },
        ) {
            composable(
                Routes.INDOOR,
                arguments = listOf(
                    navArgument("floor") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("node") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                IndoorScreen(
                    initialDestinationFloorId = it.arguments?.getString("floor"),
                    initialDestinationNodeId = it.arguments?.getString("node"),
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.INDOOR_DEV) { IndoorDevScreen() }
        }
    }
}
