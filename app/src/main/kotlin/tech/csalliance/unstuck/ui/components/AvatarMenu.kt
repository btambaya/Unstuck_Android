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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * Account menu, anchored to the top-right next to the avatar initials (a popup
 * card, not a bottom sheet — tapping the avatar should drop the menu down from
 * it). Dismisses on outside tap / back.
 */
@Composable
fun AvatarMenu(vm: AppViewModel, onInsights: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val name = vm.currentName ?: "Your account"
    val email = vm.currentEmail ?: "Signed in"
    val initials = name.split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    // Sit just below the avatar in the top bar (status bar + bar height ≈ 64dp),
    // inset a touch from the right edge. Window-level, so it ignores statusBarsPadding.
    val offset = with(LocalDensity.current) { IntOffset(x = (-6).dp.roundToPx(), y = 64.dp.roundToPx()) }
    Popup(alignment = Alignment.TopEnd, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.padding(end = 2.dp).width(248.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).background(c.surface)
                .padding(vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.greenSoft), contentAlignment = Alignment.Center) {
                    Text(initials, style = UFont.sans(12, FontWeight.SemiBold), color = c.greenInk)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1)
                    Text(email, style = UFont.sans(11), color = c.ink3, maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            MenuRow("Insights", c.ink) { onInsights(); onDismiss() }
            MenuRow("Settings", c.ink) { onSettings(); onDismiss() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            MenuRow("Sign out", c.ink3) { vm.signOut(); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(label: String, color: Color, onClick: () -> Unit) {
    Text(label, style = UFont.sans(14), color = color, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp))
}
