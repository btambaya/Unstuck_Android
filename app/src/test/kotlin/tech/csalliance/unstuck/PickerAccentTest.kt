package tech.csalliance.unstuck

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.design.theme.UnstuckColors

// The PLATFORM date / time pickers (android.app.DatePickerDialog /
// TimePickerDialog — Schedule, the repeat end date, the call hours, a
// collection's "by" time) paint their header, selected day and clock hand with
// the XML theme's colorAccent. It was Material's default teal; selection is the
// black-and-white ink / bg pair everywhere (owner, 2026-09-24), so it is the
// palette's ink — and this keeps the XML hex and the Compose token together.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PickerAccentTest {

    private fun accentOf(style: Int): Int {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), style)
        val tv = TypedValue()
        assertTrue(ctx.theme.resolveAttribute(android.R.attr.colorAccent, tv, true))
        return tv.data
    }

    private fun near(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { sh -> kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 1 } &&
            (a ushr 24) == (b ushr 24)

    @Test fun theActivityThemesPickersUseTheLightInk() {
        val accent = accentOf(R.style.Theme_Unstuck)
        assertTrue("Theme.Unstuck colorAccent #%08X".format(accent), near(accent, UnstuckColors.light.ink.toArgb()))
    }

    @Test fun theLightAndDarkPickerDialogsUseTheirPalettesInk() {
        val light = accentOf(R.style.Theme_Unstuck_PickerLight)
        val dark = accentOf(R.style.Theme_Unstuck_PickerDark)
        assertTrue("PickerLight colorAccent #%08X".format(light), near(light, UnstuckColors.light.ink.toArgb()))
        assertTrue("PickerDark colorAccent #%08X".format(dark), near(dark, UnstuckColors.dark.ink.toArgb()))
    }
}
