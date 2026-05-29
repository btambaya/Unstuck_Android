package tech.csalliance.unstuck.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.MdField
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.AuthOutcome
import tech.csalliance.unstuck.ui.AppViewModel

@Composable
fun AuthScreen(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> AuthOutcome) {
        busy = true; message = null
        scope.launch {
            when (val r = block()) {
                is AuthOutcome.Ok -> {}
                is AuthOutcome.Error -> message = r.message
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Orbit(size = 36)
        Spacer(Modifier.height(16.dp))
        SectionLabel(if (signUp) "BEGIN AGAIN" else "WELCOME BACK", color = c.primaryDeep)
        Text(
            if (signUp) "You don't need more discipline." else "Pick up where\nyou left off.",
            style = UFont.serifItalic(40), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (signUp) "Unstuck reduces friction at the moment behavior breaks." else "Quiet clarity, with momentum.",
            style = UFont.sans(13), color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
        )

        Spacer(Modifier.height(22.dp))
        if (signUp) {
            MdField(name, { name = it }, "Name (optional)"); Spacer(Modifier.height(14.dp))
        }
        MdField(email, { email = it }, "Email", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        MdField(password, { password = it }, "Password", password = true)

        message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = UFont.sans(13), color = c.coralDeep, textAlign = TextAlign.Center) }

        Spacer(Modifier.height(20.dp))
        UButton(if (busy) "…" else if (signUp) "Create account" else "Sign in", kind = ButtonKind.DARK, enabled = !busy) {
            if (signUp) run { vm.signUp(email.trim(), password, name) } else run { vm.signIn(email.trim(), password) }
        }
        Spacer(Modifier.height(10.dp))
        // Outlined Google button with the official multicolor "G" logo.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(999.dp))
                .clickable(enabled = !busy) { run { vm.googleSignIn() } }.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(tech.csalliance.unstuck.R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Continue with Google", style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (signUp) "Already have an account? Sign in" else "New here? Create an account",
            style = UFont.sans(13, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clickable { signUp = !signUp },
        )
        Spacer(Modifier.height(8.dp))
        Text("Email me a magic link instead", style = UFont.sans(13), color = c.ink3, modifier = Modifier.clickable { run { vm.magicLink(email.trim()) } })
        if (!signUp) {
            Spacer(Modifier.height(8.dp))
            Text("Forgot your password?", style = UFont.sans(13), color = c.ink3, modifier = Modifier.clickable { run { vm.resetPassword(email.trim()) } })
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (signUp) "Quiet clarity, with momentum." else "The anchor stays steady. You move around it.",
            style = UFont.sans(11), color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp),
        )
    }
}
