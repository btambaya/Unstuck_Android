package tech.csalliance.unstuck.design.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckColors

/** M3 outlined text field with a notched floating label (radius 6dp).
 *  IME navigation: [imeAction] = Next moves focus to the next field below;
 *  Done fires [onDone] (the form's submit) or, when null, just clears focus. */
@Composable
fun MdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
) {
    val c = UTheme.colors
    val focusManager = LocalFocusManager.current
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, c.line2, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = UFont.sans(14).copy(color = c.ink),
                singleLine = true,
                cursorBrush = SolidColor(c.ink),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { onDone?.invoke() ?: focusManager.clearFocus() },
                ),
                modifier = Modifier.fillMaxWidth().semantics {
                    // The floating label is a disconnected sibling node; name the field here
                    // so TalkBack doesn't announce an anonymous "Edit box".
                    contentDescription = label
                    if (password) password()
                },
            )
        }
        // Floating label notch painted over the border. Hidden from accessibility
        // (clearAndSetSemantics) — the field above already carries the label.
        Box(
            Modifier.offset(x = 10.dp, y = (-8).dp).clip(RoundedCornerShape(2.dp)).background(c.bg)
                .padding(horizontal = 4.dp).clearAndSetSemantics {},
        ) {
            Text(label, style = UFont.sans(11), color = c.ink3)
        }
    }
}

/** The switch's track: brand coral when ON (owner decision 2026-09-24 — every
 *  "on" switch in all three apps is coral; it was green here, indigo on iOS),
 *  the `line2` hairline grey when off. The thumb stays white either way. */
fun toggleTrackColor(c: UnstuckColors, checked: Boolean): Color = if (checked) c.coral else c.line2

/** M3 switch — 44×26 pill, coral when on ([toggleTrackColor]). Mirrors M3 Switch
 *  a11y: Role.Switch + on/off state via toggleable, 48dp minimum interactive size.
 *  Every switch in the app is this one component, so they all follow. */
@Composable
fun MdToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    val thumbX by animateDpAsState(if (checked) 18.dp else 0.dp, label = "thumb")
    Box(
        modifier.minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .width(44.dp).height(26.dp).clip(CircleShape).background(toggleTrackColor(c, checked))
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = thumbX).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

/** M3 segmented control — pill track (bg2), dark-ink active segment. Each option is a
 *  selectable radio (selected state announced) with a ≥40dp touch target. */
@Composable
fun MdSegment(options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.bg2).selectableGroup().padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (active) c.ink else Color.Transparent)
                    .selectable(selected = active, role = Role.RadioButton) { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                // ink2 (not ink3) for inactive labels: 11sp on bg2 needs ≥4.5:1 AA contrast.
                Text(opt, style = UFont.sans(11, FontWeight.SemiBold), color = if (active) c.bg else c.ink2)
            }
        }
    }
}
