package com.lync.app.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lync.app.AppContainer
import com.lync.app.data.demo.DemoRepository
import com.lync.app.data.supabase.SupabaseRepository
import com.lync.app.ui.card.StudentCardScreen
import com.lync.app.ui.onboarding.OnboardingFlow
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.CampusTheme

@Composable
fun CampusRoot(container: AppContainer) {
    var accent by remember { mutableStateOf(container.session.accentHex) }
    var accent2 by remember { mutableStateOf(container.session.accent2Hex) }
    var themeMode by remember { mutableStateOf(container.session.themeMode) }
    var ready by remember { mutableStateOf(container.repository is DemoRepository) }

    LaunchedEffect(Unit) {
        (container.repository as? SupabaseRepository)?.restore()
        ready = true
    }

    val me by container.repository.me.collectAsStateWithLifecycle()

    // Keep the demo session across restarts
    LaunchedEffect(me) { if (container.repository.isDemo) container.session.demoProfile = me }

    val onUniversityColors: (String, String) -> Unit = { a, b ->
        accent = a; accent2 = b
        container.session.accentHex = a; container.session.accent2Hex = b
    }

    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val settings = remember { ThemeController { mode -> themeMode = mode; container.session.themeMode = mode } }

    CompositionLocalProvider(LocalAppContainer provides container, LocalThemeController provides settings) {
        CampusTheme(accentHex = accent, accent2Hex = accent2, dark = dark) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val screen = when {
                !ready -> Screen.Loading
                me == null -> Screen.Onboarding
                landscape -> Screen.Card
                else -> Screen.Main
            }
            Box(Modifier.fillMaxSize().background(Campus.colors.bg)) {
                // MainScreen stays composed under the card so tabs / scroll keep their state.
                if (me != null) MainScreen()
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                    label = "root",
                ) { s ->
                    when (s) {
                        Screen.Loading -> Box(Modifier.fillMaxSize().background(Campus.colors.bg))
                        Screen.Onboarding -> OnboardingFlow(onUniversityColors)
                        Screen.Card -> me?.let { StudentCardScreen(it) }
                        Screen.Main -> Box(Modifier.fillMaxSize()) // MainScreen is drawn beneath
                    }
                }
            }
        }
    }
}

/** Lets the Settings screen switch light / dark / system live. */
class ThemeController(val set: (String) -> Unit)
val LocalThemeController = androidx.compose.runtime.staticCompositionLocalOf { ThemeController { } }

private enum class Screen { Loading, Onboarding, Main, Card }
