package tech.csalliance.unstuck.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale. The web uses Geist (sans) / Instrument Serif / IBM Plex Mono;
// bundling those into res/font is a P7 polish step — for now the brand serif
// (display) + monospace (timer/labels) map to the platform families, which
// keeps the hierarchy intact. UFont mirrors the iOS UFont helpers.

object UFont {
    val sans = FontFamily.Default
    val serif = FontFamily.Serif
    val mono = FontFamily.Monospace

    fun serifItalic(size: Int) = TextStyle(fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = size.sp)
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = mono, fontWeight = weight, fontSize = size.sp)
    fun sans(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = sans, fontWeight = weight, fontSize = size.sp)
}

val UnstuckTypography = Typography(
    displayLarge = TextStyle(fontFamily = UFont.serif, fontSize = 34.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontFamily = UFont.sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = UFont.sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = UFont.sans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = UFont.sans, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = UFont.sans, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = UFont.sans, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = UFont.mono, fontSize = 11.sp, letterSpacing = 0.5.sp),
)
