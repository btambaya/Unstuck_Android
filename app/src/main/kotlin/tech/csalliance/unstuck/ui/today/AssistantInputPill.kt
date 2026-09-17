package tech.csalliance.unstuck.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// The way into the assistant from Today — Android port of iOS
// App/Features/TodayFeature.swift `AssistantInputPill` (2026-09-17). ONE pill
// directly under the week pill: "✦ Ask, plan, or brain-dump…" with the mic on
// the right, drawn exactly like the composer the old gateway card carried
// (surface capsule, coral ring, ✦ leading glyph, mic + faded send). Tapping the
// field (or the arrow) opens the Assistant sheet with focus in ITS composer;
// the mic starts realtime Talk. No typing happens here. The caller gates on the
// AI kill-switch (nothing renders while the assistant is off).

/** The pill's placeholder — verbatim from iOS / the web. */
const val ASSISTANT_PILL_PLACEHOLDER = "Ask, plan, or brain-dump…"

/**
 * @param onTalk the mic — the host presents VoiceModeScreen, exactly as the
 *   Assistant sheet does for its Talk pill.
 */
@Composable
fun AssistantInputPill(vm: AppViewModel, onTalk: () -> Unit, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    val open = { vm.openAssistant(focusComposer = true) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp)).background(c.surface)
            .border(1.5.dp, c.coral.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(start = 13.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "Opens the assistant", onClick = open)
                .semantics { contentDescription = "Ask the assistant" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.coral, modifier = Modifier.size(15.dp))
            Text(ASSISTANT_PILL_PLACEHOLDER, style = UFont.sans(14), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vm.voiceConfigured()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onTalk)
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Talk to your assistant" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = c.coral, modifier = Modifier.size(18.dp)) }
        }
        // The send affordance of the composer it replaces — the empty-field look
        // (nothing to send yet); it opens the assistant like the field. Hidden
        // from TalkBack: the field already carries the one spoken label.
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = open)
                .minimumInteractiveComponentSize()
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(c.coral.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}
