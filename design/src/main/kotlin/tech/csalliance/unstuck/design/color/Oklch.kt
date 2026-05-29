package tech.csalliance.unstuck.design.color

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// The web brand tokens are authored in oklch (app/globals.css). Compose has
// no oklch color, so we convert oklch → oklab → linear sRGB → gamma to
// reproduce the exact brand colors rather than eyeball hex. Standard oklab
// matrix (Björn Ottosson). Port of the iOS OKLCH.swift.

/** Linear-to-gamma sRGB components in 0…1 (clamped). Pure — unit-tested
 *  against known anchors so the palette renders the intended colors. */
fun oklchToRgb(l: Double, c: Double, h: Double): Triple<Double, Double, Double> {
    val hr = h * Math.PI / 180
    val a = c * cos(hr)
    val bb = c * sin(hr)

    val lStar = l + 0.3963377774 * a + 0.2158037573 * bb
    val mStar = l - 0.1055613458 * a - 0.0638541728 * bb
    val sStar = l - 0.0894841775 * a - 1.2914855480 * bb

    val lc = lStar * lStar * lStar
    val mc = mStar * mStar * mStar
    val sc = sStar * sStar * sStar

    val rLin = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc
    val gLin = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc
    val bLin = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc

    fun gamma(x: Double): Double {
        val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
        return minOf(1.0, maxOf(0.0, v))
    }
    return Triple(gamma(rLin), gamma(gLin), gamma(bLin))
}

/** Compose Color for an oklch token. */
fun oklch(l: Double, c: Double, h: Double, alpha: Double = 1.0): Color {
    val (r, g, b) = oklchToRgb(l, c, h)
    return Color(r.toFloat(), g.toFloat(), b.toFloat(), alpha.toFloat())
}

/** Parse `#RRGGBB` (the few non-oklch tokens: cream / surface). */
fun hexColor(hex: String): Color {
    val s = if (hex.startsWith("#")) hex.drop(1) else hex
    val v = s.toLong(16)
    val r = ((v shr 16) and 0xFF) / 255.0
    val g = ((v shr 8) and 0xFF) / 255.0
    val b = (v and 0xFF) / 255.0
    return Color(r.toFloat(), g.toFloat(), b.toFloat(), 1f)
}
