package tech.csalliance.unstuck.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.tasks.SelectableChip

private val STRUGGLES = listOf(
    "Getting started", "Time blindness", "Task switching", "Overwhelm",
    "Perfectionism", "Forgetting things", "Hyperfocus", "Prioritising",
)

// First-run onboarding: which struggles resonate (→ user_preferences), then
// seed the canonical life areas. Mirrors the web/iOS onboarding intent.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val c = UTheme.colors
    val picked = remember { mutableStateListOf<String>() }

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Welcome to unstuck", style = UFont.serifItalic(30), color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "What trips you up most? We'll tune the nudges. (Optional — pick any.)",
            style = UFont.sans(14), color = c.ink2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        SectionLabel("This sounds like me")
        Spacer(Modifier.height(10.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            STRUGGLES.forEach { s ->
                SelectableChip(s, selected = s in picked) {
                    if (s in picked) picked.remove(s) else picked.add(s)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        UButton("Get started") { vm.completeOnboarding(picked.toList()); onDone() }
        TextButton(onClick = { vm.completeOnboarding(emptyList()); onDone() }) {
            Text("Skip", color = c.ink3, style = UFont.sans(13))
        }
    }
}
