package com.lync.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Lync design tokens — THE single source of truth.
 * The iOS app must declare the exact same values (see design/README.md).
 *
 * Palette: cool "night ink" neutrals. The only saturated color in the UI
 * is the student's university (accent / accent2), injected at runtime.
 */
object Tokens {
    // Light
    val Paper = Color(0xFFFFFFFF)          // background
    val Mist = Color(0xFFF3F4F7)           // surfaces, inputs, chips
    val Fog = Color(0xFFE8EAF0)            // pressed / secondary surface
    val Line = Color(0xFFE3E5EB)           // hairlines
    val Night = Color(0xFF1A1D29)          // primary text, ink
    val Slate = Color(0xFF6B7081)          // secondary text

    // Dark
    val NightBg = Color(0xFF10121A)
    val NightSurface = Color(0xFF1A1D29)
    val NightSurfaceAlt = Color(0xFF252938)
    val NightLine = Color(0xFF2A2E3D)
    val NightSlate = Color(0xFF9398A8)

    // Radii — by hierarchy, not one radius for everything
    val RadiusCard = 28.dp      // the Lync student card, sheets
    val RadiusMedia = 22.dp     // photos
    val RadiusControl = 16.dp   // inputs, primary buttons, tiles
    // chips / avatars are fully round

    // Spacing (4pt grid)
    val Gutter = 20.dp          // screen side margin everywhere
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 24.dp
    val S6 = 32.dp
}
