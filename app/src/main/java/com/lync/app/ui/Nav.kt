package com.lync.app.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

object Routes {
    const val SETTINGS = "settings"
    const val INDOOR = "indoor?floor={floor}&node={node}"
    const val INDOOR_DEV = "indoor-dev"
}

/** Screen-to-screen navigation, so screens never touch the NavController directly. */
class Navigator(private val nav: NavHostController) {
    fun settings() = nav.navigate(Routes.SETTINGS)
    /** User view (« Find a room »); with [floorId]/[nodeId] the room is pre-filled in « Going to ». */
    fun indoor(floorId: String? = null, nodeId: String? = null) {
        val route = if (floorId != null && nodeId != null) "indoor?floor=${Uri.encode(floorId)}&node=${Uri.encode(nodeId)}" else "indoor"
        nav.navigate(route) { launchSingleTop = true }
    }
    fun indoorDev() = nav.navigate(Routes.INDOOR_DEV) { launchSingleTop = true }
    fun back() { nav.popBackStack() }
}

val LocalAppContainer = staticCompositionLocalOf<com.lync.app.AppContainer> { error("No AppContainer") }

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }
