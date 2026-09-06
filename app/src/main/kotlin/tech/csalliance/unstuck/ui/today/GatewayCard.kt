package tech.csalliance.unstuck.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.BuildConfig
import tech.csalliance.unstuck.core.logic.Moment
import tech.csalliance.unstuck.core.logic.MomentRun
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.assistant.GATEWAY_CHIPS
import tech.csalliance.unstuck.ui.assistant.GATEWAY_INTERVIEW_PILL
import tech.csalliance.unstuck.ui.assistant.GATEWAY_PLACEHOLDER

// The AI gateway card on Today — Android port of iOS App/Features/GatewayCard.swift
// (itself the port of components/dashboard/gateway-card.tsx). ADDITIVE by
// design: it sits between the greeting and the recap/hero; the Start-Next
// hero, recap and list stay. Under the greeting: a deterministic zero-token
// brief (composeBrief), ONE "moment" from the PA engine (pickMoment — rituals
// the user opted into, notices from their behaviour, relationship reminders),
// the first-run interview pill, and a single composer whose send HANDS OFF to
// the Assistant sheet (which owns the thread). Moments REPLACE the old
// quiet-nudge card (they subsume slip radar / habit gaps).
//
// Everything derived lives on the ViewModel (`gateway`, memoised on a
// minute-keyed input; `momentDone`; the action handlers) — the card only
// draws and forwards taps.

/**
 * @param onTalk the mic inside the composer — the host presents VoiceModeScreen,
 *   exactly as the Assistant sheet does.
 * @param interviewPending show the "Personalise your assistant" pill (not done,
 *   not open). The pill stays until the interview is DONE (not merely started):
 *   leaving after 1–2 answers used to dead-end onboarding with no way back in.
 * @param onPersonalise open the interview (the host presents the sheet).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GatewayCard(
    vm: AppViewModel,
    onTalk: () -> Unit,
    interviewPending: Boolean,
    onPersonalise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = UTheme.colors
    val derived by vm.gateway.collectAsStateWithLifecycle()
    val momentDone by vm.momentDone.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Privacy §21 kill-switch (Settings → Interface → AI Assistant): with AI off
    // the WHOLE card goes — web `if (!aiEnabled) return null` (gateway-card.tsx:159)
    // and iOS `if model.assistantEnabled { card }`. A card still headed "YOUR
    // ASSISTANT" after the user switched the assistant off is the opposite of a
    // kill-switch, even with its moment/interview/composer stripped out.
    val assistantOn = BuildConfig.ASSISTANT_ENABLED && settings.assistantEnabled
    if (!assistantOn) return
    var draft by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun engage(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        draft = ""
        keyboard?.hide()
        // Hand-off: the sheet owns the thread — same queue a typed message takes.
        vm.openAssistantWith(t)
    }

    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(
                Brush.linearGradient(listOf(c.coral.copy(alpha = if (c.isDark) 0.14f else 0.09f), c.surface)),
            )
            .border(1.5.dp, c.coral, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { contentDescription = "Assistant" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Identity row — THIS is the assistant, and it's alive.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).shadow(4.dp, CircleShape, ambientColor = c.coral, spotColor = c.coral)
                    .clip(CircleShape).background(c.coral),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "YOUR ASSISTANT", style = UFont.mono(10, FontWeight.SemiBold).copy(letterSpacing = 1.6.sp), color = c.coralDeep,
                    modifier = Modifier.semantics { contentDescription = "Your assistant" },
                )
                // The brief — deterministic, instant, offline-safe.
                Text(derived.brief, style = UFont.serif(19).copy(lineHeight = 26.sp), color = c.ink)
            }
        }

        // One PA moment at a time — ritual, notice, or relationship. The ✓ line
        // is a moment, not a mute button: it clears after ~8 s so the NEXT
        // undismissed moment can surface.
        val done = momentDone
        val moment = derived.moment
        if (done != null) {
            LaunchedEffect(done) { kotlinx.coroutines.delay(8_000); vm.clearMomentDone(done) }
            Text(
                "✓ $done", style = UFont.sans(13), color = c.ink2,
                modifier = Modifier.semantics { contentDescription = done; liveRegion = LiveRegionMode.Polite },
            )
        } else if (moment != null) {
            MomentView(moment, onAction = { a -> vm.runMomentAction(moment, a) })
        }

        // First-run interview — the profile bootstrap.
        if (interviewPending) {
            val dash = c.line2
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = onPersonalise)
                    .drawBehind {
                        drawRoundRect(
                            color = dash, cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .semantics { contentDescription = "Personalise your assistant — two minutes, skip anything" },
                contentAlignment = Alignment.Center,
            ) { Text(GATEWAY_INTERVIEW_PILL, style = UFont.sans(13), color = c.ink2) }
        }

        // Instant CTAs — one tap into a real conversation.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GATEWAY_CHIPS.forEach { chip ->
                Text(
                    chip.label, style = UFont.sans(12, FontWeight.SemiBold), color = c.coralDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.surface)
                        .border(1.dp, c.coral.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button) { engage(chip.message) }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .semantics { contentDescription = chip.spoken },
                )
            }
        }

        // The gateway: one composer. Search-box construction — the sparkles icon
        // lives INSIDE the field, and voice lives INSIDE the bar next to send.
        val can = draft.isNotBlank()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp)).background(c.surface)
                .border(1.5.dp, c.coral.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                .padding(start = 13.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.coral, modifier = Modifier.size(15.dp))
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                textStyle = UFont.sans(14).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { engage(draft) }),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(start = 6.dp, top = 12.dp, bottom = 12.dp)
                    .semantics { contentDescription = "Ask the assistant" },
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text(GATEWAY_PLACEHOLDER, style = UFont.sans(14), color = c.ink3, maxLines = 1)
                    inner()
                },
            )
            if (vm.voiceConfigured()) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onTalk)
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = "Talk to your assistant" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = c.coralDeep, modifier = Modifier.size(18.dp)) }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable(enabled = can, role = Role.Button) { engage(draft) }
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(if (can) c.coral else c.coral.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentView(m: Moment, onAction: (tech.csalliance.unstuck.core.logic.MomentAction) -> Unit) {
    val c = UTheme.colors
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = m.text },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(m.text, style = UFont.sans(13).copy(fontStyle = FontStyle.Italic, lineHeight = 19.sp), color = c.ink2)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.actions.forEach { a ->
                val isDismiss = a.run == MomentRun.Dismiss
                Text(
                    a.label,
                    style = UFont.sans(12, if (isDismiss) FontWeight.Normal else FontWeight.SemiBold),
                    color = if (isDismiss) c.ink2 else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isDismiss) Color.Transparent else c.coral)
                        .then(if (isDismiss) Modifier.border(1.dp, c.line2, RoundedCornerShape(999.dp)) else Modifier)
                        .clickable(role = Role.Button) { onAction(a) }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .semantics { contentDescription = a.label },
                )
            }
        }
    }
}
