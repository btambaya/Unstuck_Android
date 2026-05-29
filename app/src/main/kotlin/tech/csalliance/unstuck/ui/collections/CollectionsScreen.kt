package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@Composable
fun CollectionsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit, onMenu: () -> Unit) {
    val c = UTheme.colors
    val collections by vm.collections.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Collections", leading = Leading.MENU, onLeading = onMenu, onSearch = onSearch)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Column {
                    Text("Things you don't need to remember.", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                    Text("A calm shelf. Nothing here is a task.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                }
            }
            items(collections.sortedBy { it.sortOrder }, key = { it.id }) { col ->
                val color = c.areaColor(col.color)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 130.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).clickable { onOpen(col.id) }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        ColorChip(color, box = 26, dot = 8)
                        Text("${col.items.size}", style = UFont.sans(11), color = c.ink3)
                    }
                    Text(col.name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                    Column(Modifier.weight(1f, fill = false).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        col.items.take(2).forEach { item ->
                            Text("· ${item.body}", style = UFont.sans(11), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { Box(Modifier.padding(28.dp)) {} }
        }
    }
}
