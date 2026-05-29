package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * Tag picker — mirrors the web TagPicker: selected tags render as removable
 * "#name" chips; a "+ Tag" button opens a search-filtered dropdown over the
 * existing vocabulary (toggle on/off) with inline "Create" for a new tag.
 */
@Composable
fun TagPicker(vm: AppViewModel, selected: List<String>, onChange: (List<String>) -> Unit) {
    val c = UTheme.colors
    val vocab by vm.tags.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        selected.forEach { name ->
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).clickable { onChange(selected - name) }.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("#$name", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep)
                Text("✕", style = UFont.sans(11), color = c.primaryDeep)
            }
        }
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp)).clickable { open = true; query = "" }.padding(horizontal = 11.dp, vertical = 5.dp),
            ) { Text("+ Tag", style = UFont.sans(12, FontWeight.Medium), color = c.ink2) }

            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Box(Modifier.widthIn(min = 200.dp).padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.bg2).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, textStyle = UFont.sans(13).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner -> if (query.isEmpty()) Text("Search or create…", style = UFont.sans(13), color = c.ink3); inner() },
                    )
                }
                val matches = vocab.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                matches.forEach { tag ->
                    val on = tag.name in selected
                    Row(
                        Modifier.widthIn(min = 200.dp).clickable {
                            onChange(if (on) selected - tag.name else selected + tag.name)
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (on) "✓" else " ", style = UFont.sans(13), color = c.primaryDeep)
                        Text("#${tag.name}", style = UFont.sans(13), color = c.ink, modifier = Modifier.weight(1f))
                    }
                }
                val q = query.trim()
                if (q.isNotEmpty() && vocab.none { it.name.equals(q, ignoreCase = true) }) {
                    Row(
                        Modifier.widthIn(min = 200.dp).clickable {
                            onChange(selected + vm.ensureTag(q)); query = ""
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { Text("Create \"$q\"", style = UFont.sans(13, FontWeight.Medium), color = c.primaryDeep) }
                }
            }
        }
    }
}
