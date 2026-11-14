package com.lync.app.ui.motion

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Lync motion system — the same values must be used on iOS (see design/README.md › Motion).
 *
 * Rules:
 *  1. One signature moment (two people connecting). Everything else only answers a gesture.
 *  2. Springs everywhere, nothing longer than 300 ms except the signature moment.
 *  3. If the user turned animations off in system settings, we snap instead of animating.
 */
object Motion {
    /** Press feedback (buttons, cards). iOS: .spring(response: 0.25, dampingFraction: 0.6) */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 600f)
    /** Default UI movement. iOS: .spring(response: 0.35, dampingFraction: 0.8) */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 400f)
    /** Playful pop (likes, avatars joining). iOS: .spring(response: 0.4, dampingFraction: 0.5) */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f)

    const val SHORT = 180      // fades, colour changes
    const val MEDIUM = 280     // screen transitions
    const val STAGGER = 70     // delay between items of a list (poll bars, avatars)
    const val SIGNATURE = 1500 // the connection celebration
}

/** True when the user disabled animations (Settings › Accessibility › Remove animations). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Small, consistent haptics. iOS mapping:
 *  tick    -> UISelectionFeedbackGenerator
 *  confirm -> UIImpactFeedbackGenerator(.light)
 *  success -> UINotificationFeedbackGenerator(.success)
 */
class Haptics(private val view: View) {
    private fun perform(constant: Int) { view.performHapticFeedback(constant) }

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** Two beats — used only when two people connect. */
    fun success() {
        perform(HapticFeedbackConstants.LONG_PRESS)
        view.postDelayed({ perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }, 140)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
