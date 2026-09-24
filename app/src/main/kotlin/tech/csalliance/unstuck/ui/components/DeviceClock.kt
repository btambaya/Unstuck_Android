package tech.csalliance.unstuck.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tech.csalliance.unstuck.core.time.ClockMode

/**
 * The thin accessor that supplies the phone's 12/24-hour preference to
 * [tech.csalliance.unstuck.core.time.ClockFormat] — the ONE rule for every
 * clock time the user sees (Ahmad, 2026-09-24). Android's own answer:
 * Settings › System › Date & time › "Use 24-hour format", or the locale's
 * default while that is left on automatic.
 */
object DeviceClock {
    fun mode(context: Context): ClockMode =
        if (DateFormat.is24HourFormat(context)) ClockMode.H24 else ClockMode.H12
}

/** The mode for the screens under [ProvideDeviceClock]; null outside it. */
val LocalClockMode = compositionLocalOf<ClockMode?> { null }

/** Supplies [LocalClockMode] to everything inside, re-read whenever the app
 *  comes back to the front — the only way to flip the phone's setting is from
 *  Settings, so a change shows the moment they return. */
@Composable
fun ProvideDeviceClock(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(DeviceClock.mode(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) mode = DeviceClock.mode(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    CompositionLocalProvider(LocalClockMode provides mode) { content() }
}

/** The phone's 12/24-hour mode for this composition (the provided one, else
 *  read straight from the device). */
@Composable
fun clockMode(): ClockMode = LocalClockMode.current ?: DeviceClock.mode(LocalContext.current)
