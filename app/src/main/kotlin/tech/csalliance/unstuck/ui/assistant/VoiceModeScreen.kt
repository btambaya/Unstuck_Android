package tech.csalliance.unstuck.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * Full-screen voice mode — live speech-to-speech with Qwen-Omni (through the
 * Cloudflare proxy). Talk naturally; it listens, reasons, runs your scheduling
 * tools, and speaks back, with barge-in. Shows the current state + live caption.
 */
@Composable
fun VoiceModeScreen(vm: AppViewModel, onClose: () -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val audio = remember { VoiceAudioEngine(context) }

    var state by remember { mutableStateOf(VoiceState.CONNECTING) }
    var caption by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var client by remember { mutableStateOf<VoiceRealtimeClient?>(null) }

    fun startSession() {
        val token = vm.voiceAccessToken()
        if (token.isNullOrBlank()) { note = "Please sign in to use voice."; state = VoiceState.ERROR; return }
        if (!vm.voiceConfigured()) { note = "Voice isn't set up yet."; state = VoiceState.ERROR; return }
        note = null; state = VoiceState.CONNECTING
        vm.resetVoiceScratch()
        val rc = VoiceRealtimeClient(
            proxyUrl = vm.voiceProxyUrl, token = token, model = vm.voiceModel,
            instructions = vm.voiceInstructions(), tools = vm.voiceTools(), audio = audio,
            runTool = { name, args -> vm.runVoiceTool(name, args) },
            onState = { s -> main.post { state = s } },
            onError = { msg -> main.post { note = msg } },
            onCaption = { role, text, done ->
                main.post {
                    when {
                        role == "user" -> caption = "" // new user turn → clear the reply line
                        role == "assistant" && !done -> caption += text
                    }
                }
            },
        )
        client = rc
        rc.start()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSession() else { note = "Microphone access is needed for voice."; state = VoiceState.ERROR }
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startSession() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { client?.stop(); audio.shutdown() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // Close (X)
            Box(
                Modifier.align(Alignment.TopEnd).padding(18.dp).size(40.dp).clip(CircleShape)
                    .background(c.bg2).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = UFont.sans(18), color = c.ink2) }

            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val live = state == VoiceState.SPEAKING || state == VoiceState.LISTENING
                PulsingOrb(
                    active = live,
                    color = if (state == VoiceState.SPEAKING) c.coral else c.primary,
                    onClick = if (live) ({ client?.interrupt() }) else null,
                )
                Text(
                    when (state) {
                        VoiceState.CONNECTING -> "Connecting…"
                        VoiceState.LISTENING -> "Listening…"
                        VoiceState.SPEAKING -> "Speaking…"
                        VoiceState.ERROR -> note ?: "Something went wrong."
                        VoiceState.CLOSED -> "Ended"
                    },
                    style = UFont.sans(15, FontWeight.Medium), color = c.ink2,
                )
                if (caption.isNotBlank()) {
                    Text(caption, style = UFont.serifItalic(22), color = c.ink, textAlign = TextAlign.Center)
                }
                // Manual interrupt — cut the model's speech and let the user talk.
                if (live) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2)
                            .clickable { client?.interrupt() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) { Text("Interrupt", style = UFont.sans(15, FontWeight.SemiBold), color = c.ink) }
                }
            }

            // End button
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(999.dp)).background(c.coral).clickable { onClose() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) { Text("End", style = UFont.sans(15, FontWeight.SemiBold), color = Color.White) }
        }
    }
}

@Composable
private fun PulsingOrb(active: Boolean, color: Color, onClick: (() -> Unit)? = null) {
    val transition = rememberInfiniteTransition(label = "orb")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = if (active) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
    )
    Box(
        Modifier.size(120.dp).scale(scale).clip(CircleShape).background(color)
            .let { if (onClick != null) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center,
    ) { Text("●", style = UFont.sans(36), color = Color.White.copy(alpha = 0.9f)) }
}
