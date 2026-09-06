package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.AssistantSuggestion
import tech.csalliance.unstuck.core.logic.SuggestionGroups
import tech.csalliance.unstuck.core.logic.buildCheckin
import tech.csalliance.unstuck.core.logic.buildSuggestions
import tech.csalliance.unstuck.core.logic.fmtHrs
import tech.csalliance.unstuck.core.logic.pickStartNext
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import java.time.Instant
import java.time.ZoneId

// The assistant "cockpit" — port of components/assistant/assistant-home.tsx.
// The context strip proves the assistant already knows the day (Start-Next pick,
// usable time, paused session) before a word is typed; the chips are DYNAMIC,
// built from the user's real tasks/lists via core/logic/AssistantSuggestions and
// hidden when not applicable. Everything here is computed from the local stores:
// the LLM is only called when the user actually sends something.

/** Where a context-strip chip — or the assistant's `open_screen` tool — jumps
 *  to. Resolved by MainScaffold. [screen] is the contract's vocabulary (today |
 *  tasks | calendar | week | month | focus | insights | lists | captures |
 *  settings | people | notifications) and [id] an optional task / list id; the
 *  three named constants are the strip's own jumps. */
data class AssistantDestination(val screen: String, val id: String? = null) {
    companion object {
        val TASKS = AssistantDestination("tasks")
        val CALENDAR = AssistantDestination("calendar")
        val FOCUS = AssistantDestination("focus")
        /** The contract's 12 screens. */
        val SCREENS = listOf("today", "tasks", "calendar", "week", "month", "focus", "insights", "lists", "captures", "settings", "people", "notifications")
    }
}

data class AssistantPanelContext(
    val nextName: String? = null,
    val usableLabel: String? = null,
    val pausedName: String? = null,
    val groups: SuggestionGroups = SuggestionGroups(),
    /** Grounded daily-check-in line (client-built, zero tokens). */
    val checkinLine: String = "",
)

/** Everything the panel knows without asking the model. Recomputed only when an
 *  input that actually feeds it changes. */
@Composable
fun rememberAssistantContext(vm: AppViewModel, nowMs: Long): AssistantPanelContext {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val displayName by vm.currentNameState.collectAsStateWithLifecycle()

    return remember(tasks, blocks, collections, live, displayName, nowMs) {
        val liveTaskId = live?.taskId
        val next = pickStartNext(tasks, blocks, liveTaskId)
        val pausedName = live?.takeIf { it.paused }?.let { l -> tasks.firstOrNull { it.id == l.taskId }?.name }
        val todayIso = Clock.dateIso(nowMs)
        val usableMins = usableToday(blocks, todayIso).usableMins
        val usableLabel = if (usableMins > 0) fmtHrs(usableMins) else null
        val openTodayCount = blocks.count { it.date == todayIso && !it.done && !it.skipped }
        // NOT ui.components.greetingName: that falls back to "Unstuck", and
        // "Morning, Unstuck." would be nonsense. No name → no name.
        val firstName = displayName?.trim()?.split(' ', '\t', '\n', '.', '_', '-')
            ?.firstOrNull { it.isNotBlank() }
        val hour = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour
        AssistantPanelContext(
            nextName = next?.name,
            usableLabel = usableLabel,
            pausedName = pausedName,
            groups = buildSuggestions(tasks, blocks, collections, todayIso),
            checkinLine = buildCheckin(firstName, openTodayCount, usableLabel, hour),
        )
    }
}

/** Pinned context band under the header — each piece is a real jump:
 *  NEXT → Tasks, USABLE → Calendar, PAUSED → the live focus session.
 *  Renders nothing when there's nothing to show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextStrip(ctx: AssistantPanelContext, onNavigate: (AssistantDestination) -> Unit) {
    val c = UTheme.colors
    val pieces = buildList {
        ctx.nextName?.let { add(Triple("NEXT", it, AssistantDestination.TASKS)) }
        ctx.usableLabel?.let { add(Triple("USABLE", it, AssistantDestination.CALENDAR)) }
        ctx.pausedName?.let { add(Triple("PAUSED", it, AssistantDestination.FOCUS)) }
    }
    if (pieces.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().background(c.bg2).padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        pieces.forEachIndexed { i, (key, value, dest) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) {
                    Text("·", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open $key") { onNavigate(dest) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionLabel(key)
                    Text(
                        value, style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, chips: List<AssistantSuggestion>, onAsk: (String) -> Unit) {
    if (chips.isEmpty()) return
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, modifier = Modifier.padding(bottom = 10.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { chip ->
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface)
                        .border(1.dp, c.line2, RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button) { onAsk(chip.message) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text(chip.label, style = UFont.sans(13, FontWeight.Medium), color = c.ink) }
            }
        }
    }
}

/** The suggestions block. Full (serif hero + chips) as the empty thread's "first
 *  page"; [compact] (chips only) as the check-in card at the tail of an existing
 *  conversation — the thing the sheet opens onto. */
@Composable
fun AssistantHome(
    ctx: AssistantPanelContext,
    onAsk: (String) -> Unit,
    compact: Boolean = false,
    undoAllCount: Int = 0,
    onUndoAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = UTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 8.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp),
    ) {
        if (!compact) {
            Column {
                Text("What can I take off your plate?", style = UFont.serifItalic(24), color = c.ink)
                Text(
                    "I already know your day. Tell me what you want to happen — I'll do it, not just talk about it.",
                    style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (undoAllCount > 0) {
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2)
                    .border(1.dp, c.line2, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { onUndoAll() }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "↩ Undo all $undoAllCount change${if (undoAllCount == 1) "" else "s"}",
                    style = UFont.sans(13, FontWeight.Medium), color = c.ink2,
                )
            }
        }
        ChipGroup("Getting started", ctx.groups.gettingStarted, onAsk)
        ChipGroup("Plan & schedule", ctx.groups.planAndSchedule, onAsk)
        ChipGroup("Refine", ctx.groups.refine, onAsk)
        // A brand-new account has no data to build chips from — say so plainly
        // instead of showing an empty, broken-looking page.
        if (!compact && ctx.groups.isEmpty) {
            Text(
                "Add a task or two and I'll start suggesting things I can do for you.",
                style = UFont.sans(13), color = c.ink3,
            )
        }
    }
}
