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

/** The Orbit mark: ink anchor dot + ~270° ink ring with a gap at 3 o'clock +
 *  a coral satellite dot in the gap. Mirrors the SVG in the mockups. */
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
        // ~270° arc, gap centered at 3 o'clock (where the satellite sits).
        drawArc(
            color = main,
            startAngle = 28f,
            sweepAngle = 304f,
            useCenter = false,
            topLeft = Offset(center.x - ringR, center.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawCircle(main, anchorR, center)
        drawCircle(satellite, satR, Offset(center.x + ringR, center.y))
    }
}
