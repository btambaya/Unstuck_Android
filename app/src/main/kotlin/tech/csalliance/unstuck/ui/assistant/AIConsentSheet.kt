package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.R
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// AI data-sharing consent (core AIConsent) — the sheet that asks, and the line
// a surface shows after "Not now". The gate itself is AppViewModel.withAIConsent;
// MainScaffold hosts the ONE sheet (above whatever asked — the Assistant sheet,
// Settings, a task), so no surface presents its own.

/** The surface a consent ask came from — the one that shows its "Not now"
 *  line (iOS AIConsentHost). */
enum class AIConsentHost {
    /** App open with Calls on and no OK (MainScaffold's dialog). */
    ROOT,
    ASSISTANT,
    /** Today's ask pill — its Talk mic. */
    TODAY,
    /** Settings → Notifications & calls → Calls. */
    CALL_SETTINGS,
    /** A task's "Call me about this". */
    TASK_EDITOR,
    /** Settings → Assistant & privacy → AI data sharing. */
    SETTINGS,
}

/** What the consent sheet was opened for, and what each answer goes on to do. */
data class AIConsentAsk(
    val id: String,
    val action: AIConsent.Action,
    val host: AIConsentHost,
    val onAgree: () -> Unit,
    val onDecline: () -> Unit,
)

/** A "Not now" line and the surface that shows it. */
data class AIConsentNote(val host: AIConsentHost, val text: String)

/** "Your assistant uses OpenAI" — the disclosure + ask before anything the user
 *  types or says goes to the AI provider. The same words as iOS and the web.
 *  Swiping it away counts as "Not now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIConsentSheet(vm: AppViewModel, ask: AIConsentAsk) {
    val c = UTheme.colors
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The sheet reports in (an ask whose sheet never came up is dropped), and a
    // sheet torn down under its host (a deep link, the tour) counts as "Not now".
    DisposableEffect(ask.id) {
        vm.aiConsentSheetShown(ask.id)
        onDispose { vm.aiConsentSheetGone(ask.id) }
    }
    ModalBottomSheet(
        onDismissRequest = { vm.declineAIConsent() }, sheetState = sheet, containerColor = c.bg, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 20.dp)
                .testTag("ai-consent-sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.bg2), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_orbit), contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
            }
            Text(AIConsent.TITLE, style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(AIConsent.BODY, style = UFont.sans(14), color = c.ink2)
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AIConsent.PRIVACY_URL))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .minimumInteractiveComponentSize()
                    .testTag("ai-consent-privacy"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(AIConsent.PRIVACY_LINK_LABEL, style = UFont.sans(13, FontWeight.SemiBold).copy(textDecoration = TextDecoration.Underline), color = c.ink)
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.ink, modifier = Modifier.size(13.dp))
            }
            // The app's selection pair (ink / bg), never an accent.
            Box(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.ink)
                    .clickable(role = Role.Button) { vm.agreeAIConsent() }
                    .heightIn(min = 48.dp).padding(vertical = 14.dp)
                    .testTag("ai-consent-agree"),
                contentAlignment = Alignment.Center,
            ) { Text(AIConsent.AGREE_LABEL, style = UFont.sans(15, FontWeight.SemiBold), color = c.bg) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { vm.declineAIConsent() }
                    .heightIn(min = 48.dp).padding(vertical = 10.dp)
                    .testTag("ai-consent-decline"),
                contentAlignment = Alignment.Center,
            ) { Text(AIConsent.DECLINE_LABEL, style = UFont.sans(14, FontWeight.Medium), color = c.ink2) }
        }
    }
}

/** A "Not now" line under the surface that asked — calm secondary ink, not an error. */
@Composable
fun AIConsentNoteLine(vm: AppViewModel, host: AIConsentHost, modifier: Modifier = Modifier) {
    val note by vm.aiConsentNote.collectAsStateWithLifecycle()
    val n = note?.takeIf { it.host == host } ?: return
    Text(
        n.text, style = UFont.sans(12), color = UTheme.colors.ink3,
        modifier = modifier.testTag("ai-consent-note").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** App open, "Not now": Calls went off — a dialog says so (iOS's alert). */
@Composable
fun AIConsentRootNote(vm: AppViewModel) {
    val c = UTheme.colors
    val note by vm.aiConsentNote.collectAsStateWithLifecycle()
    val n = note?.takeIf { it.host == AIConsentHost.ROOT } ?: return
    AlertDialog(
        onDismissRequest = { vm.clearAIConsentNote() },
        title = { Text(AIConsent.CALLS_TURNED_OFF_TITLE, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text(n.text, style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { vm.clearAIConsentNote() }) { Text("OK", color = c.ink) } },
        containerColor = c.surface,
    )
}
