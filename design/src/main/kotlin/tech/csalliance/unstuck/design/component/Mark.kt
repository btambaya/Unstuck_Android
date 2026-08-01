package tech.csalliance.unstuck.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.theme.UTheme

/** The Orbit mark: ink anchor dot + near-full ink ring with a tight ~51° gap
 *  at the lower-right, and the coral satellite resting ON the ring at
 *  3 o'clock (the gap's edge). Canonical geometry = brand mark.svg
 *  (`M 26.5 16 A 10.5 10.5 0 1 0 22.6 24.1`). */
@Composable
fun Orbit(modifier: Modifier = Modifier, size: Int = 22, white: Boolean = false, coral: Boolean = true) {
    val c = UTheme.colors
    val main = if (white) Color.White else c.ink
    val satellite = if (coral) c.coral else main
    Canvas(modifier.size(size.dp)) {
        val s = this.size.minDimension
        val center = Offset(s / 2f, s / 2f)
        val ringR = s * 10.5f / 32f
        val stroke = s * 2.2f / 32f
        val anchorR = s * 3.4f / 32f
        val satR = s * 2.1f / 32f
        // Canonical arc: 51°→360° clockwise from 3 o'clock — the gap is the
        // 0°→51° lower-right slice; the satellite sits AT its 3-o'clock edge.
        drawArc(
            color = main,
            startAngle = 51f,
            sweepAngle = 309f,
            useCenter = false,
            topLeft = Offset(center.x - ringR, center.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawCircle(main, anchorR, center)
        drawCircle(satellite, satR, Offset(center.x + ringR, center.y))
    }
}
