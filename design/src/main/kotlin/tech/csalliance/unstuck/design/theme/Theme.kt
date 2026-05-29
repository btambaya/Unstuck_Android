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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.color.hexColor
import tech.csalliance.unstuck.design.color.oklch

// Brand palette + theme — values taken verbatim from the Android Mockups
// (unstuck-v2). Neutrals are exact hex; accents are oklch rendered via the
// oklab→sRGB converter. `primary` = indigo. Soft + "ink" (dark-text) variants
// back the StatCard badges, the avatar, and tonal chips.

@Immutable
data class UnstuckColors(
    val bg: Color, val bg2: Color, val surface: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val ink4: Color,
    val line: Color, val line2: Color,
    val primary: Color, val primarySoft: Color, val primaryDeep: Color,
    val coral: Color, val coralSoft: Color, val coralDeep: Color,
    val green: Color, val greenSoft: Color, val greenInk: Color,
    val blue: Color, val blueSoft: Color, val blueInk: Color,
    val amber: Color, val amberSoft: Color, val amberInk: Color,
    val violet: Color, val red: Color,
    val isDark: Boolean,
) {
    /** Resolve a life-area / collection color TOKEN (indigo, coral, …) to a Color. */
    fun areaColor(token: String?): Color = when ((token ?: "").lowercase()) {
        "indigo", "primary" -> primary
        "coral", "rethink", "test" -> coral
        "violet" -> violet
        "blue" -> blue
        "green", "new feature", "bug" -> green
        "amber" -> amber
        "red" -> red
        else -> ink4
    }

    /** The soft rounded color-chip fill: the area color at ~32% over surface. */
    fun areaSwatch(color: Color): Color = lerp(surface, color, 0.32f)

    companion object {
        val light = UnstuckColors(
            bg = hexColor("#FAFAF7"), bg2 = hexColor("#F4F2EC"), surface = hexColor("#FFFFFF"),
            ink = hexColor("#1A1C26"), ink2 = hexColor("#414252"),
            ink3 = hexColor("#7B7D8E"), ink4 = hexColor("#B5B6C0"),
            line = hexColor("#EAE7DD"), line2 = hexColor("#D9D5CA"),
            primary = oklch(0.58, 0.13, 280.0), primarySoft = oklch(0.93, 0.04, 280.0),
            primaryDeep = oklch(0.42, 0.13, 280.0),
            coral = hexColor("#E89077"), coralSoft = oklch(0.94, 0.05, 40.0), coralDeep = oklch(0.48, 0.16, 35.0),
            green = oklch(0.72, 0.10, 155.0), greenSoft = oklch(0.94, 0.04, 155.0), greenInk = oklch(0.40, 0.10, 155.0),
            blue = oklch(0.70, 0.10, 240.0), blueSoft = oklch(0.94, 0.03, 240.0), blueInk = oklch(0.40, 0.10, 240.0),
            amber = oklch(0.80, 0.13, 75.0), amberSoft = oklch(0.95, 0.05, 75.0), amberInk = oklch(0.45, 0.13, 75.0),
            violet = oklch(0.55, 0.13, 300.0), red = oklch(0.66, 0.13, 25.0),
            isDark = false,
        )

        val dark = UnstuckColors(
            bg = oklch(0.205, 0.025, 270.0), bg2 = oklch(0.255, 0.03, 270.0), surface = oklch(0.27, 0.03, 270.0),
            ink = oklch(0.96, 0.005, 270.0), ink2 = oklch(0.82, 0.01, 270.0),
            ink3 = oklch(0.66, 0.015, 270.0), ink4 = oklch(0.45, 0.02, 270.0),
            line = oklch(0.34, 0.025, 270.0), line2 = oklch(0.40, 0.03, 270.0),
            primary = oklch(0.72, 0.13, 280.0), primarySoft = oklch(0.32, 0.07, 280.0),
            primaryDeep = oklch(0.82, 0.12, 280.0),
            coral = hexColor("#E89077"), coralSoft = oklch(0.36, 0.08, 35.0), coralDeep = oklch(0.58, 0.16, 35.0),
            green = oklch(0.72, 0.10, 155.0), greenSoft = oklch(0.34, 0.07, 155.0), greenInk = oklch(0.86, 0.10, 155.0),
            blue = oklch(0.70, 0.10, 240.0), blueSoft = oklch(0.34, 0.06, 240.0), blueInk = oklch(0.86, 0.08, 240.0),
            amber = oklch(0.80, 0.13, 75.0), amberSoft = oklch(0.36, 0.08, 75.0), amberInk = oklch(0.88, 0.11, 75.0),
            violet = oklch(0.74, 0.13, 300.0), red = oklch(0.70, 0.13, 25.0),
            isDark = true,
        )
    }
}

/** Corner radii used across the design. */
object Radius {
    val sm = 8.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 24.dp
    val pill = 999.dp
}

val LocalUnstuckColors = staticCompositionLocalOf { UnstuckColors.light }

/** Convenience accessor: `UTheme.colors.coral` inside composables. */
object UTheme {
    val colors: UnstuckColors
        @Composable get() = LocalUnstuckColors.current
}

/** Accent palettes — mirror the web theme-context ACCENT_PALETTES. */
enum class AccentPalette { INDIGO_CORAL, PERIWINKLE_ROSE, FOREST_AMBER }

/** Override the primary/coral ramp on a base palette for the chosen accent.
 *  INDIGO_CORAL is the brand default (no change). */
fun UnstuckColors.withAccent(accent: AccentPalette): UnstuckColors = when (accent) {
    AccentPalette.INDIGO_CORAL -> this
    AccentPalette.PERIWINKLE_ROSE -> copy(
        primary = oklch(0.62, 0.14, 265.0), primaryDeep = oklch(0.42, 0.16, 265.0), primarySoft = oklch(0.94, 0.04, 265.0),
        coral = oklch(0.74, 0.14, 15.0), coralSoft = oklch(0.95, 0.05, 15.0), coralDeep = oklch(0.50, 0.16, 15.0),
    )
    AccentPalette.FOREST_AMBER -> copy(
        primary = oklch(0.55, 0.10, 170.0), primaryDeep = oklch(0.38, 0.10, 170.0), primarySoft = oklch(0.94, 0.04, 170.0),
        coral = oklch(0.74, 0.14, 65.0), coralSoft = oklch(0.95, 0.05, 65.0), coralDeep = oklch(0.48, 0.13, 65.0),
    )
}

@Composable
fun UnstuckTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: AccentPalette = AccentPalette.INDIGO_CORAL,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors = (if (dark) UnstuckColors.dark else UnstuckColors.light).withAccent(accent)
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
        // Density + larger-type both fold into one font-scale multiplier so
        // every sp text size responds (mirrors the web density / larger-type).
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * fontScale)) {
            MaterialTheme(colorScheme = scheme, typography = UnstuckTypography, content = content)
        }
    }
}
