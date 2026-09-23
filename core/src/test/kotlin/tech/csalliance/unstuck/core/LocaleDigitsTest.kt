package tech.csalliance.unstuck.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantClock
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.findFreeSlots
import tech.csalliance.unstuck.core.logic.hmOfMillis
import tech.csalliance.unstuck.core.logic.isoToLocalHHMM
import tech.csalliance.unstuck.core.logic.isoToLocalYmd
import tech.csalliance.unstuck.core.logic.localNowHM
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.minToHM
import tech.csalliance.unstuck.core.logic.nextWeekend
import tech.csalliance.unstuck.core.logic.resolveSharedSlot
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.logic.wakeWindowSample
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.core.time.WireTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// Every date and time the app stores, syncs, compares or sends is ASCII on a
// phone whose language writes its own digits. A bare "%02d".format(…) follows
// the default locale, so on ar-EG / fa-IR (and bn, mr, ne, my) todayIso read
// "٢٠٢٦-٠٩-٢٣", scheduled tasks fell out of Today and the server refused the
// blocks (Android audit 2026-09-23, A12). Each case runs under every locale.
class LocaleDigitsTest {
    private val nativeDigitLocales = listOf("ar-EG", "fa-IR", "bn-BD", "mr-IN", "ne-NP", "my-MM").map(Locale::forLanguageTag)
    private lateinit var saved: Locale
    private val ymd = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    @Before fun save() { saved = Locale.getDefault() }
    @After fun restore() { Locale.setDefault(saved) }

    private fun underEachLocale(body: (Locale) -> Unit) {
        for (l in nativeDigitLocales) {
            Locale.setDefault(l)
            body(l)
        }
    }

    @Test fun theJvmReallyWritesNativeDigitsForTheseLocales() {
        // Guards the test itself: if the JDK stopped localising %d, every other
        // case here would pass for the wrong reason.
        underEachLocale { l -> assertTrue("$l", "%02d".format(7) != "07") }
    }

    @Test fun clockDatesAreAscii() {
        val ms = Time.civil(2026, 9, 23) + 10 * 3_600_000L
        underEachLocale { l ->
            assertEquals("$l", "2026-09-23", Clock.dateIso(ms))
            assertTrue("$l", ymd.matches(Clock.todayIso()))
        }
    }

    @Test fun wireTimeHelpersAreAscii() {
        underEachLocale { l ->
            assertEquals("$l", "2026-09-03", WireTime.ymd(LocalDate.of(2026, 9, 3)))
            assertEquals("$l", "07:05", WireTime.hm(7, 5))
            assertEquals("$l", "09", WireTime.pad2(9))
        }
    }

    @Test fun asciiDigitsHealsEveryNativeDigitSetAndKeepsTheRest() {
        assertEquals("2026-09-23", WireTime.asciiDigits("٢٠٢٦-٠٩-٢٣"))   // Arabic-Indic
        assertEquals("10:30", WireTime.asciiDigits("۱۰:۳۰"))              // Persian
        assertEquals("08:45", WireTime.asciiDigits("০৮:৪৫"))              // Bengali
        assertEquals("2026-01-02", WireTime.asciiDigits("२०२६-०१-०२"))    // Devanagari (mr, ne)
        assertEquals("12:00", WireTime.asciiDigits("၁၂:၀၀"))              // Myanmar
        val ascii = "2026-09-23"
        assertTrue("an ASCII string comes back as is", ascii === WireTime.asciiDigits(ascii))
        assertEquals("letters and punctuation stay", "Tâche 2 — مهمة", WireTime.asciiDigits("Tâche 2 — مهمة"))
    }

    @Test fun assistantDatesAndTimesAreAscii() {
        val clock = AssistantClock(nowMs = { Time.parseMillis("2026-09-23T09:07:00.000Z")!! }, zone = ZoneId.of("UTC"))
        underEachLocale { l ->
            assertEquals("$l", "2026-09-23", clock.todayIso())
            assertEquals("$l", "2026-09-23", IsoDate.format(LocalDate.of(2026, 9, 23)))
            assertEquals("$l", "2026-09-30", IsoDate.addDays("2026-09-23", 7))
            assertEquals("$l", "09:05", minToHM(9 * 60 + 5))
            assertEquals("$l", "09:07", localNowHM(Time.parseMillis("2026-09-23T09:07:00.000Z")!!, ZoneId.of("UTC")))
            assertEquals("$l", "2026-09-26" to "2026-09-27", nextWeekend("2026-09-23"))
        }
    }

    @Test fun freeSlotsWriteAsciiStartTimes() {
        val now = localMillis(2026, 5, 21, 7, 0)
        underEachLocale { l ->
            val slot = findFreeSlots(emptyList(), 30, now, startDate = now, daysToScan = 1, limit = 1).first()
            assertEquals("$l", "2026-05-21", slot.date)
            assertEquals("$l", "08:00", slot.startTime)
        }
    }

    @Test fun aRepeatWithAnEndDateStillMaterialises() {
        // The picker writes the end date in ASCII; a native-digit day sorted above
        // it and the series stopped on day 0.
        underEachLocale { l ->
            val occ = materializeOccurrences(Recurrence.Daily(until = "2026-05-23"), Time.civil(2026, 5, 21), "09:00", 14)
            assertEquals("$l", listOf("2026-05-21", "2026-05-22", "2026-05-23"), occ.map { it.date })
        }
    }

    @Test fun aServerBlockForTodayIsInTodayNotBacklog() {
        // The server's date for today is ASCII, computed without any formatter.
        // The task is older than a day, so only its block can put it in Today.
        val serverToday = LocalDate.now(ZoneId.systemDefault()).toString()
        val task = mkTask(id = "t1", createdAt = "2026-05-01T10:00:00.000Z")
        val block = mkBlock(id = "b1", taskId = "t1", date = serverToday)
        underEachLocale { l ->
            val today = visibleTasks(TaskListView.TODAY, listOf(task), listOf(block), NOW, null, slipMode = false)
            assertEquals("$l", listOf("t1"), today.map { it.id })
            val backlog = visibleTasks(TaskListView.BACKLOG, listOf(task), listOf(block), NOW, null, slipMode = false)
            assertEquals("$l: not overdue", emptyList<String>(), backlog.map { it.id })
        }
    }

    @Test fun wakeWindowSampleIsAscii() {
        val ms = Time.parseMillis("2026-07-18T23:30:00.000Z")!!
        underEachLocale { l ->
            val s = wakeWindowSample(ms, ZoneId.of("Asia/Tokyo"))
            assertEquals("$l", "2026-07-19", s.localDate)
            assertEquals("$l", "08:30", s.firstInputLocal)
        }
    }

    @Test fun callTimesAreAsciiAndAStoredNativeTimeHeals() {
        val ms = Time.parseMillis("2026-09-23T06:05:00.000Z")!!
        underEachLocale { l ->
            assertEquals("$l", "06:05", CallSettingsLogic.hhmm(ms, ZoneId.of("UTC")))
            assertEquals("$l", "08:30", CallProactivePrefs.hhmm("08:30:00"))
            // What an older build saved from the picker on this phone: the push
            // now sends it as ASCII instead of failing for ever.
            val stored = """{"morningEnabled":true,"morningTime":"٠٨:٣٠","eveningTime":"۱۸:۰۰"}"""
            val p = CallProactivePrefs.fromJson(stored)
            assertEquals("$l", "08:30", p.morningTime)
            assertEquals("$l", "18:00", p.eveningTime)
        }
    }

    @Test fun callHoursSentencesUseOneDigitScript() {
        // The allowed hours beside these minutes are ASCII now, so on a Persian phone
        // the warning read "about ۲۱:۰۰ … (08:00–21:00; the latest it rings is ۲۰:۵۹)",
        // and the model got the same mix in the refusal.
        underEachLocale { l ->
            assertEquals("$l", "08:00–21:00; the latest it rings is 20:59", CallSettingsLogic.hoursLabel("08:00", "21:00", 21 * 60))
            assertEquals(
                "$l",
                "Unstuck rings this call at about 21:00, outside this phone's allowed hours (08:00–21:00; the latest it rings is 20:59), so it's declined here — widen the hours above or pick another time.",
                CallSettingsLogic.proactiveTimeWarning("20:58", enabled = true, start = "08:00", end = "21:00"),
            )
        }
    }

    @Test fun briefClockGoogleMirrorAndSharedSlotsAreAscii() {
        val ms = Time.parseMillis("2026-09-23T14:05:00.000Z")!!
        underEachLocale { l ->
            assertEquals("$l", "14:05", hmOfMillis(ms))
            assertEquals("$l", "2026-09-23", isoToLocalYmd("2026-09-23T14:05:00Z"))
            assertEquals("$l", "14:05", isoToLocalHHMM("2026-09-23T14:05:00Z"))
            val slot = resolveSharedSlot("2026-09-23T08:00:00Z", "2026-09-23", "08:00", ZoneId.of("Europe/Berlin"))
            assertEquals("$l", "2026-09-23", slot.date)
            assertEquals("$l", "10:00", slot.time)
        }
    }
}
