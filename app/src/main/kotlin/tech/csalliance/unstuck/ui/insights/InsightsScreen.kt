package tech.csalliance.unstuck.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.calibrationDots
import tech.csalliance.unstuck.core.logic.calibrationHitRate
import tech.csalliance.unstuck.core.logic.pauseAnatomy
import tech.csalliance.unstuck.core.logic.slipping
import tech.csalliance.unstuck.core.logic.topInsights
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val c = UTheme.colors
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val reasonLogs by vm.reasonLogs.collectAsStateWithLifecycle()

    val insights = topInsights(sessions, tasks, captures, reasonLogs)
    val dots = calibrationDots(sessions, tasks)
    val hit = if (dots.isNotEmpty()) (calibrationHitRate(dots) * 100).roundToInt() else null
    val slips = slipping(tasks, vm.nowMs())
    val pauses = pauseAnatomy(reasonLogs)
    val totalFocusMin = sessions.sumOf { it.actualSec } / 60

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink) }
            Text("Insights", style = UFont.serifItalic(24), color = c.ink)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Sessions", sessions.size.toString(), Modifier.weight(1f))
                    Stat("Focus", "${totalFocusMin}m", Modifier.weight(1f))
                    Stat("On estimate", hit?.let { "$it%" } ?: "—", Modifier.weight(1f))
                }
            }

            if (insights.isNotEmpty()) {
                item { SectionLabel("Worth noticing", Modifier.padding(vertical = 8.dp)) }
                items(insights) { ins ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ins.title, style = UFont.sans(15, FontWeight.SemiBold), color = c.ink)
                            Text(ins.sub, style = UFont.sans(13), color = c.ink2)
                        }
                    }
                }
            }

            if (pauses.isNotEmpty()) {
                item { SectionLabel("Pause anatomy", Modifier.padding(vertical = 8.dp)) }
                items(pauses) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.reason, style = UFont.sans(14), color = c.ink)
                        Text("${p.count}× · ${p.minutes.roundToInt()}m", style = UFont.mono(12), color = c.ink3)
                    }
                }
            }

            if (slips.isNotEmpty()) {
                item { SectionLabel("Slipping", Modifier.padding(vertical = 8.dp)) }
                items(slips) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.name, style = UFont.sans(14), color = c.ink, modifier = Modifier.weight(1f))
                        Text(if (s.moveCount >= 3) "moved ${s.moveCount}×" else "${s.weeks}w", style = UFont.mono(12), color = c.amber)
                    }
                }
            }

            if (sessions.isEmpty() && slips.isEmpty()) {
                item { Text("Focus a few sessions to unlock insights.", Modifier.padding(vertical = 24.dp), style = UFont.sans(14), color = c.ink3) }
            }
            item { Text("", Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Card(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = UFont.mono(20, FontWeight.Medium), color = c.ink)
            Text(label, style = UFont.sans(11), color = c.ink3)
        }
    }
}
