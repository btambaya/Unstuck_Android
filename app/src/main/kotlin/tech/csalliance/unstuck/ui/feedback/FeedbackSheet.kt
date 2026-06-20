package tech.csalliance.unstuck.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// (key sent to the server, label shown). Key is stable; the label is just UI.
private val CATEGORIES = listOf("bug" to "Bug", "idea" to "Idea", "praise" to "Praise", "other" to "Other")

/**
 * One-way beta feedback composer (bottom sheet). Type, pick a tag, Send — fires to
 * the `feedback` table with auto-attached context (app version / screen / device),
 * shows a brief "Thanks", then dismisses. No thread / replies (we triage in the
 * dashboard). `currentScreen` is the tab the user opened it from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(vm: AppViewModel, currentScreen: String?, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        FeedbackForm(vm, currentScreen, onDone = onDismiss)
    }
}

/** The feedback composer content (no sheet wrapper) — reused inside the
 *  assistant surface's "Feedback" tab. Calls [onDone] after the thanks lands. */
@Composable
fun FeedbackForm(vm: AppViewModel, currentScreen: String?, onDone: () -> Unit) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("bug") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ctx = "Attaches v${tech.csalliance.unstuck.BuildConfig.VERSION_NAME}" +
        (currentScreen?.let { " · $it" } ?: "") + " · ${android.os.Build.MODEL}"

    if (sent) {
        LaunchedEffect(Unit) { delay(1100); onDone() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Thanks — we got it 🙏", style = UFont.serifItalic(22), color = c.ink)
            Text("It goes straight to the team.", style = UFont.sans(13), color = c.ink3)
        }
        return
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bugs, ideas, anything — straight to the team.", style = UFont.sans(13), color = c.ink2)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CATEGORIES.forEach { (key, label) ->
                val sel = category == key
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) c.ink else c.surface)
                        .then(if (sel) Modifier else Modifier.border(1.dp, c.line2, RoundedCornerShape(999.dp)))
                        .selectable(selected = sel, role = Role.Button) { category = key }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = UFont.sans(12, FontWeight.Medium), color = if (sel) c.bg else c.ink3) }
            }
        }

        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(14.dp)) {
            BasicTextField(
                value = body, onValueChange = { if (!sending) body = it },
                textStyle = UFont.sans(15).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                decorationBox = { inner -> if (body.isEmpty()) Text("What's on your mind?", style = UFont.sans(15), color = c.ink3); inner() },
            )
        }

        Text(ctx, style = UFont.mono(10), color = c.ink4)
        error?.let { Text(it, style = UFont.sans(12), color = c.coralDeep) }

        Box(Modifier.padding(top = 2.dp)) {
            UButton(if (sending) "Sending…" else "Send", kind = ButtonKind.CORAL, fill = false, enabled = body.isNotBlank() && !sending) {
                scope.launch {
                    sending = true; error = null
                    val ok = vm.sendFeedback(body, category, currentScreen)
                    sending = false
                    if (ok) sent = true else error = "Couldn't send — check your connection and try again."
                }
            }
        }
    }
}
