package tech.csalliance.unstuck.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.calibrationDots
import tech.csalliance.unstuck.core.logic.calibrationHitRate
import tech.csalliance.unstuck.core.logic.slipping
import tech.csalliance.unstuck.core.logic.timeOfDayHeatmap
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.StatCard
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(vm: AppViewModel, deep: Boolean, onBack: () -> Unit, onToggleDeep: (Boolean) -> Unit) {
    val c = UTheme.colors
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val dots = calibrationDots(sessions, tasks)
    val hit = if (dots.isNotEmpty()) (calibrationHitRate(dots) * 100).roundToInt() else 0
    val slips = slipping(tasks, vm.nowMs())
    val totalMin = sessions.sumOf { it.actualSec } / 60

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            item {
                SectionLabel(if (deep) "REFLECTION · ALL TIME" else "REFLECTION · WEEK SO FAR", color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
                Text(if (deep) "Let's look closer. Calmly." else "This is a quiet week, on purpose.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                Text(if (deep) "All your patterns, one screen." else "No score. Observations to calibrate, not perform.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.padding(top = 14.dp, bottom = 6.dp)) {
                    MdSegment(listOf("Report", "Deep dive"), if (deep) "Deep dive" else "Report") { onToggleDeep(it == "Deep dive") }
                }
            }
            if (!deep) {
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Estimates", "$hit%", "${sessions.size} sessions", c.greenSoft, c.greenInk, "of sessions landed within 5 min.")
                        StatCard("Re-entries", "${sessions.size}", "${captures.size} captures", c.blueSoft, c.blueInk, "focus sessions completed")
                        StatCard("Gentle friction", "${slips.size} tasks", if (slips.isEmpty()) "All clear." else "Watch these", if (slips.isEmpty()) c.greenSoft else c.amberSoft, if (slips.isEmpty()) c.greenInk else c.amberInk, "nothing slipping")
                    }
                }
            } else {
                item {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("Focus", "${totalMin / 60}h ${totalMin % 60}m", caption = "across ${sessions.size} sessions", modifier = Modifier.weight(1f))
                        StatCard("On estimate", "$hit%", caption = "within 5 min", modifier = Modifier.weight(1f))
                    }
                }
                item { Heatmap(timeOfDayHeatmap(sessions)) }
            }
            item { Box(Modifier.padding(24.dp)) {} }
        }
    }
}

@Composable
private fun Heatmap(grid: List<List<Double>>) {
    val c = UTheme.colors
    val max = (grid.flatten().maxOrNull() ?: 0.0).coerceAtLeast(0.001)
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hour × day", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("Brighter = more focus.", style = UFont.sans(11), color = c.ink3)
            grid.forEachIndexed { d, row ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(days.getOrElse(d) { "" }, style = UFont.sans(11), color = c.ink3, modifier = Modifier.width(30.dp))
                    row.forEach { v ->
                        val t = (v / max).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(if (v <= 0.0) c.bg2 else lerp(c.bg2, c.green, 0.2f + 0.7f * t)))
                    }
                }
            }
        }
    }
}
