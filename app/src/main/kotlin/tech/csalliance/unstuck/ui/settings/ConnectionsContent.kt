package tech.csalliance.unstuck.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.InviteResult
import tech.csalliance.unstuck.ui.AppViewModel

// Connections ("People you share with") — the Android port of the web
// SharingPanel/CircleRoster. One unified roster: everyone you've shared a task or a
// list with lands here. Add someone by email (the server emails them the join
// link), generate a link to send yourself, or redeem a code someone sent you; and
// remove a connection. Everything goes through the CircleClient RPCs + the
// circle-invite edge fn; the roster is a live StateFlow (it refetches on the
// CollabRealtime circle-changed signal when someone accepts/leaves).

/** Build the same join link the web copies for a pending invite's code. */
private fun inviteLink(code: String): String = "https://unstucknow.io/circle/join?code=$code"

@Composable
fun ConnectionsContent(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val members by vm.circle.collectAsStateWithLifecycle()

    // Add-someone state (inline panel, mirrors the web CircleRoster).
    var adding by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<InviteResult?>(null) }
    var addErr by remember { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }   // which link was just copied

    // Redeem-a-code state.
    var code by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var redeemMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun copy(text: String, key: String) {
        clipboard.setText(AnnotatedString(text)); copiedKey = key
        scope.launch { delay(1800); if (copiedKey == key) copiedKey = null }
    }

    fun submitInvite() {
        if (busy) return
        busy = true; addErr = null
        scope.launch {
            val r = runCatching { vm.inviteToCircle(email) }.getOrNull()
            busy = false
            if (r == null || r.error != null) {
                addErr = "Could not create invite."
            } else {
                result = r
                email = ""
                r.link?.let { copy(it, "new") }
            }
        }
    }

    fun submitRedeem() {
        // Accept a bare code OR a pasted join link (…/circle/join?code=XXXX).
        val raw = code.trim()
        val parsed = if (raw.contains("code=")) raw.substringAfter("code=").substringBefore('&').trim() else raw
        if (parsed.isEmpty() || redeeming) return
        redeeming = true; redeemMsg = null
        scope.launch {
            val r = runCatching { vm.redeemCircle(parsed) }.getOrNull()
            redeeming = false
            when {
                r == null -> redeemMsg = false to "Could not join. Try again."
                r.ok -> { code = ""; redeemMsg = true to "Joined ${r.ownerName ?: "their"} connections." }
                r.error == "self" -> redeemMsg = false to "That's your own invite."
                r.error == "already_in_circle" -> redeemMsg = false to "You're already connected."
                r.error == "invalid_or_expired" -> redeemMsg = false to "That invite is invalid or expired."
                else -> redeemMsg = false to "Could not join. Try again."
            }
        }
    }

    val activeOrInvited = members.count { it.isActiveOrInvited }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Everyone you share a task or a list with lands here — one place, no double invites. " +
                "You share from the task itself; this is where you add or remove people.",
            style = UFont.sans(13), color = c.ink2,
        )

        // ── Roster header + Add-someone toggle ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("People · $activeOrInvited", modifier = Modifier.weight(1f))
            if (!adding) UButton("Add someone", kind = ButtonKind.OUTLINED, fill = false, leadingIcon = Icons.Filled.Add) {
                adding = true; result = null; addErr = null
            }
        }

        // ── Roster list ──
        if (members.isEmpty() && !adding) {
            Text("No one yet. Add anyone you want to share with.", style = UFont.sans(13), color = c.ink3)
        } else {
            members.forEach { m -> MemberRow(m, copied = copiedKey == m.id, onCopy = { m.inviteCode?.let { copy(inviteLink(it), m.id) } }, onRemove = { scope.launch { vm.removeFromCircle(m.id) } }) }
        }

        // ── Inline add-someone panel ──
        if (adding) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = result
                if (r != null) {
                    when {
                        r.added == true -> Text("✓ Added.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                        r.emailed == true -> Text("✓ Invite sent.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                        r.link != null -> {
                            Text("Invite link ready${if (copiedKey == "new") " · copied!" else ""}", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                            Text(r.link!!, style = UFont.sans(12), color = c.ink2, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 8.dp))
                            Text("Send this to them however you like — it's the only way in.", style = UFont.sans(12), color = c.ink3)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.link != null) UButton("Copy link", kind = ButtonKind.DARK, fill = false) { copy(r.link!!, "new") }
                        UButton("Done", kind = ButtonKind.GHOST, fill = false) { adding = false; result = null }
                    }
                } else {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("name@example.com (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitInvite() }),
                    )
                    Text("We'll email them the invite. Or leave it blank for a link you send yourself.", style = UFont.sans(12), color = c.ink3)
                    addErr?.let { Text(it, style = UFont.sans(12), color = c.coralDeep) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UButton(if (busy) "…" else if (email.isBlank()) "Generate link" else "Send invite", kind = ButtonKind.DARK, fill = false, enabled = !busy) { submitInvite() }
                        UButton("Cancel", kind = ButtonKind.GHOST, fill = false) { adding = false; addErr = null }
                    }
                }
            }
        }

        // ── Redeem an invite someone sent you ──
        SectionLabel("Have an invite?", modifier = Modifier.padding(top = 4.dp))
        Text("Paste the invite link or code someone sent you to join their connections.", style = UFont.sans(12), color = c.ink3)
        OutlinedTextField(
            value = code, onValueChange = { code = it },
            label = { Text("Invite link or code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submitRedeem() }),
        )
        redeemMsg?.let { (ok, text) -> Text(text, style = UFont.sans(12), color = if (ok) c.greenInk else c.coralDeep) }
        UButton(if (redeeming) "Joining…" else "Join", kind = ButtonKind.DARK, fill = false, enabled = !redeeming && code.isNotBlank()) { submitRedeem() }
    }
}

/** One roster row: person glyph + name/status, plus copy-link (pending) + remove. */
@Composable
private fun MemberRow(m: CircleMember, copied: Boolean, onCopy: () -> Unit, onRemove: () -> Unit) {
    val c = UTheme.colors
    val pending = m.status == CircleStatus.INVITED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Circle icon = a human head/person (per the design note).
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(if (pending) "Invite pending" else (m.memberName ?: "Member"), style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1)
            Text(m.relationshipLabel ?: if (pending) "waiting to be accepted" else "connected", style = UFont.sans(12), color = c.ink3, maxLines = 1)
        }
        if (pending && m.inviteCode != null) {
            Text(if (copied) "Copied!" else "Copy link", style = UFont.sans(12, FontWeight.SemiBold), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopy() }.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.ink3, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onRemove() }.padding(5.dp))
    }
}
