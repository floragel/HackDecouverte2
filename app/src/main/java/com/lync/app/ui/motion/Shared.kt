package com.lync.app.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Shared element transitions between screens (event cover -> event page, group tile -> group page).
 * Screens just add `Modifier.sharedElement("key")`; it is a no-op outside the navigation host
 * or when reduced motion is on. iOS: `.matchedGeometryEffect(id:in:)` / `.navigationTransition(.zoom)`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun ProvideNavScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedScope provides scope, content = content)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElement(key: String): Modifier {
    if (LocalReducedMotion.current) return this
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedElement.sharedBounds(rememberSharedContentState(key), anim)
    }
}
