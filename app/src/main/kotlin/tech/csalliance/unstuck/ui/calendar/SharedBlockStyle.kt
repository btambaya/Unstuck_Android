package tech.csalliance.unstuck.ui.calendar

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dashed rounded outline — the calendars' "this block is someone else's" mark on a
 *  shared block (migration 052). Own blocks keep their solid 1dp border, so the two
 *  read differently at a glance even before the "· owner" suffix on the label. Draws inside the
 *  bounds (half the stroke inset) so the clip never eats the dashes. */
fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier = drawBehind {
    val w = width.toPx()
    val inset = w / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()), 0f)),
    )
}
