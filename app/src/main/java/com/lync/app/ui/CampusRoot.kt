package com.lync.app.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import com.lync.app.AppContainer
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.CampusTheme

/**
 * This branch is the building-navigation app only: splash, then « Find a room » straight away.
 * No sign-in, no social tabs (their code is still in the project, just not reachable).
 */
@Composable
fun CampusRoot(container: AppContainer) {
    var themeMode by remember { mutableStateOf(container.session.themeMode) }
    val reducedMotion = com.lync.app.ui.motion.rememberSystemReducedMotion()
    var splashDone by remember { mutableStateOf(reducedMotion) }

    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val settings = remember { ThemeController { mode -> themeMode = mode; container.session.themeMode = mode } }

    CompositionLocalProvider(
        LocalAppContainer provides container, LocalThemeController provides settings,
        com.lync.app.ui.motion.LocalReducedMotion provides reducedMotion,
    ) {
        CampusTheme(accentHex = container.session.accentHex, accent2Hex = container.session.accent2Hex, dark = dark) {
            Box(Modifier.fillMaxSize().background(Campus.colors.bg)) {
                if (splashDone) MainScreen()
                AnimatedContent(
                    targetState = splashDone,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                    label = "root",
                ) { done ->
                    if (!done) Splash(reducedMotion) { splashDone = true } else Box(Modifier.fillMaxSize())
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

