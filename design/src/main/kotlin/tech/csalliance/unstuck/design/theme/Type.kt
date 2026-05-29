@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package tech.csalliance.unstuck.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tech.csalliance.unstuck.design.R

// Type scale on the bundled brand fonts (ported from the web): Geist (sans,
// variable-weight), Instrument Serif (display), IBM Plex Mono (timer/labels).
// UFont mirrors the iOS UFont helpers.

private val GeistSans = FontFamily(
    Font(R.font.geist_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geist_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.geist_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

object UFont {
    val sans = GeistSans
    val serif = InstrumentSerif
    val mono = PlexMono

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
