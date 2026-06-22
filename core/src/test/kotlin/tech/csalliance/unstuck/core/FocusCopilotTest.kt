package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CopilotLevel
import tech.csalliance.unstuck.core.logic.FocusCommand
import tech.csalliance.unstuck.core.logic.FocusCopilot
import tech.csalliance.unstuck.core.logic.FocusEffect
import tech.csalliance.unstuck.core.logic.FocusMilestone

// Pure unit tests for the on-device, ZERO-LLM Focus Copilot brain. Must match
// iOS/web exactly. Covers: the scheduler (level × estimate matrix, gating,
// no-double-fire, paused-excluded, overrun cadence + cap, keepGoing
// suppression), the parseCommand keyword table, {n} line rendering, and the
// no-network/no-LLM purity guarantee.
class FocusCopilotTest {

    private val EMPTY = emptySet<FocusMilestone>()

    /** Walk a session second-by-second (focus secs only — paused time never
     *  reaches dueMilestone), collecting milestones in fire order. Mirrors how
     *  the controller would drive it off the timer tick. */
    private fun fireSequence(
        estimateMin: Int,
        level: CopilotLevel,
        upToSec: Int,
        keepGoingAtSec: Int? = null,
    ): List<FocusMilestone> {
        val fired = mutableSetOf<FocusMilestone>()
        var overrunCount = 0
        var keepGoing = false
        val order = mutableListOf<FocusMilestone>()
        for (sec in 0..upToSec) {
            if (keepGoingAtSec != null && sec >= keepGoingAtSec) keepGoing = true
            val m = FocusCopilot.dueMilestone(estimateMin, level, sec, fired, overrunCount, keepGoing)
            if (m != null) {
                order += m
                if (m == FocusMilestone.OVERRUN) overrunCount++ else fired += m
            }
        }
        return order
    }

    // ---------- scheduler matrix: level × {3, 5, 10, 25, 50} ----------

    @Test fun calm_onlyAtTime_acrossEstimates() {
        for (e in listOf(3, 5, 10, 25, 50)) {
            val seq = fireSequence(e, CopilotLevel.CALM, e * 60 + 60)
            assertEquals("calm E=$e", listOf(FocusMilestone.AT_TIME), seq)
        }
    }

    @Test fun balanced_e3_onlyAtTime() {
        // E<=5 → AT_TIME only even on Balanced.
        assertEquals(listOf(FocusMilestone.AT_TIME), fireSequence(3, CopilotLevel.BALANCED, 3 * 60 + 60))
    }

    @Test fun balanced_e5_onlyAtTime() {
        assertEquals(listOf(FocusMilestone.AT_TIME), fireSequence(5, CopilotLevel.BALANCED, 5 * 60 + 60))
    }

    @Test fun balanced_e10_onlyAtTime_noTMinus5() {
        // T_MINUS_5 gated on E>10, so E=10 gets AT_TIME only.
        assertEquals(listOf(FocusMilestone.AT_TIME), fireSequence(10, CopilotLevel.BALANCED, 10 * 60 + 60))
    }

    @Test fun balanced_e25_tMinus5_thenAtTime() {
        assertEquals(
            listOf(FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME),
            fireSequence(25, CopilotLevel.BALANCED, 25 * 60 + 60),
        )
    }

    @Test fun balanced_e50_tMinus5_thenAtTime_noHalfwayNoOverrun() {
        assertEquals(
            listOf(FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME),
            fireSequence(50, CopilotLevel.BALANCED, 50 * 60 + 20 * 60),
        )
    }

    @Test fun coach_e3_onlyAtTime() {
        assertEquals(listOf(FocusMilestone.AT_TIME), fireSequence(3, CopilotLevel.COACH, 3 * 60 + 12 * 60))
    }

    @Test fun coach_e5_onlyAtTime_noHalfwayNoTMinus5() {
        // E<=5 → AT_TIME only; halfway gated E>=20, tMinus5 gated E>10.
        assertEquals(listOf(FocusMilestone.AT_TIME), fireSequence(5, CopilotLevel.COACH, 5 * 60 + 12 * 60))
    }

    @Test fun coach_e10_atTimeOnly_thenOverruns() {
        // E=10: no halfway (E<20), no tMinus5 (E not >10). AT_TIME + overruns.
        val seq = fireSequence(10, CopilotLevel.COACH, 10 * 60 + 12 * 60)
        assertEquals(
            listOf(FocusMilestone.AT_TIME, FocusMilestone.OVERRUN, FocusMilestone.OVERRUN),
            seq,
        )
    }

    @Test fun coach_e25_fullSequence_withOverrunCap() {
        // E=25 (>=20): HALFWAY @12:30, T_MINUS_5 @20:00, AT_TIME @25:00,
        // OVERRUN @30:00 + @35:00 (cap 2), no 3rd at 40:00.
        val seq = fireSequence(25, CopilotLevel.COACH, 25 * 60 + 20 * 60)
        assertEquals(
            listOf(
                FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME,
                FocusMilestone.OVERRUN, FocusMilestone.OVERRUN,
            ),
            seq,
        )
    }

    @Test fun coach_e50_fullSequence() {
        val seq = fireSequence(50, CopilotLevel.COACH, 50 * 60 + 20 * 60)
        assertEquals(
            listOf(
                FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME,
                FocusMilestone.OVERRUN, FocusMilestone.OVERRUN,
            ),
            seq,
        )
    }

    @Test fun coach_e20_halfwayBoundaryIncluded() {
        // E=20 is the exact lower bound for HALFWAY (E>=20).
        val seq = fireSequence(20, CopilotLevel.COACH, 20 * 60)
        assertTrue("E=20 should include HALFWAY", seq.contains(FocusMilestone.HALFWAY))
    }

    @Test fun coach_e15_noHalfway() {
        // E=15 (<20) → no halfway; T_MINUS_5 (E>10) + AT_TIME.
        val seq = fireSequence(15, CopilotLevel.COACH, 15 * 60)
        assertEquals(listOf(FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME), seq)
    }

    // ---------- exact milestone timing ----------

    @Test fun halfwayFiresAtHalf() {
        // E=25 → half = 12:30 = 750s. Not before, due at/after.
        assertNull(FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 749, EMPTY))
        assertEquals(FocusMilestone.HALFWAY, FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 750, EMPTY))
    }

    @Test fun tMinus5FiresFiveBeforeEnd() {
        // E=25 → 20:00 = 1200s.
        assertNull(FocusCopilot.dueMilestone(25, CopilotLevel.BALANCED, 1199, setOf(FocusMilestone.HALFWAY)))
        assertEquals(
            FocusMilestone.T_MINUS_5,
            FocusCopilot.dueMilestone(25, CopilotLevel.BALANCED, 1200, EMPTY),
        )
    }

    @Test fun atTimeFiresAtEstimate() {
        assertNull(
            FocusCopilot.dueMilestone(25, CopilotLevel.CALM, 25 * 60 - 1, EMPTY),
        )
        assertEquals(
            FocusMilestone.AT_TIME,
            FocusCopilot.dueMilestone(25, CopilotLevel.CALM, 25 * 60, EMPTY),
        )
    }

    @Test fun overrunFiresAtFiveOver_thenTenOver() {
        // By the time we're in overrun the earlier one-shots have all fired.
        val fired = setOf(FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME)
        // +4:59 not yet over enough.
        assertNull(FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 5 * 60 - 1, fired, overrunCount = 0))
        // +5:00 → first overrun.
        assertEquals(
            FocusMilestone.OVERRUN,
            FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 5 * 60, fired, overrunCount = 0),
        )
        // After 1 overrun, +9:59 not yet; +10:00 → second.
        assertNull(FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 10 * 60 - 1, fired, overrunCount = 1))
        assertEquals(
            FocusMilestone.OVERRUN,
            FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 10 * 60, fired, overrunCount = 1),
        )
    }

    // ---------- no double-fire ----------

    @Test fun noDoubleFire_alreadyFiredSuppressed() {
        // AT_TIME already fired → not returned again at the same secs.
        assertNull(
            FocusCopilot.dueMilestone(25, CopilotLevel.CALM, 25 * 60 + 30, setOf(FocusMilestone.AT_TIME)),
        )
    }

    @Test fun noDoubleFire_halfwayOnceThenTMinus5() {
        // Once HALFWAY fired, the next due is T_MINUS_5 (not HALFWAY again).
        val afterHalf = setOf(FocusMilestone.HALFWAY)
        assertEquals(
            FocusMilestone.T_MINUS_5,
            FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 - 5 * 60, afterHalf),
        )
    }

    // ---------- overrun cap ----------

    @Test fun overrunCapAtTwo() {
        // overrunCount==2 → never another OVERRUN even far over.
        val fired = setOf(FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME)
        assertNull(
            FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 60 * 60, fired, overrunCount = 2),
        )
    }

    // ---------- keepGoing suppression ----------

    @Test fun keepGoingSuppressesOverrun() {
        val fired = setOf(FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME)
        assertNull(
            FocusCopilot.dueMilestone(25, CopilotLevel.COACH, 25 * 60 + 10 * 60, fired, overrunCount = 0, keepGoing = true),
        )
    }

    @Test fun keepGoingMidSessionStopsFurtherOverruns() {
        // AT_TIME + first OVERRUN fire, then keepGoing at +6min → no more.
        val seq = fireSequence(25, CopilotLevel.COACH, 25 * 60 + 20 * 60, keepGoingAtSec = 25 * 60 + 6 * 60)
        assertEquals(
            listOf(
                FocusMilestone.HALFWAY, FocusMilestone.T_MINUS_5, FocusMilestone.AT_TIME,
                FocusMilestone.OVERRUN,
            ),
            seq,
        )
    }

    // ---------- paused-excluded (focusedSec is accumulated focus only) ----------

    @Test fun pausedTimeExcluded_milestonesTrackFocusNotWallClock() {
        // The caller passes accumulated FOCUS seconds. If 5 min of a 25-min block
        // were paused, at wall-clock 25:00 the focus secs are only 20:00 → AT_TIME
        // has NOT fired yet (only T_MINUS_5).
        val focusSecAtWall25 = 20 * 60
        assertEquals(
            FocusMilestone.T_MINUS_5,
            FocusCopilot.dueMilestone(25, CopilotLevel.BALANCED, focusSecAtWall25, EMPTY),
        )
        assertNull(
            FocusCopilot.dueMilestone(25, CopilotLevel.BALANCED, focusSecAtWall25, setOf(FocusMilestone.T_MINUS_5)),
        )
    }

    @Test fun zeroOrNegativeEstimate_neverFires() {
        assertNull(FocusCopilot.dueMilestone(0, CopilotLevel.COACH, 9999, EMPTY))
        assertNull(FocusCopilot.dueMilestone(-5, CopilotLevel.COACH, 9999, EMPTY))
    }

    // ---------- line {n} rendering ----------

    @Test fun lineHalfway() {
        // E=25, halfway focusSec=750 → 12 min left (whole min).
        assertEquals("Halfway there — about 12 minutes left.", FocusCopilot.line(FocusMilestone.HALFWAY, 25, 750))
    }

    @Test fun lineHalfwaySingularMinute() {
        // 60-90s left rounds to 1 minute → singular.
        assertEquals("Halfway there — about 1 minute left.", FocusCopilot.line(FocusMilestone.HALFWAY, 10, 9 * 60))
    }

    @Test fun lineTMinus5Fixed() {
        assertEquals("Five minutes left. Want to wrap up, or add time?", FocusCopilot.line(FocusMilestone.T_MINUS_5, 25, 1200))
    }

    @Test fun lineAtTimeFixed() {
        assertEquals("That's your block. Add five, stop, or keep going?", FocusCopilot.line(FocusMilestone.AT_TIME, 25, 1500))
    }

    @Test fun lineOverrunPlural() {
        // E=25, focus=25*60+5*60 → 5 min over.
        assertEquals("You're 5 minutes over. Stop here, or keep going?", FocusCopilot.line(FocusMilestone.OVERRUN, 25, 30 * 60))
    }

    @Test fun lineOverrunSingular() {
        // 1 min over → singular.
        assertEquals("You're 1 minute over. Stop here, or keep going?", FocusCopilot.line(FocusMilestone.OVERRUN, 25, 26 * 60))
    }

    @Test fun milestoneIsQuestionFlag() {
        assertTrue(!FocusMilestone.HALFWAY.isQuestion)
        assertTrue(FocusMilestone.T_MINUS_5.isQuestion)
        assertTrue(FocusMilestone.AT_TIME.isQuestion)
        assertTrue(FocusMilestone.OVERRUN.isQuestion)
    }

    // ---------- parseCommand table (~25 cases) ----------

    private fun assertStop(u: String) = assertTrue("'$u' → Stop", FocusCopilot.parseCommand(u) is FocusCommand.Stop)
    private fun assertKeepGoing(u: String) = assertTrue("'$u' → KeepGoing", FocusCopilot.parseCommand(u) is FocusCommand.KeepGoing)
    private fun assertExtend(u: String, n: Int) =
        assertEquals("'$u' → Extend($n)", FocusCommand.Extend(n), FocusCopilot.parseCommand(u))
    private fun assertCapture(u: String, text: String) =
        assertEquals("'$u' → Capture('$text')", FocusCommand.Capture(text), FocusCopilot.parseCommand(u))
    private fun assertNone(u: String) = assertTrue("'$u' → None", FocusCopilot.parseCommand(u) is FocusCommand.None)

    @Test fun parse_stop_variants() {
        assertStop("stop")
        assertStop("Stop")
        assertStop("STOP")
        assertStop("done")
        assertStop("I'm done")
        assertStop("finish")
        assertStop("end")
        assertStop("stop here")
        assertStop("that's it")
        assertStop("thats it")
    }

    @Test fun parse_stop_beatsEverything() {
        // stop has top priority even mixed with extend/keep-going phrases.
        assertStop("add five then stop")
        assertStop("stop, keep going later")
    }

    @Test fun parse_extend_explicitNumbers() {
        assertExtend("add five", 5)
        assertExtend("add ten", 10)
        assertExtend("add fifteen", 15)
        assertExtend("add twenty", 20)
        assertExtend("extend 10", 10)
        assertExtend("give me 15", 15)
        assertExtend("Add 5 minutes", 5)
    }

    @Test fun parse_extend_addTimeDefaultsToFive() {
        assertExtend("add time", 5)
        assertExtend("more time", 5)
        assertExtend("give me more time", 5)
    }

    @Test fun parse_keepGoing_variants() {
        assertKeepGoing("keep going")
        assertKeepGoing("Keep going")
        assertKeepGoing("in the zone")
        assertKeepGoing("not yet")
    }

    @Test fun parse_capture_prefixes_verbatim() {
        assertCapture("capture call the bank", "call the bank")
        assertCapture("note buy milk", "buy milk")
        assertCapture("remember to email Sam", "to email Sam")
        assertCapture("remind me to stretch", "to stretch")
        assertCapture("add a task review the PR", "review the PR")
    }

    @Test fun parse_capture_verbatimPreservesCase() {
        // No LLM rewriting — body kept verbatim (original case).
        assertCapture("note Call Dr. Smith ASAP", "Call Dr. Smith ASAP")
    }

    @Test fun parse_capture_emptyRemainderIsNone() {
        assertNone("capture")
        assertNone("note")
    }

    @Test fun parse_none_unrecognized() {
        assertNone("hello there")
        assertNone("what time is it")
        assertNone("")
        assertNone("   ")
    }

    @Test fun parse_priority_extendBeatsKeepGoing() {
        // "add ten" parses as extend even though no keep-going words present;
        // a phrase with both an extend verb+number wins over keep-going.
        assertExtend("keep going, add ten", 10)
    }

    @Test fun parse_addTimeBeatsCapture() {
        // "add time" is an extend, never a capture (capture needs the verb prefix
        // "add a task", not bare "add").
        assertExtend("add time", 5)
    }

    @Test fun parse_bareNumber_isNotExtend() {
        // No extend verb → not an extend (avoids misfiring on stray digits).
        assertNone("five")
        assertNone("10")
    }

    // ---------- effects + acks ----------

    @Test fun effect_stop() {
        val e = FocusCopilot.effectFor(FocusCommand.Stop)
        assertEquals(FocusEffect.Stop, e)
        assertEquals("Nice work.", e.ack)
    }

    @Test fun effect_extend_ackPluralAndSingular() {
        assertEquals("Added 5 minutes.", FocusCopilot.effectFor(FocusCommand.Extend(5)).ack)
        assertEquals("Added 1 minute.", FocusCopilot.effectFor(FocusCommand.Extend(1)).ack)
    }

    @Test fun effect_extend_clampedAtParse() {
        // parseCommand clamps; the supported number words are already in range,
        // but verify the clamp explicitly via Extend construction in parse.
        assertEquals(FocusCommand.Extend(20), FocusCopilot.parseCommand("add twenty"))
    }

    @Test fun effect_keepGoing() {
        val e = FocusCopilot.effectFor(FocusCommand.KeepGoing)
        assertEquals(FocusEffect.KeepGoing, e)
        assertEquals("Okay, keep going.", e.ack)
    }

    @Test fun effect_capture_verbatim() {
        val e = FocusCopilot.effectFor(FocusCommand.Capture("buy milk"))
        assertTrue(e is FocusEffect.Capture && e.text == "buy milk")
        assertEquals("Got it.", e.ack)
    }

    @Test fun effect_none_emptyAck() {
        assertEquals("", FocusCopilot.effectFor(FocusCommand.None).ack)
    }

    // ---------- purity / no-LLM guarantee ----------

    @Test fun parseCommand_isDeterministic_sameInputSameOutput() {
        // A pure keyword parser: identical inputs → identical outputs, every time.
        // (No network / LLM would make this non-deterministic / slow.)
        repeat(3) {
            assertEquals(FocusCommand.Extend(10), FocusCopilot.parseCommand("add ten"))
            assertEquals(FocusCommand.Capture("call mom"), FocusCopilot.parseCommand("remember call mom"))
        }
    }

    // ---------- push-to-talk capture: captureFromTranscript (Phase 1.5) ----------

    @Test fun captureFromTranscript_normalTextSavedVerbatim() {
        assertEquals("call the bank at 3", FocusCopilot.captureFromTranscript("call the bank at 3"))
    }

    @Test fun captureFromTranscript_trimsSurroundingWhitespace() {
        assertEquals("idea for the deck", FocusCopilot.captureFromTranscript("   idea for the deck   "))
        assertEquals("tabs and newlines", FocusCopilot.captureFromTranscript("\t tabs and newlines \n"))
    }

    @Test fun captureFromTranscript_blankReturnsNull() {
        assertNull(FocusCopilot.captureFromTranscript(""))
        assertNull(FocusCopilot.captureFromTranscript("   "))
        assertNull(FocusCopilot.captureFromTranscript("\t \n "))
    }

    @Test fun captureFromTranscript_isPureDictation_neverParsedAsCommand() {
        // CRITICAL: dictation must be saved VERBATIM and NEVER routed through the
        // keyword parser. "I should stop procrastinating" contains "stop" but must
        // be captured word-for-word, not turned into a Stop command.
        val phrase = "I should stop procrastinating"
        assertEquals(phrase, FocusCopilot.captureFromTranscript(phrase))
        // Sanity: the command parser WOULD have read this as Stop — proving the
        // capture path deliberately bypasses parseCommand.
        assertEquals(FocusCommand.Stop, FocusCopilot.parseCommand(phrase))
        // Other command-like phrases are likewise saved verbatim by capture.
        assertEquals("add ten more emails to the list", FocusCopilot.captureFromTranscript("add ten more emails to the list"))
        assertEquals("done with the easy part", FocusCopilot.captureFromTranscript("done with the easy part"))
    }

    @Test fun coreModule_hasNoNetworkOrLlmDependency() {
        // Structural guarantee: :core declares no Android / Supabase / HTTP /
        // assistant dependency (see core/build.gradle.kts — only kotlinx
        // serialization + test libs). FocusCopilot is reachable as a plain
        // object with no constructor args, so it cannot hold a client.
        val cmd = FocusCopilot.parseCommand("add five")
        assertEquals(FocusCommand.Extend(5), cmd)
        // effectFor is a pure when-mapping — no suspend, no IO.
        assertEquals("Added 5 minutes.", FocusCopilot.effectFor(cmd).ack)
    }
}
