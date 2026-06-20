package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private val TAGS = listOf(
    CaptureTag.FOLLOW_UP to "follow-up", CaptureTag.IDEA to "idea", CaptureTag.EDIT to "edit",
    CaptureTag.QUESTION to "question", CaptureTag.DISTRACTION to "distraction",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(vm: AppViewModel, task: TaskItem, sessionId: String?, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saveable so a typed capture + chosen tag survive rotation (CaptureTag is a
    // Serializable enum; String saves directly).
    var text by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf(CaptureTag.FOLLOW_UP) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim, dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CAPTURE · STAYS ATTACHED", style = UFont.mono(11, FontWeight.Medium), color = c.ink3)
            BasicTextField(value = text, onValueChange = { text = it }, textStyle = UFont.sans(19, FontWeight.Medium).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), decorationBox = { inner -> if (text.isEmpty()) Text("What just popped up?", style = UFont.sans(19, FontWeight.Medium), color = c.ink4); inner() })
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TAGS.forEach { (t, label) ->
                    val sel = tag == t
                    // Each tag carries its own soft-bg / dark-ink color pair (per the mockup).
                    val (selBg, selFg) = when (t) {
                        CaptureTag.FOLLOW_UP -> c.primarySoft to c.primaryDeep
                        CaptureTag.IDEA -> c.amberSoft to c.amberInk
                        CaptureTag.EDIT -> c.blueSoft to c.blueInk
                        CaptureTag.QUESTION -> c.greenSoft to c.greenInk
                        CaptureTag.DISTRACTION -> c.coralSoft to c.coralDeep
                        else -> c.primarySoft to c.primaryDeep
                    }
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) selBg else c.surface).then(if (sel) Modifier else Modifier.border(1.dp, c.line2, RoundedCornerShape(999.dp))).clickable { tag = t }.padding(horizontal = 11.dp, vertical = 5.dp)) {
                        Text(label, style = UFont.sans(12, FontWeight.Medium), color = if (sel) selFg else c.ink3)
                    }
                }
            }
            Box(Modifier.padding(top = 4.dp)) {
                tech.csalliance.unstuck.design.component.UButton("Save", kind = tech.csalliance.unstuck.design.component.ButtonKind.DARK, fill = false) {
                    // Resolve the session id at SAVE time: on a freshly started session the
                    // `live` row can still be null when the sheet opened (so the passed-in
                    // sessionId is null), which would orphan the capture from the Session the
                    // interruption histogram keys on. Fall back to the now-current live id.
                    val sid = sessionId ?: vm.liveSession.value?.id
                    if (text.isNotBlank()) vm.saveCapture(task.id, sid, tag, text.trim()); onDismiss()
                }
            }
        }
    }
}
