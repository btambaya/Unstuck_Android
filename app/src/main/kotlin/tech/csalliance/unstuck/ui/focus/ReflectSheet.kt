package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.component.RadioOptionRow
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** End-of-session reflection — centered modal dialog over a scrim. */
@Composable
fun ReflectSheet(elapsedSec: Int, onDismiss: () -> Unit) {
    val c = UTheme.colors
    var sel by remember { mutableStateOf<String?>(null) }
    val options = listOf(
        "flow" to ("It flowed." to c.greenSoft),
        "okay" to ("It was OK." to c.blueSoft),
        "sticky" to ("It was sticky." to c.amberSoft),
        "stopped" to ("I had to stop." to c.coralSoft),
    )
    Box(Modifier.fillMaxSize().background(SheetScrim).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).clickable(enabled = false) {}.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("SESSION COMPLETE · ${(elapsedSec / 60.0).let { Math.round(it).toInt() }}M", color = c.primaryDeep)
            Text("How did that land?", style = UFont.serifItalic(24), color = c.ink)
            options.forEach { (key, v) -> RadioOptionRow(v.first, sel == key, v.second) { sel = key } }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Skip", style = UFont.sans(13), color = c.ink3, modifier = Modifier.clickable(onClick = onDismiss))
                Box(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.ink).clickable(onClick = onDismiss).padding(horizontal = 18.dp, vertical = 9.dp)) {
                    Text("Save", style = UFont.sans(13, FontWeight.SemiBold), color = c.bg)
                }
            }
        }
    }
}
