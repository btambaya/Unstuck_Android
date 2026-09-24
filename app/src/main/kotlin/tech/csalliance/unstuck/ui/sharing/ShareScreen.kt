package tech.csalliance.unstuck.ui.sharing

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.HAND_OVER_EXPLAINER
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.ShareItemKind
import tech.csalliance.unstuck.core.logic.SharePendingRow
import tech.csalliance.unstuck.core.logic.SharePersonRow
import tech.csalliance.unstuck.core.logic.sharePeopleCandidates
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// The ONE Share screen (unified sharing v1 — docs/unified-sharing-spec.md §2 /
// §4) for tasks AND collections — the Android port of iOS App/Features/
// ShareScreen.swift. Replaces the old per-task ShareTaskSheet (Off/View/
// Partner/Assign) and ShareCollectionSheet (email + Can edit/Can view) with a
// single surface and a single vocabulary:
//
//   Share · <item name>            [Can edit | Can view]   (default Can edit)
//   PEOPLE        ONLY the people who already have the item, each a row with
//                 its access menu (Can edit / Can view / Report / Block /
//                 Remove), plus
//                 one "Choose someone · N" row that opens a SEARCHABLE picker
//                 of everyone else. The whole roster is never listed inline
//                 (Ahmad, 2026-09-17: "ten people is a wall").
//   SOMEONE NEW   email + Share — an existing account is shared with at once
//                 (the server pushes them), anyone else gets an invite email
//                 that is claimed when they sign up. Pending invites listed.
//   SHARE A LINK  a one-shot join link (clipboard + the Android share sheet)
//                 that connects whoever opens it AND grants the item in one step.
//
// Under the button, the line is TRUE to what the server did ("Shared with
// Maya — they can edit." vs "Invite sent to x@y — waiting for them to sign
// up." vs "Link copied — …"), and refusals are shown ("That's you.", "You've
// blocked that person.", the rate-limit copy).
//
// "Hand over to…" (level `assign`) is the same people picker in HAND_OVER
// mode — no grade control, one button per person, and the explainer "it
// becomes their task; you keep view".
//
// PRE-CREATE (ShareTarget.NewTask) is this same screen opened from the New
// task sheet's one "Share with…" row, before the task exists: picks are held
// locally and handed back to the sheet, whose "Add task" applies them (the
// grade switch, the people card and the searchable picker are unchanged). A
// picked person's menu adds "Hand over" (the sheet always offered Assign) and
// drops Report / Block; "Someone new" is the circle invite the sheet's inline
// "Add someone" panel sent; Share a link and the pending invites are hidden —
// there is no task to link to or invite into yet.
//
// Colours (memory brand-colour-coral-only): selection is the app's black-and-
// white pair — a chosen grade / a person who holds the item is `ink` filled
// with `bg` text; unselected is `bg2` / `ink2` with a `line2` ring. Errors are
// the palette `red`; nothing here is rust or coral.

/** Test seam: the transport a [ShareScreen] talks through, in place of the
 *  live one ([LiveShareTransport]). Null (always, in the app) = live. Lets a
 *  test drive the REAL New task sheet → pre-create Share screen round trip
 *  against a fake roster. */
internal val LocalShareTransport = staticCompositionLocalOf<ShareScreenTransport?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    vm: AppViewModel,
    target: ShareTarget,
    mode: ShareMode = ShareMode.SHARE,
    /** PRE-CREATE ([ShareTarget.NewTask]) only: the New task sheet's picks, and
     *  where every change goes — live, so however the screen is closed (Done,
     *  swipe, back) the sheet already holds what was picked. */
    picks: Map<String, ShareLevel> = emptyMap(),
    onPicks: (Map<String, ShareLevel>) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val transport = LocalShareTransport.current
    val model = remember(target, mode) { ShareScreenModel(target, mode, transport ?: LiveShareTransport(vm), initialPicks = picks) }
    val s by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model) { model.load() }
    // Refresh on the live collab signals (a connection / share row of mine
    // changed — e.g. the invitee just joined): the roster + badge flows re-emit
    // on CollabRealtime's circleChanged / sharesChanged, so re-read then.
    val circle by vm.circle.collectAsStateWithLifecycle()
    val badges by vm.shareBadges.collectAsStateWithLifecycle()
    LaunchedEffect(model) { snapshotFlow { circle to badges }.drop(1).collect { model.load() } }
    // Pre-create: hand every change of the picks straight back to the sheet.
    val latestOnPicks by rememberUpdatedState(onPicks)
    LaunchedEffect(model) {
        if (model.preCreate) model.state.map { it.picks }.distinctUntilChanged().drop(1).collect { latestOnPicks(it) }
    }

    var showPicker by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<SharePersonRow?>(null) }
    /** The row whose "Block…" is awaiting its confirm. */
    var blockTarget by remember { mutableStateOf<SharePersonRow?>(null) }
    val handOver = mode == ShareMode.HAND_OVER
    val split = remember(s.people, s.pinnedIds, handOver) { sharePeopleSplit(s.people, s.pinnedIds, handOver) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        ShareScreenBody(
            model = model, s = s, onDone = onDismiss, onChoose = { showPicker = true },
            onReport = { reportTarget = it }, onBlock = { blockTarget = it },
            onManagePeople = { onDismiss(); vm.openDeepLink(MANAGE_PEOPLE_LINK) },
            modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
        )
    }

    // The searchable dropdown behind "Choose someone" — everyone you are
    // connected to who does NOT have the item yet.
    if (showPicker) {
        PeoplePickerSheet(
            title = if (handOver) "Hand over to" else "Share with",
            action = if (handOver) "Hand over" else "Share",
            people = split.candidates,
            onPick = { row -> showPicker = false; scope.launch { model.tap(row) } },
            onDismiss = { showPicker = false },
        )
    }

    reportTarget?.let { row ->
        val who = row.email ?: row.name
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("Report this person?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Send a report about $who to the Unstuck team. We review reports and take action.", style = UFont.sans(13), color = c.ink2)
                    listOf("Objectionable content", "Spam", "Harassment", "Other").forEach { reason ->
                        TextButton(onClick = {
                            reportTarget = null
                            scope.launch { vm.reportShareConcern(target.kind.noun, target.itemId, who, reason) }
                        }) { Text(reason, color = c.ink) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }

    // A block is server-side (migration 075) and cuts everything between you, so
    // it confirms first (parity with iOS build 79, audit 2026-09-22 C10).
    blockTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text("Block ${row.name}?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                Text(
                    "They won't be able to share tasks or lists with you, and everything shared between you stops. You can unblock them in Settings › People.",
                    style = UFont.sans(13), color = c.ink2,
                )
            },
            confirmButton = { TextButton(onClick = { blockTarget = null; scope.launch { model.block(row) } }) { Text("Block", color = c.red) } },
            dismissButton = { TextButton(onClick = { blockTarget = null }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}

/** The Share screen's content, outside its bottom sheet (so a render test can
 *  draw it). Every mode shares it; PRE-CREATE ([ShareScreenModel.preCreate])
 *  hides what needs a real task — Share a link, the pending email invites,
 *  Report and Block — adds "Hand over" to a picked person's menu, and turns
 *  "Someone new" into the circle invite the New task sheet always had. */
@Composable
internal fun ShareScreenBody(
    model: ShareScreenModel,
    s: ShareScreenState,
    onDone: () -> Unit,
    onChoose: () -> Unit,
    onReport: (SharePersonRow) -> Unit,
    onBlock: (SharePersonRow) -> Unit,
    modifier: Modifier = Modifier,
    /** Settings › People (main's slim settings: "Manage people" on every share sheet). */
    onManagePeople: (() -> Unit)? = null,
) {
    val c = UTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val target = model.target
    val handOver = model.mode == ShareMode.HAND_OVER
    val preCreate = model.preCreate
    val split = remember(s.people, s.pinnedIds, handOver) { sharePeopleSplit(s.people, s.pinnedIds, handOver) }
    val anyBusy = s.busyId != null

    /** Copy the link + hand it to the system share sheet. */
    fun shareLink(url: String) {
        clipboard.setText(AnnotatedString(url))
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
        runCatching { context.startActivity(Intent.createChooser(send, "Share a link")) }
    }
    // Pre-create: a circle-invite link is copied the moment it's made (the
    // sheet's old inline panel did the same) and shown under "Someone new".
    LaunchedEffect(s.lastLink) { if (preCreate) s.lastLink?.let { clipboard.setText(AnnotatedString(it)) } }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ── nav row: title + Done ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (handOver) "Hand over" else "Share", style = UFont.serifItalic(18), color = c.ink, modifier = Modifier.weight(1f))
            Text(
                "Done", style = UFont.sans(14, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onDone).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // ── header: the item + what sharing means ──
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                target.name.ifBlank { if (preCreate) "New task" else if (target.kind == ShareItemKind.TASK) "Untitled task" else "Untitled list" },
                style = UFont.serifItalic(22), color = c.ink,
            )
            Text(
                when {
                    handOver -> HAND_OVER_EXPLAINER
                    preCreate -> "Anyone you pick gets this task in their “Shared with you” once you add it."
                    else -> "Anyone you share with sees this ${target.kind.noun} in their “Shared with you”."
                },
                style = UFont.sans(13), color = c.ink2,
            )
        }

        // ── grade: Can edit / Can view (the app's ink/bg selection pair) ──
        if (!handOver) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareAccess.entries.forEach { a ->
                        val on = s.access == a
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (on) c.ink else c.bg2)
                                .border(1.dp, if (on) c.ink else c.line2, RoundedCornerShape(999.dp))
                                .selectable(selected = on, role = Role.RadioButton) { model.setAccess(a) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text(a.label, style = UFont.sans(12, FontWeight.SemiBold), color = if (on) c.bg else c.ink2) }
                    }
                }
                Text(s.access.blurb(target.kind), style = UFont.sans(12), color = c.ink3)
            }
        }

        // ── the honest line — HIGH on the screen, never behind the keyboard ──
        s.error?.let { Text(it, style = UFont.sans(13, FontWeight.SemiBold), color = c.red) }
            ?: s.result?.let { Text("✓ $it", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk) }

        // ── PEOPLE: who has it (+ the menu) and one row to choose someone ──
        val noun = if (handOver) "Hand over to" else "People"
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(if (split.withAccess.isEmpty()) noun else "$noun · ${split.withAccess.size}")
            when {
                s.loading && s.people.isEmpty() -> Text("Loading…", style = UFont.sans(13), color = c.ink3)
                s.people.isEmpty() -> Text(
                    when {
                        handOver -> "No one to hand this to yet — connect with someone from the Share screen first."
                        preCreate -> "No one yet — invite someone below."
                        else -> "No one yet — add someone by email below, or share a link."
                    },
                    style = UFont.sans(13), color = c.ink3,
                )
                else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
                    split.withAccess.forEachIndexed { idx, row ->
                        if (idx > 0) CardDivider()
                        PersonRow(
                            row = row, handOver = handOver, access = s.access,
                            busy = s.busyId == row.id, anyBusy = anyBusy, preCreate = preCreate,
                            onTap = { scope.launch { model.tap(row) } },
                            onSetAccess = { next -> scope.launch { model.setAccess(row, next) } },
                            onHandOver = { model.handOver(row) },
                            onReport = { onReport(row) },
                            onBlock = { onBlock(row) },
                        )
                    }
                    if (split.candidates.isNotEmpty()) {
                        if (split.withAccess.isNotEmpty()) CardDivider()
                        ChooseRow(handOver = handOver, count = split.candidates.size, enabled = !anyBusy, onClick = onChoose)
                    }
                }
            }
        }

        if (!handOver && preCreate) {
            // ── SOMEONE NEW (pre-create): the circle invite — there's no task to
            // share by email yet. Email → added at once / emailed; blank → a link.
            val emailBusy = s.busyId == ShareScreenModel.EMAIL_BUSY_ID
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Someone new")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.email, onValueChange = model::setEmail,
                        placeholder = { Text("name@example.com (optional)", style = UFont.sans(14), color = c.ink3) },
                        singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = "Email address" },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { focus.clearFocus(); scope.launch { model.shareWithEmail() } }),
                    )
                    UButton(
                        when { emailBusy -> "Inviting…"; s.email.isBlank() -> "Get link"; else -> "Invite" },
                        kind = ButtonKind.DARK, fill = false, enabled = !emailBusy,
                    ) { focus.clearFocus(); scope.launch { model.shareWithEmail() } }
                }
                Text(
                    "Has an account? They join your people right away. No account yet? We email them an invite. Leave it blank for a link you send yourself.",
                    style = UFont.sans(12), color = c.ink3,
                )
                s.lastLink?.let { link ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            link, style = UFont.sans(12), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.bg2).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                        UButton("Copy link", kind = ButtonKind.DARK, fill = false) { clipboard.setText(AnnotatedString(link)) }
                    }
                }
            }
        } else if (!handOver) {
            // ── SOMEONE NEW: email + Share, then the pending invites ──
            val emailBusy = s.busyId == ShareScreenModel.EMAIL_BUSY_ID
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Someone new")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.email, onValueChange = model::setEmail,
                        placeholder = { Text("name@example.com", style = UFont.sans(14), color = c.ink3) },
                        singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = "Email address" },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { focus.clearFocus(); scope.launch { model.shareWithEmail() } }),
                    )
                    UButton(
                        if (emailBusy) "Sharing…" else "Share", kind = ButtonKind.DARK, fill = false,
                        enabled = !emailBusy && s.email.isNotBlank(),
                    ) { focus.clearFocus(); scope.launch { model.shareWithEmail() } }
                }
                Text(
                    "Has an account? They get it right away. No account yet? We email them an invite — it's theirs the moment they sign up.",
                    style = UFont.sans(12), color = c.ink3,
                )
                s.pending.forEach { p ->
                    PendingRow(p, busy = s.busyId == p.id) { scope.launch { model.cancelPending(p) } }
                }
            }

            // ── SHARE A LINK: the system share sheet with a one-shot join link ──
            val linkBusy = s.busyId == ShareScreenModel.LINK_BUSY_ID
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Share a link")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable(enabled = !linkBusy) { scope.launch { model.makeLink()?.let { shareLink(it) } } }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = "Share a link, ${s.access.label}" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                    Text(if (linkBusy) "Making a link…" else "Share a link", style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Share, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                }
                Text(
                    "Whoever opens it is connected to you and gets this ${target.kind.noun} — ${s.access.label.lowercase()}. The link works once and expires in 14 days.",
                    style = UFont.sans(12), color = c.ink3,
                )
            }
        }

        // Everyone you share with, in one place: Settings → People.
        if (onManagePeople != null) {
            Text(
                MANAGE_PEOPLE, style = UFont.sans(13, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = MANAGE_PEOPLE_A11Y, onClick = onManagePeople)
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 4.dp)
                    .testTag("share-manage-people"),
            )
        }
    }
}

@Composable
private fun CardDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(UTheme.colors.line))
}

/** One 44dp row. The WHOLE row is the control: a tap shares / hands over, or
 *  opens the access menu for someone who already has it. A busy row is inert
 *  (spinner); the hand-over holder's row is inert (a state, not a dimmed
 *  control); every other row dims while a write is in flight. [preCreate]
 *  (the New task sheet's picker): the menu gains "Hand over" and loses Report
 *  and Block, which act on a share that doesn't exist yet. */
@Composable
private fun PersonRow(
    row: SharePersonRow,
    handOver: Boolean,
    access: ShareAccess,
    busy: Boolean,
    anyBusy: Boolean,
    onTap: () -> Unit,
    onSetAccess: (ShareAccess?) -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    preCreate: Boolean = false,
    onHandOver: () -> Unit = {},
) {
    val c = UTheme.colors
    val on = if (handOver) row.handedOver else row.isShared
    var menu by remember(row.id) { mutableStateOf(false) }
    val label = when {
        busy -> "${row.name}, working"
        handOver && row.handedOver -> "${row.name}, already handed over"
        handOver -> "Hand over to ${row.name}"
        row.isShared -> "${row.name}, ${row.statusLabel ?: "shared"}. Change access"
        else -> "Share with ${row.name}, ${access.label}"
    }
    val interactive = !busy && !(handOver && row.handedOver)
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .then(if (interactive) Modifier.clickable(enabled = !anyBusy) { if (!handOver && row.isShared) menu = true else onTap() } else Modifier)
                .alpha(if (anyBusy && !busy) 0.6f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Monogram(row.name, on = on, size = 22)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                row.subtitle?.let { Text("· $it", style = UFont.sans(12), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(16.dp), color = c.ink2, strokeWidth = 2.dp)
                handOver && row.handedOver -> Text(row.statusLabel ?: "Handed over", style = UFont.sans(13), color = c.ink3)
                handOver -> Pill("Hand over")
                row.isShared -> Pill(row.statusLabel ?: "Shared", chevron = true)
                else -> Pill("Share")
            }
        }
        // The picker for someone who already has the item: Can edit ✓ / Can
        // view / Report… / Block… / Remove (or "Take it back" for a hand-over).
        // Pre-create: Can edit / Can view / Hand over / Remove.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ShareAccess.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, style = UFont.sans(14), color = c.ink) },
                    leadingIcon = if (row.access == a) ({ Icon(Icons.Filled.Check, contentDescription = "current", tint = c.ink, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = { menu = false; if (row.access != a) onSetAccess(a) },
                )
            }
            if (preCreate) {
                DropdownMenuItem(
                    text = { Text("Hand over", style = UFont.sans(14), color = c.ink) },
                    leadingIcon = if (row.handedOver) ({ Icon(Icons.Filled.Check, contentDescription = "current", tint = c.ink, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = { menu = false; if (!row.handedOver) onHandOver() },
                )
            }
            CardDivider()
            if (!preCreate) {
                DropdownMenuItem(text = { Text("Report…", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; onReport() })
                DropdownMenuItem(text = { Text("Block ${row.name}…", style = UFont.sans(14), color = c.red) }, onClick = { menu = false; onBlock() })
            }
            DropdownMenuItem(
                text = { Text(if (row.handedOver && !preCreate) "Take it back" else "Remove", style = UFont.sans(14), color = c.red) },
                onClick = { menu = false; onSetAccess(null) },
            )
        }
    }
}

/** The filled action / state word: `bg` on `ink` (the app's filled-chip pair). */
@Composable
private fun Pill(text: String, chevron: Boolean = false) {
    val c = UTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(c.ink).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text, style = UFont.sans(12, FontWeight.SemiBold), color = c.bg)
        if (chevron) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.bg, modifier = Modifier.size(12.dp))
    }
}

/** The app's selected / unselected pair: filled `ink` with a `bg` letter when
 *  they hold the item, `surface` / `ink2` with a `line2` ring when not. Colour
 *  is never the only signal — the trailing word says the same thing. */
@Composable
private fun Monogram(name: String, on: Boolean, size: Int) {
    val c = UTheme.colors
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (on) c.ink else c.surface)
            .border(1.dp, if (on) c.ink else c.line2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text((name.trim().firstOrNull() ?: '?').uppercase(), style = UFont.sans(if (size > 24) 11 else 10, FontWeight.SemiBold), color = if (on) c.bg else c.ink2)
    }
}

/** The dropdown: "Choose someone ⌄" — opens the searchable picker. */
@Composable
private fun ChooseRow(handOver: Boolean, count: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Choose someone, $count people. Opens a searchable list" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = c.ink2, modifier = Modifier.size(20.dp))
        Text(if (handOver) "Choose who gets it" else "Choose someone", style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
        Text("· $count", style = UFont.sans(12), color = c.ink3)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.ink3, modifier = Modifier.size(16.dp))
    }
}

/** A pending email invite under "Someone new": the address, what they'll get, and Cancel. */
@Composable
private fun PendingRow(p: SharePendingRow, busy: Boolean, onCancel: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.email, style = UFont.sans(13), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Invited · waiting for them to sign up · ${p.access.label.lowercase()}", style = UFont.sans(11), color = c.amberInk)
        }
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = c.ink2, strokeWidth = 2.dp)
        else Icon(
            Icons.Filled.Close, contentDescription = "Cancel invite to ${p.email}", tint = c.ink3,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onCancel).padding(8.dp),
        )
    }
}

/** The searchable dropdown behind "Choose someone": everyone you are connected
 *  to who does NOT have the item yet, a search field, one tap to share / hand
 *  over. Its own bottom sheet above the Share screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeoplePickerSheet(
    title: String,
    action: String,
    people: List<SharePersonRow>,
    onPick: (SharePersonRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        PeoplePickerBody(title, action, people, query, onQuery = { query = it }, onPick = onPick, onCancel = onDismiss)
    }
}

/** The picker's content, outside its bottom sheet (so a render test can draw it). */
@Composable
internal fun PeoplePickerBody(
    title: String,
    action: String,
    people: List<SharePersonRow>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (SharePersonRow) -> Unit,
    onCancel: () -> Unit,
) {
    val c = UTheme.colors
    val rows = remember(people, query) { sharePeopleCandidates(people, query) }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = UFont.serifItalic(18), color = c.ink, modifier = Modifier.weight(1f))
            Text(
                "Cancel", style = UFont.sans(14, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search people", style = UFont.sans(14), color = c.ink3) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        if (rows.isEmpty()) {
            Text(
                if (people.isEmpty()) "Everyone you're connected to already has it." else "No one matches “${query.trim()}”.",
                style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(rows, key = { it.id }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(row) }
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                            .semantics { contentDescription = "$action with ${row.name}" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Monogram(row.name, on = false, size = 30)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.name, style = UFont.sans(15, FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            row.subtitle?.let { Text(it, style = UFont.sans(12), color = c.ink3, maxLines = 1) }
                        }
                        Spacer(Modifier.width(4.dp))
                        Pill(action)
                    }
                }
            }
        }
    }
}

/** The share sheet's way to the roster (slim settings, 2026-09-24). */
internal const val MANAGE_PEOPLE = "Manage people"
internal const val MANAGE_PEOPLE_A11Y = "Manage the people you share with, in Settings"
internal const val MANAGE_PEOPLE_LINK = "unstuck://settings?section=People"
