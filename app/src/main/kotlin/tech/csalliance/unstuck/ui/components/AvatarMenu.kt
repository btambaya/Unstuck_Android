package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarMenu(vm: AppViewModel, onInsights: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim, dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.greenSoft), contentAlignment = Alignment.Center) {
                    Text("UN", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                }
                Column(Modifier.weight(1f)) {
                    Text("Unstuck", style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                    Text(vm.auth?.currentUserId?.let { "Signed in" } ?: "unstuck@kazaure.com", style = UFont.sans(11), color = c.ink3)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            MenuRow("Insights", c.ink, onInsights)
            MenuRow("Settings", c.ink, onSettings)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line).padding(horizontal = 20.dp))
            MenuRow("Sign out", c.ink3) { vm.signOut(); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(label, style = UFont.sans(14), color = color, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp))
}
