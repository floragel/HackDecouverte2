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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
    val reducedMotion = com.lync.app.ui.motion.rememberSystemReducedMotion()
    var splashDone by remember { mutableStateOf(reducedMotion) }

    // Keep the demo session across restarts
    LaunchedEffect(me) { if (container.repository.isDemo) container.session.demoProfile = me }

    val onUniversityColors: (String, String) -> Unit = { a, b ->
        accent = a; accent2 = b
        container.session.accentHex = a; container.session.accent2Hex = b
    }

    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val settings = remember { ThemeController { mode -> themeMode = mode; container.session.themeMode = mode } }

    // After sign-in / session restore, re-tint the app with the user's university colors.
    LaunchedEffect(me?.universityId) {
        val id = me?.universityId ?: return@LaunchedEffect
        runCatching { container.repository.university(id) }.getOrNull()?.let { onUniversityColors(it.primaryColor, it.secondaryColor) }
    }

    CompositionLocalProvider(
        LocalAppContainer provides container, LocalThemeController provides settings,
        com.lync.app.ui.motion.LocalReducedMotion provides reducedMotion,
    ) {
        CampusTheme(accentHex = accent, accent2Hex = accent2, dark = dark) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val screen = when {
                !ready || !splashDone -> Screen.Loading
                me == null -> Screen.Onboarding
                landscape -> Screen.Card
                else -> Screen.Main
            }
            Box(Modifier.fillMaxSize().background(Campus.colors.bg)) {
                // MainScreen stays composed under the card so tabs / scroll keep their state.
                if (me != null && splashDone) MainScreen()
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                    label = "root",
                ) { s ->
                    when (s) {
                        Screen.Loading -> Splash(reducedMotion) { splashDone = true }
                        Screen.Onboarding -> OnboardingFlow(onUniversityColors)
                        Screen.Card -> me?.let { StudentCardScreen(it) }
                        Screen.Main -> Box(Modifier.fillMaxSize()) // MainScreen is drawn beneath
                    }
                }
            }
        }
    }
}

/** Launch: the two links snap together on the brand night blue (same as the app icon), then the app fades in. */
@Composable
private fun Splash(reduced: Boolean, onDone: () -> Unit) {
    var wordmark by remember { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (wordmark) 1f else 0f, androidx.compose.animation.core.tween(220), label = "w")
    Box(Modifier.fillMaxSize().background(com.lync.app.ui.theme.Tokens.Night), contentAlignment = androidx.compose.ui.Alignment.Center) {
        // same size & position as the system splash icon -> no jump between the two
        com.lync.app.ui.motion.LyncLogoIntro(148.dp, androidx.compose.ui.graphics.Color.White, reduced) { wordmark = true }
        androidx.compose.material3.Text(
            "Lync", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha),
            modifier = Modifier.offset(y = (96 + (1f - alpha) * 10).dp),
        )
    }
    LaunchedEffect(wordmark) { if (wordmark) { kotlinx.coroutines.delay(if (reduced) 0 else 260); onDone() } }
}

/** Lets the Settings screen switch light / dark / system live. */
class ThemeController(val set: (String) -> Unit)
val LocalThemeController = androidx.compose.runtime.staticCompositionLocalOf { ThemeController { } }

private enum class Screen { Loading, Onboarding, Main, Card }
