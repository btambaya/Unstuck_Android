package tech.csalliance.unstuck.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private val AREA_OPTIONS = listOf("Work", "Personal", "Home", "Health", "Family", "Volunteering", "Study", "Side project")
private val AREA_PALETTE = listOf("indigo", "coral", "green", "amber", "violet", "blue")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val c = UTheme.colors
    // Saveable so a rotation / theme change doesn't restart onboarding from step 0 or
    // lose the typed first task.
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val pickedAreas = remember { mutableStateListOf("Work", "Personal", "Home") }
    var firstTask by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var treatment by remember { mutableStateOf(FocusTreatment.AMBIENT) }

    fun finish() {
        // completeOnboarding seeds the picked areas (single source — no double seed).
        vm.updateSettings { it.copy(treatment = treatment) }   // persist chosen treatment
        if (firstTask.isNotBlank()) vm.addTask(name = firstTask.trim(), estimateMin = 15, lifeArea = pickedAreas.firstOrNull())
        vm.completeOnboarding(struggles = emptyList(), areas = pickedAreas.toList())
        onDone()
    }

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp, vertical = 30.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            (0..3).forEach { i ->
                Box(Modifier.height(6.dp).width(if (i == step) 16.dp else 6.dp).clip(RoundedCornerShape(999.dp)).background(if (i <= step) c.ink else c.line2))
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("STEP ${step + 1} OF 4", color = c.primaryDeep)
            when (step) {
                0 -> {
                    Text("Welcome.", style = UFont.serifItalic(32), color = c.ink)
                    Text("Unstuck is built for minds that struggle to start. Three minutes to set up.", style = UFont.sans(14), color = c.ink3)
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Orbit(size = 88) }
                }
                1 -> {
                    Text("What parts of life share your attention?", style = UFont.serifItalic(26), color = c.ink)
                    Text("Pick a few. You can change these any time.", style = UFont.sans(14), color = c.ink3)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AREA_OPTIONS.forEach { a ->
                            val on = a in pickedAreas
                            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) c.ink else c.bg2).clickable { if (on) pickedAreas.remove(a) else pickedAreas.add(a) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(a, style = UFont.sans(13, FontWeight.Medium), color = if (on) c.bg else c.ink2)
                            }
                        }
                    }
                }
                2 -> {
                    Text("What's one thing on your mind right now?", style = UFont.serifItalic(26), color = c.ink)
                    Text("Just one. Small is good. We'll start there.", style = UFont.sans(14), color = c.ink3)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.line2, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                        BasicTextField(value = firstTask, onValueChange = { firstTask = it }, textStyle = UFont.sans(16).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), decorationBox = { inner -> if (firstTask.isEmpty()) Text("Reply to landlord about parking", style = UFont.sans(16), color = c.ink3); inner() })
                    }
                }
                else -> {
                    Text("Pick how focus feels.", style = UFont.serifItalic(26), color = c.ink)
                    Text("You can switch any time. Most people start with Ambient.", style = UFont.sans(14), color = c.ink3)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            FocusTreatment.AMBIENT to ("Ambient" to "A gentle breathing ring. Calm presence."),
                            FocusTreatment.COCKPIT to ("Cockpit" to "Timer, controls visible. Tighter feedback."),
                            FocusTreatment.MONK to ("Monk" to "Just the task. Everything else hidden."),
                        ).forEach { (t, v) ->
                            val on = treatment == t
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.ink else c.bg2).clickable { treatment = t }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.first, style = UFont.sans(14, FontWeight.SemiBold), color = if (on) c.bg else c.ink)
                                    Text(v.second, style = UFont.sans(12), color = if (on) c.bg.copy(alpha = 0.7f) else c.ink3, modifier = Modifier.padding(top = 3.dp))
                                }
                                if (on) Text("✓", style = UFont.sans(14, FontWeight.Bold), color = c.bg)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Skip", style = UFont.sans(14), color = c.ink3, modifier = Modifier.clickable { vm.completeOnboarding(emptyList()); onDone() })
                Box(Modifier.weight(1f))
                UButton(if (step == 3) "Begin" else "Continue", kind = ButtonKind.DARK, fill = false) { if (step == 3) finish() else step++ }
            }
        }
    }
}
