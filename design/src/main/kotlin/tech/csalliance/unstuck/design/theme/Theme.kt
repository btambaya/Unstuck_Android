package tech.csalliance.unstuck.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.color.hexColor
import tech.csalliance.unstuck.design.color.oklch

// Brand-v2 palette + theme. Values ported verbatim from
// ../unstuck/app/globals.css (:root light + .u-dark) via the iOS Tokens.swift.
// Colors render through the oklch→sRGB converter so they match the web.

@Immutable
data class UnstuckColors(
    val bg: Color, val bg2: Color, val surface: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val ink4: Color,
    val line: Color, val line2: Color,
    val primary: Color, val primarySoft: Color, val primaryDeep: Color,
    val coral: Color, val coralSoft: Color, val coralDeep: Color,
    val violet: Color, val blue: Color, val green: Color, val amber: Color, val red: Color,
    val isDark: Boolean,
) {
    /** Resolve a life-area / collection color token (indigo, coral, …). */
    fun areaColor(token: String?): Color = when ((token ?: "").lowercase()) {
        "indigo", "primary" -> primary
        "coral" -> coral
        "violet" -> violet
        "blue" -> blue
        "green" -> green
        "amber" -> amber
        "red" -> red
        else -> ink3
    }

    companion object {
        val light = UnstuckColors(
            bg = hexColor("#FAFAF7"), bg2 = hexColor("#F4F2EC"), surface = hexColor("#FFFFFF"),
            ink = oklch(0.22, 0.02, 280.0), ink2 = oklch(0.40, 0.02, 280.0),
            ink3 = oklch(0.58, 0.02, 280.0), ink4 = oklch(0.78, 0.01, 280.0),
            line = oklch(0.92, 0.005, 280.0), line2 = oklch(0.88, 0.008, 280.0),
            primary = oklch(0.58, 0.13, 280.0), primarySoft = oklch(0.93, 0.04, 280.0),
            primaryDeep = oklch(0.42, 0.13, 280.0),
            coral = oklch(0.72, 0.13, 35.0), coralSoft = oklch(0.94, 0.05, 40.0),
            coralDeep = oklch(0.48, 0.16, 35.0),
            violet = oklch(0.55, 0.13, 300.0), blue = oklch(0.70, 0.10, 240.0),
            green = oklch(0.72, 0.10, 155.0), amber = oklch(0.80, 0.13, 75.0),
            red = oklch(0.66, 0.13, 25.0), isDark = false,
        )

        val dark = UnstuckColors(
            bg = oklch(0.205, 0.025, 270.0), bg2 = oklch(0.24, 0.03, 270.0), surface = oklch(0.26, 0.03, 270.0),
            ink = oklch(0.96, 0.005, 270.0), ink2 = oklch(0.82, 0.01, 270.0),
            ink3 = oklch(0.66, 0.015, 270.0), ink4 = oklch(0.45, 0.02, 270.0),
            line = oklch(0.34, 0.025, 270.0), line2 = oklch(0.38, 0.03, 270.0),
            primary = oklch(0.72, 0.13, 280.0), primarySoft = oklch(0.32, 0.07, 280.0),
            primaryDeep = oklch(0.82, 0.12, 280.0),
            coral = oklch(0.72, 0.13, 35.0), coralSoft = oklch(0.36, 0.08, 35.0),
            coralDeep = oklch(0.48, 0.16, 35.0),
            violet = oklch(0.74, 0.13, 300.0), blue = oklch(0.70, 0.10, 240.0),
            green = oklch(0.72, 0.10, 155.0), amber = oklch(0.80, 0.13, 75.0),
            red = oklch(0.66, 0.13, 25.0), isDark = true,
        )
    }
}

/** Corner radii (the brand surface tokens). */
object Radius {
    val sm = 8.dp
    val md = 14.dp
    val lg = 22.dp
}

val LocalUnstuckColors = staticCompositionLocalOf { UnstuckColors.light }

/** Convenience accessor: `UTheme.colors.coralDeep` inside composables. */
object UTheme {
    val colors: UnstuckColors
        @Composable get() = LocalUnstuckColors.current
}

@Composable
fun UnstuckTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) UnstuckColors.dark else UnstuckColors.light
    // Map our brand tokens onto a Material3 scheme so stock M3 components
    // (ripples, text fields) inherit the palette; brand surfaces read the
    // CompositionLocal directly.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.bg, secondary = colors.coral,
            background = colors.bg, onBackground = colors.ink, surface = colors.surface,
            onSurface = colors.ink, error = colors.red, outline = colors.line,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = Color.White, secondary = colors.coral,
            background = colors.bg, onBackground = colors.ink, surface = colors.surface,
            onSurface = colors.ink, error = colors.red, outline = colors.line,
        )
    }
    CompositionLocalProvider(LocalUnstuckColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = UnstuckTypography, content = content)
    }
}
