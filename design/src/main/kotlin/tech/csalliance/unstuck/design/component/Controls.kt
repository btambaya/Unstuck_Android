package tech.csalliance.unstuck.design.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** M3 outlined text field with a notched floating label (radius 6dp). */
@Composable
fun MdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = UTheme.colors
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
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Floating label notch painted over the border.
        Box(Modifier.offset(x = 10.dp, y = (-8).dp).clip(RoundedCornerShape(2.dp)).background(c.bg).padding(horizontal = 4.dp)) {
            Text(label, style = UFont.sans(11), color = c.ink3)
        }
    }
}

/** M3 switch — 44×26 pill, green when on. */
@Composable
fun MdToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    val thumbX by animateDpAsState(if (checked) 18.dp else 0.dp, label = "thumb")
    Box(
        modifier.width(44.dp).height(26.dp).clip(CircleShape).background(if (checked) c.green else c.line2)
            .clickable { onChange(!checked) }.padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = thumbX).size(22.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
    }
}

/** M3 segmented control — pill track (bg2), dark-ink active segment. */
@Composable
fun MdSegment(options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.bg2).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) c.ink else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(opt) }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(opt, style = UFont.sans(11, FontWeight.SemiBold), color = if (active) c.bg else c.ink3)
            }
        }
    }
}
