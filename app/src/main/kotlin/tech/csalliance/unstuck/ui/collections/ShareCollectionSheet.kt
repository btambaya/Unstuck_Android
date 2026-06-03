package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.CollectionMemberInfo
import tech.csalliance.unstuck.sync.ShareOutcome
import tech.csalliance.unstuck.ui.AppViewModel

/** Share a collection by email — existing user → member, otherwise a pending
 *  invite that's claimed on signup. Mirrors the web share-collection-sheet:
 *  email + role (can edit / can view), live member + pending-invite list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCollectionSheet(vm: AppViewModel, collectionId: String, collectionName: String, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("editor") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }   // ok? -> text
    var members by remember { mutableStateOf<List<CollectionMemberInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() { members = vm.listCollectionMembers(collectionId); loading = false }
    LaunchedEffect(collectionId) { refresh() }

    fun submit() {
        val e = email.trim()
        if (e.isEmpty() || busy) return
        busy = true; message = null
        scope.launch {
            val outcome = vm.shareCollection(collectionId, e, role)
            busy = false
            when (outcome) {
                ShareOutcome.OK -> { email = ""; message = true to "Shared with $e (${if (role == "viewer") "can view" else "can edit"})."; refresh() }
                ShareOutcome.INVITED -> { email = ""; message = true to "Invited $e — they'll get access when they sign up."; refresh() }
                ShareOutcome.SELF -> message = false to "That's you."
                ShareOutcome.NOT_FOUND -> message = false to "No Unstuck account for that email yet."
                ShareOutcome.ERROR -> message = false to "Could not share. Try again."
            }
        }
    }

    fun removeMember(m: CollectionMemberInfo) {
        members = members.filterNot { it === m }   // optimistic
        scope.launch {
            if (m.pending) vm.cancelCollectionInvite(collectionId, m.email) else vm.unshareCollection(collectionId, m.userId)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Share · ${collectionName}")
            Text(
                "Invite anyone by email. If they don't have an Unstuck account yet, they'll get access the moment they sign up. Changes sync live between you.",
                style = UFont.sans(13), color = c.ink2,
            )

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("partner@email.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            // Role toggle + Share.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("editor" to "Can edit", "viewer" to "Can view").forEach { (value, label) ->
                    val on = role == value
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) c.ink else c.bg2)
                            .border(1.dp, c.line2, RoundedCornerShape(999.dp)).clickable { role = value }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(label, style = UFont.sans(12, FontWeight.SemiBold), color = if (on) c.bg else c.ink2) }
                }
                Box(Modifier.weight(1f))
                UButton(if (busy) "Sharing…" else "Share", kind = ButtonKind.DARK, fill = false, enabled = !busy && email.isNotBlank()) { submit() }
            }

            message?.let { (ok, text) ->
                Text(text, style = UFont.sans(13), color = if (ok) c.greenInk else c.coralDeep)
            }

            // Members + pending invites.
            SectionLabel(if (members.isNotEmpty()) "Shared with ${members.size}" else "Not shared yet")
            if (loading) {
                Text("Loading…", style = UFont.sans(13), color = c.ink3)
            } else {
                members.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(m.email, style = UFont.sans(13), color = c.ink2, modifier = Modifier.weight(1f))
                        if (m.pending) Text("PENDING", style = UFont.sans(9, FontWeight.Bold), color = c.amberInk)
                        Text(if (m.role == "viewer") "VIEW" else "EDIT", style = UFont.sans(10, FontWeight.Bold), color = c.ink3)
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${m.email}", tint = c.ink3, modifier = Modifier.clickable { removeMember(m) }.padding(2.dp))
                    }
                }
            }

            UButton("Done", kind = ButtonKind.GHOST, modifier = Modifier.padding(top = 4.dp)) { onDismiss() }
        }
    }
}
