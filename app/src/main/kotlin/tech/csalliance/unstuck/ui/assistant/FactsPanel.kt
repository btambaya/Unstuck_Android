package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.RITUAL_LABELS
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// "What Unstuck knows" — every fact the assistant has learned, visible and
// deletable (port of iOS App/Features/FactsPanel.swift and the web's
// components/settings/facts-panel.tsx). Memory transparency is part of the
// same consent surface as the AI toggle (docs/ai-gateway-brainstorm.md):
// nothing is stored silently, and forgetting is immediate, everywhere
// (soft-delete tombstones sync through profile_facts).
//
// Settings → "What Unstuck knows". Lists each ACTIVE fact with its category
// chip + date (the date it refers to when set, else when it was last
// updated), edit-in-place, forget one, "Forget everything" (confirmed), an
// add row, and the four ritual toggles (which recurring moments run).

/** Every user-facing string of the panel, verbatim from iOS / the web. */
object FactsPanelCopy {
    const val EYEBROW = "Settings · Memory"
    const val TITLE = "What Unstuck knows about you."
    const val NAV_TITLE = "What Unstuck knows"
    const val DISCLOSURE =
        "The assistant’s memory — built from your answers and conversations. These facts are shared with our AI provider (which doesn’t train on them) so it can personalise your help. Delete anything; it forgets immediately, everywhere."
    const val EMPTY = "Nothing yet — it learns as you talk to it."
    const val ADD_PLACEHOLDER = "Add something it should know…"
    const val ADD = "Add"
    const val CATEGORY_LABEL = "Fact category"
    const val FORGET_ALL = "Forget everything"
    const val FORGET_ALL_A11Y = "Forget everything the assistant has learned"
    const val FORGET_ALL_TITLE = "Forget everything?"
    const val FORGET_ALL_MESSAGE = "Forget everything the assistant has learned about you? This can’t be undone."
    const val CANCEL = "Cancel"
    const val MOMENTS = "Moments it runs for you"
    const val TONE_NOTE = "Your tone is derived from these facts — tell it “keep me honest” or “gently” and it adapts. No separate dial."
    const val EDIT_PLACEHOLDER = "The fact"
    const val SAVE = "Save"
    /** A rejected/failed edit is SAID, never swallowed — the old text is still
     *  on screen because the row was never removed. Same words as the
     *  interview's failed save (InterviewCopy.SAVE_FAILED). */
    const val EDIT_FAILED = "Couldn’t save that — try again"
    fun editTitle(f: ProfileFact) = "Edit · ${f.category.raw}"
    fun editA11y(f: ProfileFact) = "Edit fact: ${f.fact}"
    fun forgetA11y(f: ProfileFact) = "Forget \"${f.fact}\""
}

/** "for 12 Sept" (the date the fact is about) or "12 Sept" (last updated) —
 *  iOS `FactDate.label`. Pure; `nowMs` only decides whether the year shows. */
object FactDate {
    private val short = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val withYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

    fun label(f: ProfileFact, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        f.whenIso?.let { parseYmd(it) }?.let { d ->
            return "for " + (if (d.year == today.year) short else withYear).format(d)
        }
        Time.parseMillis(f.updatedAt)?.let { ms ->
            val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            return (if (d.year == today.year) short else withYear).format(d)
        }
        return ""
    }

    private fun parseYmd(s: String): LocalDate? {
        val p = s.take(10).split('-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        return runCatching { LocalDate.of(p[0], p[1], p[2]) }.getOrNull()
    }
}

/** The full Settings sub-screen (own app bar), for a host that routes it as
 *  its own destination. [FactsPanelContent] is the body alone, for a host that
 *  places it inside `SettingsSubScreen`'s scaffold. */
@Composable
fun FactsPanelScreen(host: FactsHost, onBack: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = FactsPanelCopy.NAV_TITLE, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
            SectionLabel(FactsPanelCopy.EYEBROW, color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
            Text(FactsPanelCopy.TITLE, style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            FactsPanelContent(host)
            Box(Modifier.padding(24.dp)) {}
        }
    }
}

@Composable
fun FactsPanelContent(host: FactsHost, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val facts by host.profileFacts.collectAsStateWithLifecycle()
    val rituals by host.rituals.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProfileFact?>(null) }
    var confirmForgetAll by remember { mutableStateOf(false) }
    /** Set when the last edit didn't land — the row is untouched, so this is
     *  the only sign the user gets. Cleared by the next edit attempt. */
    var editError by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        Text(FactsPanelCopy.DISCLOSURE, style = UFont.sans(12).copy(lineHeight = 17.sp), color = c.ink3, modifier = Modifier.padding(bottom = 12.dp))

        Card {
            if (facts.isEmpty()) {
                Text(
                    FactsPanelCopy.EMPTY, style = UFont.serifItalic(13), color = c.ink3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                facts.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    FactRow(
                        f = f,
                        onEdit = { editing = f },
                        onForget = { scope.launch { host.forgetProfileFact(f.id) } },
                    )
                }
            }
            Divider()
            AddRow { category, text -> scope.launch { host.saveProfileFact(category, text, ProfileFactSource.SETTINGS, null) } }
        }

        editError?.let { err ->
            Text(
                err, style = UFont.sans(12), color = c.coralDeep,
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (facts.isNotEmpty()) {
            Text(
                FactsPanelCopy.FORGET_ALL, style = UFont.sans(12, FontWeight.Medium), color = c.red,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = FactsPanelCopy.FORGET_ALL_A11Y) { confirmForgetAll = true }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }

        Text(FactsPanelCopy.MOMENTS, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Card {
            RITUAL_LABELS.forEachIndexed { i, r ->
                RitualToggleRow(label = r.label, sub = r.sub, value = rituals[r.key], last = i == RITUAL_LABELS.lastIndex) { on ->
                    host.setRitual(r.key, on)
                }
            }
        }
        Text(FactsPanelCopy.TONE_NOTE, style = UFont.sans(12).copy(lineHeight = 17.sp), color = c.ink2, modifier = Modifier.padding(top = 10.dp))
    }

    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text(FactsPanelCopy.FORGET_ALL_TITLE, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text(FactsPanelCopy.FORGET_ALL_MESSAGE, style = UFont.sans(13), color = c.ink2) },
            confirmButton = {
                TextButton(onClick = { confirmForgetAll = false; scope.launch { host.forgetAllProfileFacts() } }) {
                    Text(FactsPanelCopy.FORGET_ALL, color = c.red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForgetAll = false }) { Text(FactsPanelCopy.CANCEL, color = c.ink2) } },
            containerColor = c.surface,
        )
    }

    editing?.let { f ->
        FactEditDialog(
            fact = f,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                if (text != f.fact) {
                    // Edit = rewrite the row IN PLACE (same id, one outbox op).
                    // The old forget+re-save minted a NEW id, so every moment
                    // dismissed against this fact (dates-that-matter:<id>:<date>)
                    // re-fired — and a save that was rejected or dropped lost the
                    // fact outright, because the forget had already committed.
                    // updateProfileFact falls back to a fresh save only when the
                    // row has vanished (forgotten on another device).
                    // iOS FactsPanel.swift:88-93.
                    scope.launch {
                        editError = null
                        val r = host.updateProfileFact(f.id, text, f.whenIso)
                        if (r.isFailure) editError = FactsPanelCopy.EDIT_FAILED
                    }
                }
            },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(UTheme.colors.line))
}

@Composable
private fun FactRow(f: ProfileFact, onEdit: () -> Unit, onForget: () -> Unit) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Text(
            f.category.raw.uppercase(), style = UFont.mono(9, FontWeight.SemiBold).copy(letterSpacing = 0.6.sp), color = c.coralDeep,
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).background(c.bg2).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Column(
            Modifier.weight(1f).padding(start = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = FactsPanelCopy.editA11y(f)) { onEdit() }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(f.fact, style = UFont.sans(13).copy(lineHeight = 19.sp), color = c.ink)
            Text(FactDate.label(f), style = UFont.sans(11), color = c.ink3)
        }
        Icon(
            Icons.Filled.Close, contentDescription = FactsPanelCopy.forgetA11y(f), tint = c.ink3,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { onForget() }.minimumInteractiveComponentSize().size(16.dp),
        )
    }
}

@Composable
private fun AddRow(onAdd: (ProfileFactCategory, String) -> Unit) {
    val c = UTheme.colors
    var draft by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ProfileFactCategory.CONTEXT) }
    var menu by remember { mutableStateOf(false) }
    val can = draft.isNotBlank()
    fun add() {
        val t = draft.trim()
        if (t.isEmpty()) return
        onAdd(category, t)
        draft = ""
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.bg2)
                    .clickable(role = Role.DropdownList, onClickLabel = FactsPanelCopy.CATEGORY_LABEL) { menu = true }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(category.raw, style = UFont.sans(12), color = c.ink2)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.ink2, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ProfileFactCategory.entries.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat.raw.replaceFirstChar { it.uppercase() }, style = UFont.sans(14), color = c.ink) },
                        onClick = { category = cat; menu = false },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.bg2).padding(horizontal = 10.dp, vertical = 8.dp)) {
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                textStyle = UFont.sans(13).copy(color = c.ink), cursorBrush = SolidColor(c.ink), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text(FactsPanelCopy.ADD_PLACEHOLDER, style = UFont.sans(13), color = c.ink3)
                    inner()
                },
            )
        }
        Text(
            FactsPanelCopy.ADD, style = UFont.sans(12, FontWeight.SemiBold), color = if (can) Color.White else c.ink3,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp)).background(if (can) c.coral else c.bg2)
                .clickable(enabled = can, role = Role.Button) { add() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Label + sub + switch — one ritual (Settings variant of the interview chips). */
@Composable
private fun RitualToggleRow(label: String, sub: String, value: Boolean, last: Boolean, onChange: (Boolean) -> Unit) {
    val c = UTheme.colors
    // The whole row is the switch for TalkBack ("<label>, switch, on") — the
    // inner pill is decorative so it doesn't surface as a second nameless toggle.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "$label. $sub" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text(sub, style = UFont.sans(12), color = c.ink3)
        }
        MdToggle(value, onChange, Modifier.clearAndSetSemantics {})
    }
    if (!last) Divider()
}

/** Edit one fact's text (category + date stay). Save re-stores it. */
@Composable
private fun FactEditDialog(fact: ProfileFact, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = UTheme.colors
    var value by remember(fact.id) { mutableStateOf(fact.fact) }
    val can = value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FactsPanelCopy.editTitle(fact), style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                placeholder = { Text(FactsPanelCopy.EDIT_PLACEHOLDER, style = UFont.sans(15), color = c.ink3) },
                textStyle = UFont.sans(15).copy(color = c.ink), minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = can, onClick = { val t = value.trim(); if (t.isNotEmpty()) onSave(t) }) {
                Text(FactsPanelCopy.SAVE, color = if (can) c.coral else c.ink3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(FactsPanelCopy.CANCEL, color = c.ink2) } },
        containerColor = c.surface,
    )
}
