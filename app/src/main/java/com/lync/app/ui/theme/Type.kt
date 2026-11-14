package com.lync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lync.app.R

/*
 * Two typefaces, bundled on both platforms so Android and iOS look identical:
 *  - Bricolage Grotesque (display): page titles, names, numbers. Gives Lync its voice.
 *  - Figtree (text): everything you read or tap.
 * Both are variable fonts (OFL), files in res/font and design/fonts.
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, opsz: Float) = Font(
    R.font.bricolage, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz), FontVariation.Setting("wdth", 96f)),
)

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(R.font.figtree, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val DisplayFamily = FontFamily(bricolage(600, 36f), bricolage(700, 48f), bricolage(800, 72f))
val TextFamily = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

val CampusTypography = Typography(
    // Display (Bricolage)
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(800), fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.4).sp),
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(700), fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp),
    headlineSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    // Text (Figtree)
    titleMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 13.sp),
)

val CampusShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(Tokens.RadiusControl),
    large = RoundedCornerShape(Tokens.RadiusCard),
)
