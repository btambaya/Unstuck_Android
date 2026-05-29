package tech.csalliance.unstuck.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.AuthOutcome
import tech.csalliance.unstuck.ui.AppViewModel

@Composable
fun AuthScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val c = UTheme.colors

    fun run(block: suspend () -> AuthOutcome) {
        busy = true; message = null
        scope.launch {
            when (val r = block()) {
                is AuthOutcome.Ok -> if (mode == AuthMode.MAGIC) message = "Check your email for the link."
                is AuthOutcome.Error -> message = r.message
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("unstuck", style = UFont.serifItalic(40), color = c.ink)
        Spacer(Modifier.height(6.dp))
        SectionLabel(
            when (mode) {
                AuthMode.SIGN_IN -> "Welcome back"
                AuthMode.SIGN_UP -> "Create account"
                AuthMode.MAGIC -> "Magic link"
            },
        )
        Spacer(Modifier.height(24.dp))

        if (mode == AuthMode.SIGN_UP) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxSize().height(64.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        if (mode != AuthMode.MAGIC) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = UFont.sans(13), color = c.coralDeep)
        }

        Spacer(Modifier.height(20.dp))
        UButton(
            title = if (busy) "…" else when (mode) {
                AuthMode.SIGN_IN -> "Sign in"
                AuthMode.SIGN_UP -> "Create account"
                AuthMode.MAGIC -> "Send magic link"
            },
            enabled = !busy,
        ) {
            when (mode) {
                AuthMode.SIGN_IN -> run { vm.signIn(email.trim(), password) }
                AuthMode.SIGN_UP -> run { vm.signUp(email.trim(), password, name) }
                AuthMode.MAGIC -> run { vm.magicLink(email.trim()) }
            }
        }
        Spacer(Modifier.height(10.dp))
        UButton(title = "Continue with Google", kind = ButtonKind.GHOST, enabled = !busy) {
            run { vm.googleSignIn() }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN }) {
            Text(if (mode == AuthMode.SIGN_IN) "New here? Create an account" else "Have an account? Sign in", color = c.primary, style = UFont.sans(13))
        }
        TextButton(onClick = { mode = AuthMode.MAGIC }) {
            Text("Email me a magic link instead", color = c.ink3, style = UFont.sans(13))
        }
    }
}

private enum class AuthMode { SIGN_IN, SIGN_UP, MAGIC }
