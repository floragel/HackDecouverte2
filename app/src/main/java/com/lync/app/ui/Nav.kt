package com.lync.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

object Routes {
    const val HOME = "home"
    const val NETWORK = "network"
    const val MAP = "map"
    const val PROFILE = "profile"
    const val COMPOSE = "compose?story={story}"
    const val USER = "user/{id}"
    const val STORIES = "stories/{id}"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val EVENT = "event/{id}"
    const val GROUP = "group/{id}"
    const val INDOOR = "indoor?floor={floor}&node={node}"
    const val INDOOR_DEV = "indoor-dev"
    const val CALENDAR = "calendar"
    const val GO = "go/{place}"
    val tabs = setOf(HOME, NETWORK, CALENDAR, MAP, PROFILE)
}

/** Screen-to-screen navigation, so screens never touch the NavController directly (iOS: a Router / NavigationPath). */
class Navigator(private val nav: NavHostController) {
    fun user(id: String) = nav.navigate("user/$id")
    fun compose(story: Boolean = false) = nav.navigate("compose?story=$story")
    fun stories(profileId: String) = nav.navigate("stories/$profileId")
    fun editProfile() = nav.navigate(Routes.EDIT)
    fun settings() = nav.navigate(Routes.SETTINGS)
    fun scan() = nav.navigate(Routes.SCAN)
    fun event(id: String) = nav.navigate("event/$id")
    fun group(id: String) = nav.navigate("group/$id")
    fun calendar() = nav.navigate(Routes.CALENDAR)
    fun map() = nav.navigate(Routes.MAP) { launchSingleTop = true }
    /** Door-to-door directions to a room / building ("MB-2.210", "H-920", "JMSB, MB-1.437"). */
    fun go(place: String) = nav.navigate("go/" + android.net.Uri.encode(place)) { launchSingleTop = true }
    /** Opens « Find a room »; with [floorId]/[nodeId] the room is pre-filled in « Going to ». */
    fun indoor(floorId: String? = null, nodeId: String? = null) {
        val route = if (floorId != null && nodeId != null) "indoor?floor=${Uri.encode(floorId)}&node=${Uri.encode(nodeId)}" else "indoor"
        nav.navigate(route) { launchSingleTop = true }
    }
    fun indoorDev() = nav.navigate(Routes.INDOOR_DEV) { launchSingleTop = true }
    fun back() { nav.popBackStack() }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
