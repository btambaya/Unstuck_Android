package tech.csalliance.unstuck.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** Indigo-tinted modal-sheet scrim — rgba(20,18,40,0.30). */
val SheetScrim = Color(0x4D141228)

enum class Leading { MENU, BACK, NONE }

/** M3 small top app bar — transparent over bg, 40dp round icon buttons. */
@Composable
fun AppBar(
    title: String = "",
    leading: Leading = Leading.MENU,
    trailingSearch: Boolean = true,
    dark: Boolean = false,
    onLeading: () -> Unit = {},
    onSearch: () -> Unit = {},
    avatarInitials: String? = null,
    onAvatar: (() -> Unit)? = null,
) {
    val c = UTheme.colors
    val iconTint = if (dark) Color.White else c.ink2
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != Leading.NONE) {
            BarIcon(if (leading == Leading.MENU) Icons.Outlined.Menu else Icons.AutoMirrored.Outlined.ArrowBack, iconTint, onLeading)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = UFont.sans(16, FontWeight.Medium).copy(letterSpacing = (-0.1).sp),
            color = if (dark) Color.White else c.ink,
        )
        if (trailingSearch) BarIcon(Icons.Outlined.Search, iconTint, onSearch)
        if (onAvatar != null) {
            Box(
                Modifier.size(40.dp).padding(4.dp).clip(CircleShape).background(c.greenSoft).clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) { Text(avatarInitials ?: "U", style = UFont.sans(12, FontWeight.SemiBold), color = c.greenInk) }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

data class NavSpec(val key: String, val label: String, val icon: ImageVector)

/** M3 bottom nav with a filled bg2 pill active indicator + a floating coral FAB. */
@Composable
fun BottomNavBar(
    items: List<NavSpec>,
    activeKey: String,
    onSelect: (String) -> Unit,
    onFab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = UTheme.colors
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(c.bg).padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom,
        ) {
            // top hairline divider
            NavCell(items[0], activeKey, onSelect)
            NavCell(items[1], activeKey, onSelect)
            Box(Modifier.width(56.dp)) {} // FAB gap
            NavCell(items[2], activeKey, onSelect)
            NavCell(items[3], activeKey, onSelect)
        }
        // 0.5px top divider
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line).align(Alignment.TopCenter))
        // Floating FAB, centered, lifted above the bar.
        CoralFab(onFab, Modifier.align(Alignment.TopCenter).offset(y = (-28).dp))
    }
}

@Composable
private fun RowScope.NavCell(item: NavSpec, activeKey: String, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    val active = item.key == activeKey
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(item.key) }.padding(top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) c.bg2 else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Icon(item.icon, contentDescription = item.label, tint = if (active) c.ink else c.ink3, modifier = Modifier.size(20.dp))
        }
        Text(item.label, style = UFont.sans(11, if (active) FontWeight.SemiBold else FontWeight.Medium), color = if (active) c.ink else c.ink3)
    }
}

/** 56×56, 16dp rounded-square coral FAB. */
@Composable
fun CoralFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Box(
        modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.coral).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Add, contentDescription = "New", tint = Color.White, modifier = Modifier.size(24.dp)) }
}

/** 32×4 drag handle for bottom sheets. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(999.dp)).background(UTheme.colors.line2))
}
