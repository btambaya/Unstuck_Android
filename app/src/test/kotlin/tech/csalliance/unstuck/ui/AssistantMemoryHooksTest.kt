package tech.csalliance.unstuck.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.ReceiptUndoKind
import tech.csalliance.unstuck.core.logic.ReceiptUndoRefusal
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.sync.PreferencesClient
import tech.csalliance.unstuck.sync.ToolCall
import tech.csalliance.unstuck.sync.ToolFunction
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.assistant.receiptUndoKey

/**
 * The gateway A0 hooks on [AppViewModel]: the deterministic style-preference save
 * before a message reaches the model, the per-account rituals / dismissals /
 * interview-flag caches (SharedPreferences, `<key>.<uid>`), how a pull pins them
 * from the server (`user_preferences`), and the sign-out scrub. Same SUT shape as
 * AppViewModelTest: a real in-memory Room store + WriteThrough, no coordinator
 * (graph.configured=false), viewModelScope on a StandardTestDispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AssistantMemoryHooksTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()
    private var uid: String? = "me"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
        write = WriteThrough(graph.store)
    }

    @After fun teardown() {
        // Drain the main looper first. Each AppViewModel built here posts to it
        // (init → CallVoiceService.bind → main().post { … }) and nothing here runs
        // that, so a test used to end with runnables still queued — Robolectric's
        // "Main looper has queued unexecuted runnables" note, and one test's work
        // left to fire inside whichever test idles the looper next.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // ...and FINISH this test's ViewModels before Main is handed back: cancelling
        // one only asks, and a child still unwinding on a real Room thread resumes on
        // Dispatchers.Main, which by then belongs to the next test. See ViewModelDrain.
        drain.drain()
        Dispatchers.resetMain()
    }

    /** Finishes every ViewModel this test built — see [ViewModelDrain]. */
    private val drain = ViewModelDrain(dispatcher.scheduler)

    /**
     * Build the SUT — and make sure it DIES WITH THE TEST. `onCleared()` never runs
     * in a unit test, so without this every AppViewModel kept its `viewModelScope`
     * (WhileSubscribed StateFlows collecting Room on a real Default thread, plus any
     * in-flight write) alive past the test. `viewModelScope` is Dispatchers.Main,
     * which @Before/@After swap per test: a leaked Room continuation resuming just
     * as Main was swapped threw "Dispatchers.Main is used concurrently with setting
     * it", and once the next test called setMain the leftovers resumed onto the NEXT
     * test's scheduler. runTest cancels backgroundScope after the body and then
     * drains the scheduler, so unwinding here happens while it is still live.
     */
    private fun kotlinx.coroutines.test.TestScope.vm() =
        AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { uid }, currentNameProvider = { "Ada" })
            .also { created ->
                drain.track(created)
                backgroundScope.coroutineContext.job.invokeOnCompletion {
                    runCatching { created.viewModelScope.cancel() }
                }
            }

    /** Drive the VM's coroutines until the turn has settled. The style save awaits
     *  Room suspend calls on a REAL executor thread, which the virtual scheduler
     *  can't advance through — so alternate advanceUntilIdle with a short real
     *  wait until [assistantSending] has flipped back (the turn's `finally`). */
    private fun kotlinx.coroutines.test.TestScope.settleTurn(vm: AppViewModel) {
        repeat(300) {
            advanceUntilIdle()
            if (!vm.assistantSending.value) {
                // The flag flips INSIDE the turn's `finally`, so the job's own
                // completion is still resuming from a real Dispatchers.Default
                // thread. Give it a beat and drain again, or teardown's
                // resetMain() races it ("Main is used concurrently with setting it").
                Thread.sleep(25)
                advanceUntilIdle()
                return
            }
            Thread.sleep(10)
        }
        error("assistant turn never settled")
    }

    /** One executor tool call against the live ViewModel state (the harness's
     *  own entry, minus the scratch the tests here don't need). */
    private suspend fun AppViewModel.tool(name: String, args: kotlinx.serialization.json.JsonObject = buildJsonObject { }): String =
        runAssistantTool(name, args, HashMap<String, TaskItem>(), HashMap<String, ItemCollection>())

    /** Drain the VM's coroutines until [cond] holds. Some steps await Room on a
     *  REAL executor thread, which the virtual scheduler can't advance through. */
    private fun kotlinx.coroutines.test.TestScope.settleUntil(cond: () -> Boolean) {
        repeat(300) {
            advanceUntilIdle()
            if (cond()) { Thread.sleep(20); advanceUntilIdle(); return }
            Thread.sleep(10)
        }
        error("condition never settled")
    }

    private suspend fun facts(): List<ProfileFact> = store.profileFacts().first()
    private suspend fun pendingFactOps() = store.pending().filter { it.recordTable == Tables.PROFILE_FACTS }
    private fun serverPrefs(doneAt: String? = null, rituals: String? = null) = PreferencesClient.ServerUserPrefs(
        assistant_interview_done_at = doneAt,
        pa_rituals = rituals?.let { Json.parseToJsonElement(it).jsonObject },
    )

    // ── style preference before the model sees the message ─────────────────

    @Test fun sendAssistant_savesANoNamePreferenceDeterministicallyBeforeTheTurn() = runTest {
        val vm = vm()
        vm.sendAssistant("please stop saying my name")
        settleTurn(vm)
        // No coordinator → the turn itself fails "not_configured", but the app
        // has already remembered the preference (web + iOS parity).
        assertEquals("not_configured", vm.assistantError.value)
        val f = facts().single()
        assertEquals(ProfileFactCategory.PREFERENCE, f.category)
        assertEquals("Don't use their name in replies", f.fact)
        assertEquals(ProfileFactSource.CHAT, f.source)
        assertTrue(f.active)
        assertEquals("queued for the account", 1, pendingFactOps().size)
        // The live projection + the derived readers see it.
        backgroundScope.launch { vm.profileFacts.collect { } }
        vm.profileFacts.first { it.isNotEmpty() }
        assertTrue(vm.noNamePreference())
        assertNull(vm.preferredName())
        // Saying it again refines the same fact — no duplicate row.
        vm.sendAssistant("stop saying my name")
        settleTurn(vm)
        assertEquals(1, facts().size)
    }

    @Test fun sendAssistant_savesACallMePreferenceAndSkipsTaskishSentences() = runTest {
        val vm = vm()
        vm.sendAssistant("add a task: call me Sam at 5")
        settleTurn(vm)
        assertTrue("a task-ish sentence is not a preference", facts().isEmpty())
        vm.sendAssistant("Call me Chief from now on")
        settleTurn(vm)
        assertEquals("Call them Chief", facts().single().fact)
        backgroundScope.launch { vm.profileFacts.collect { } }
        vm.profileFacts.first { it.isNotEmpty() }
        assertEquals("Chief", vm.preferredName())
    }

    // ── rituals: per-account cache, pending push, server wins on pull ──────

    @Test fun rituals_defaultThenCachePerAccountAndFlagPending() = runTest {
        val vm = vm()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        vm.setRitual(RitualKey.FRIDAY, true)
        assertEquals(RitualPrefs(friday = true), vm.rituals.value)
        assertTrue("no coordinator to push to — stays pending for the next pull", vm.ritualsPendingPush("me"))
        // A different account on the same phone starts on defaults …
        uid = "other"
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertFalse(vm.ritualsPendingPush("other"))
        // … and the first account gets its own cache back.
        uid = "me"
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs(friday = true), vm.rituals.value)
        // A fresh ViewModel reads the same cache at construction.
        assertEquals(RitualPrefs(friday = true), vm().rituals.value)
    }

    @Test fun pull_serverRitualsWinUnlessALocalToggleIsStillPending() = runTest {
        val vm = vm()
        // Nothing pending: the account's value replaces the cache, missing keys default.
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = """{"sunday":true,"morning":false}"""))
        assertEquals(RitualPrefs(morning = false, sunday = true), vm.rituals.value)
        assertFalse(vm.ritualsPendingPush("me"))
        // A null column (never set anywhere) leaves the cache alone.
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = null))
        assertEquals(RitualPrefs(morning = false, sunday = true), vm.rituals.value)
        // A toggle made here that hasn't landed must NOT be pulled over.
        vm.setRitual(RitualKey.FRIDAY, true)
        assertTrue(vm.ritualsPendingPush("me"))
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = """{"friday":false}"""))
        assertEquals(RitualPrefs(morning = false, sunday = true, friday = true), vm.rituals.value)
        assertTrue("still pending: no coordinator could land it", vm.ritualsPendingPush("me"))
    }

    // ── interview flag: server pins local, local pushes up ──────────────────

    @Test fun pull_serverDoneFlagPinsLocalAndDropsAHalfWayResumeStep() = runTest {
        val vm = vm()
        assertFalse(vm.interviewDone.value)
        vm.setInterviewStep(3)
        assertTrue(vm.hasInterviewResumeStep())
        assertEquals(3, vm.interviewParkedStep(7))
        // Unknown / not done: nothing changes.
        vm.applyServerAssistantPrefs("me", null)
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = "garbage"))
        assertFalse(vm.interviewDone.value)
        assertEquals(3, vm.interviewParkedStep(7))
        // The account finished elsewhere (PostgREST timestamp shape).
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = "2026-09-05T10:00:00.123456+00:00"))
        assertTrue(vm.interviewDone.value)
        assertFalse("they finished elsewhere — no stale resume step", vm.hasInterviewResumeStep())
        assertNull(vm.interviewParkedStep(7))
        // Persisted per account: a fresh ViewModel sees it, another account doesn't.
        assertTrue(vm().interviewDone.value)
        uid = "other"
        assertFalse(vm().interviewDone.value)
    }

    @Test fun markInterviewDone_setsTheLocalFlagAndClearsTheStep() = runTest {
        val vm = vm()
        vm.setInterviewStep(2)
        vm.markInterviewDone()
        assertTrue(vm.interviewDone.value)
        assertFalse(vm.hasInterviewResumeStep())
        assertTrue(vm().interviewDone.value)
        // A pull that says "not done" never un-does a local done (it pushes instead).
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = null))
        assertTrue(vm.interviewDone.value)
    }

    @Test fun interviewStep_parsesAValidIndexOrRestarts() = runTest {
        val vm = vm()
        assertNull("nothing parked", vm.interviewParkedStep(7))
        vm.setInterviewStep(9)
        assertEquals("out of range → restart at the greeting", 0, vm.interviewParkedStep(7))
        vm.clearInterviewStep()
        assertNull(vm.interviewParkedStep(7))
        assertFalse(vm.hasInterviewResumeStep())
    }

    // ── moment dismissals: device-local, idempotent, persisted ──────────────

    @Test fun dismissMoment_isIdempotentAndPersistedPerAccount() = runTest {
        val vm = vm()
        vm.dismissMoment("evening-sweep:2026-08-29")
        vm.dismissMoment("evening-sweep:2026-08-29")
        assertEquals(listOf("evening-sweep:2026-08-29"), vm.dismissedMoments.value)
        assertTrue(vm.isMomentDismissed("evening-sweep:2026-08-29"))
        assertFalse(vm.isMomentDismissed("quiet-win:2026-08-29"))
        assertEquals(listOf("evening-sweep:2026-08-29"), vm().dismissedMoments.value)
        uid = "other"
        assertEquals(emptyList<String>(), vm().dismissedMoments.value)
    }

    // ── the hydrated flag + the sign-out scrub ──────────────────────────────

    @Test fun scrub_wipesLocalFactsAndEveryGatewayCache() = runTest {
        val vm = vm()
        assertNotNull(vm.saveProfileFact(ProfileFactCategory.PERSON, "Maleek — son", ProfileFactSource.INTERVIEW))
        vm.setRitual(RitualKey.SUNDAY, true)
        vm.dismissMoment("m1")
        vm.markInterviewDone()
        assertEquals(1, facts().size)
        vm.scrubAssistantUserState()
        assertEquals("the local memory is gone (the server keeps the account's facts)", 0, facts().size)
        assertEquals("the queued push keeps its own payload", 1, pendingFactOps().size)
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertEquals(emptyList<String>(), vm.dismissedMoments.value)
        assertFalse(vm.interviewDone.value)
        assertFalse(vm.profileFactsHydrated.value)
        // The file is cleared too: the next account (or the same one, re-signing in)
        // starts on defaults until its pull re-applies the server's word.
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertFalse(vm.interviewDone.value)
        assertFalse(vm.ritualsPendingPush("me"))
    }

    // ── review section 4 ────────────────────────────────────────────────────

    /** The style receipt IS the consent UX for a fact that then rides in every
     *  prompt — so it carries the same one-tap forget web and iOS attach. */
    @Test fun styleReceiptCarriesAOneTapForget() = runTest {
        val vm = vm()
        val r = vm.saveStylePreference("please stop saying my name")!!
        assertEquals("Noted: Don't use their name in replies", r.label)
        val stored = facts().single()
        assertEquals(ReceiptUndoKind.FORGET_FACT, r.undo?.kind)
        assertEquals(stored.id, r.undo?.id)
        assertTrue("and the undo target really exists", vm.forgetProfileFact(r.undo!!.id))
        assertNull("nothing to note in an ordinary message", vm.saveStylePreference("what's next?"))
    }

    /** Settings edit-in-place: the SAME row, id kept, updatedAt bumped; a blank
     *  edit reports why instead of silently doing nothing. */
    @Test fun updateProfileFact_rewritesTheRowInPlace() = runTest {
        val vm = vm()
        val f = vm.saveProfileFact(ProfileFactCategory.PERSON, "Maleek — son", ProfileFactSource.INTERVIEW)!!
        val edited = vm.updateProfileFact(f.id, "  Maleek — son, 9  ").getOrThrow()
        assertEquals(f.id, edited.id)
        assertEquals("Maleek — son, 9", edited.fact)
        assertEquals(ProfileFactCategory.PERSON, edited.category)
        assertEquals(1, facts().size)
        assertEquals("Maleek — son, 9", facts().single().fact)
        assertTrue(vm.updateProfileFact(f.id, "   ").isFailure)
    }

    /** A thread poisoned before the tool-call hygiene fix (arguments cut off by
     *  finish_reason=length) is REPAIRED on replay — every later turn used to
     *  400 forever and the only escape erased the conversation. */
    @Test fun replayNormalisesAPoisonedPersistedToolCall() = runTest {
        val vm = vm()
        fun msg(args: String) = ChatMessage(
            role = "assistant",
            toolCalls = listOf(ToolCall("c1", "function", ToolFunction("create_tasks", args))),
        )
        with(vm) {
            assertEquals("{}", msg("""{"tasks":[{"name":"a"},{"name":"b""").toHarness().toolCalls.single().argumentsJson)
            assertEquals("{}", msg("").toHarness().toolCalls.single().argumentsJson)
            assertEquals("""{"name":"A"}""", msg("""{"name":"A"}""").toHarness().toolCalls.single().argumentsJson)
        }
    }

    /** The executor contract: a list write RETURNS ONLY once the local row is
     *  committed, so a read tool in the SAME round sees it. The writes used to be
     *  fire-and-forget — "add milk then read the list back" showed no milk. */
    @Test fun listWritesAreCommittedBeforeTheNextToolReadsThem() = runTest {
        val vm = vm()
        val created = vm.runAssistantTool("create_list", buildJsonObject { put("name", "Shopping") }, HashMap(), HashMap())
        val id = Regex("id=(\\S+)").find(created)!!.groupValues[1].trimEnd(']')
        assertTrue(vm.tool("add_to_list", buildJsonObject { put("listId", id); put("body", "Milk") }).startsWith("ok: added \"Milk\" to \"Shopping\" id="))
        // No dispatcher hop between the two calls — the harness runs them back to back.
        assertTrue("the read must see the write", vm.tool("get_lists", buildJsonObject { put("listId", id) }).contains("Milk"))
        vm.tool("rename_list", buildJsonObject { put("listId", id); put("name", "Groceries") })
        assertTrue(vm.tool("get_lists").contains("Groceries"))
        vm.tool("archive_list", buildJsonObject { put("listId", id); put("archived", true) })
        assertEquals("ok: no lists yet", vm.tool("get_lists"))
        vm.tool("delete_list", buildJsonObject { put("listId", id) })
        assertEquals("ok: no lists yet", vm.tool("get_lists", buildJsonObject { put("includeArchived", true) }))
    }

    /** Everything the VOICE assistant writes gets the same receipt the text
     *  harness shows, and they land in the shared thread when the session ends —
     *  with their Undo still working. */
    @Test fun voiceToolReceiptsLandInTheThreadWhenTheSessionEnds() = runTest {
        val vm = vm()
        vm.resetVoiceScratch()
        val result = vm.runVoiceTool("create_task", buildJsonObject { put("name", "Call the dentist") })
        assertTrue(result, result.startsWith("ok"))
        val r = vm.voiceReceipts.value.single()
        assertEquals("Created “Call the dentist”", r.label)
        assertEquals(ReceiptUndoKind.DELETE_TASK, r.undo?.kind)
        assertTrue("nothing lands mid-session", vm.assistantHistory.none { it.content == VOICE_SESSION_RECEIPTS })

        vm.endVoiceSession()
        settleUntil { vm.assistantHistory.any { it.content == VOICE_SESSION_RECEIPTS } }
        val turn = vm.assistantHistory.single { it.content == VOICE_SESSION_RECEIPTS }
        assertTrue("display-only — never re-sent to the model", turn.local)
        assertEquals(listOf(r.label to r.undo), turn.receipts!!.map { it.label to it.undo!!.copy(stamps = emptyMap(), stamped = false) })
        assertTrue("stamped as the session left its rows (A17)", turn.receipts!!.single().undo!!.stamped)
        assertTrue("consumed", vm.voiceReceipts.value.isEmpty())

        // The Undo still works from the thread — the whole point of landing them.
        vm.undoAssistantReceipt(turn.id!!, 0)
        settleUntil { vm.assistantHistory.first { it.id == turn.id }.receipts?.first()?.undone == true }
        assertTrue("the task is gone", vm.assistantApi.getTasks().none { it.name == "Call the dentist" })

        // A session that wrote nothing leaves nothing behind.
        vm.resetVoiceScratch()
        vm.endVoiceSession()
        advanceUntilIdle()
        assertEquals(1, vm.assistantHistory.count { it.content == VOICE_SESSION_RECEIPTS })
    }

    /**
     * The two voice surfaces don't hand over cleanly: a call ringing over the
     * Talk overlay stops Talk's client through audio focus, but the holder only
     * lands its receipts when the SCREEN is dismissed — after the answered
     * call's `sessionWillStart` has already reset the scratch. Starting a
     * session must therefore LAND what the last one left, never discard it.
     */
    @Test fun aNewVoiceSessionLandsTheLastOnesReceiptsInsteadOfDroppingThem() = runTest {
        val vm = vm()
        vm.resetVoiceScratch()
        assertTrue(vm.runVoiceTool("create_task", buildJsonObject { put("name", "Book the dentist") }).startsWith("ok"))
        assertEquals(1, vm.voiceReceipts.value.size)

        // The call starts without the Talk overlay ever having been dismissed.
        vm.resetVoiceScratch()
        settleUntil { vm.assistantHistory.any { it.content == VOICE_SESSION_RECEIPTS } }
        val turn = vm.assistantHistory.single { it.content == VOICE_SESSION_RECEIPTS }
        assertEquals("Created “Book the dentist”", turn.receipts?.single()?.label)
        assertTrue("the new session starts empty", vm.voiceReceipts.value.isEmpty())

        // Sign-out is the one path that drops them: they point at the previous
        // account's rows and the thread is being erased in the same breath.
        assertTrue(vm.runVoiceTool("create_task", buildJsonObject { put("name", "Renew the passport") }).startsWith("ok"))
        assertEquals(1, vm.voiceReceipts.value.size)
        vm.scrubAssistantUserState()
        advanceUntilIdle()
        assertTrue(vm.voiceReceipts.value.isEmpty())
        assertEquals("nothing landed", 1, vm.assistantHistory.count { it.content == VOICE_SESSION_RECEIPTS })
    }

    // ── exact Undo (Android audit 2026-09-23, A17) ─────────────────────────

    /** One voice session's receipts, landed in the thread (stamped) — the turn. */
    private fun kotlinx.coroutines.test.TestScope.landVoice(vm: AppViewModel): ChatMessage {
        val before = vm.assistantHistory.count { it.content == VOICE_SESSION_RECEIPTS }
        vm.endVoiceSession()
        settleUntil { vm.assistantHistory.count { it.content == VOICE_SESSION_RECEIPTS } > before }
        return vm.assistantHistory.last { it.content == VOICE_SESSION_RECEIPTS }
    }

    private fun AppViewModel.receipt(turn: ChatMessage, i: Int = 0) =
        assistantHistory.first { it.id == turn.id }.receipts!![i]

    private fun capture(taskId: String? = null, body: String = "ask Sam about the lease renewal") =
        Capture(id = newUuid(), taskId = taskId, tag = CaptureTag.FOLLOW_UP, body = body, at = "2026-09-22T10:00:00.000Z")

    /** "Turn that capture into a task", then Undo: the capture the user wrote
     *  comes back to the inbox, unlinked. The DELETE_TASK undo hard-deleted it
     *  on every device through its capture cascade. */
    @Test fun undoingATaskFromACapturePutsTheCaptureBack() = runTest {
        val vm = vm()
        val cap = capture()
        vm.assistantApi.upsertCapture(cap)
        vm.resetVoiceScratch()
        val r = vm.runVoiceTool("promote_capture", buildJsonObject { put("captureId", cap.id) })
        assertTrue(r, r.startsWith("ok: promoted capture to task id="))
        val taskId = Regex("id=(\\S+)").find(r)!!.groupValues[1]
        assertTrue("out of the inbox", cap.id in vm.assistantApi.getArchivedCaptureIds())
        val turn = landVoice(vm)
        assertEquals(ReceiptUndoKind.UNPROMOTE_CAPTURE, vm.receipt(turn).undo?.kind)

        vm.undoAssistantReceipt(turn.id!!, 0)
        settleUntil { vm.receipt(turn).undone }

        val back = vm.assistantApi.getCaptures().singleOrNull { it.id == cap.id }
        assertNotNull("the user's note survives the Undo", back)
        assertNull("unlinked from the task that is gone", back!!.taskId)
        assertFalse("back in the inbox", cap.id in vm.assistantApi.getArchivedCaptureIds())
        assertTrue(vm.assistantApi.getTasks().none { it.id == taskId })

        // A focus note already filed on another task: the promote left that
        // link alone, so the Undo does too — and still puts it back in the inbox.
        val made = vm.tool("create_task", buildJsonObject { put("name", "Renew the lease") })
        val onTask = Regex("id=(\\S+)").find(made)!!.groupValues[1]
        val filed = capture(taskId = onTask, body = "check the break clause")
        vm.assistantApi.upsertCapture(filed)
        vm.resetVoiceScratch()
        assertTrue(vm.runVoiceTool("promote_capture", buildJsonObject { put("captureId", filed.id) }).startsWith("ok"))
        val second = landVoice(vm)
        vm.undoAssistantReceipt(second.id!!, 0)
        settleUntil { vm.receipt(second).undone }
        assertEquals(onTask, vm.assistantApi.getCaptures().single { it.id == filed.id }.taskId)
        assertFalse(filed.id in vm.assistantApi.getArchivedCaptureIds())
    }

    /** A task the assistant made that the user has since taken notes on (or
     *  renamed): Undo refuses, says why, and deletes nothing. */
    @Test fun undoRefusesATaskThatChangedSinceItsTurnAndSaysSo() = runTest {
        val vm = vm()
        vm.resetVoiceScratch()
        val r1 = vm.runVoiceTool("create_task", buildJsonObject { put("name", "Draft the lease letter") })
        val r2 = vm.runVoiceTool("create_task", buildJsonObject { put("name", "Call the landlord") })
        val noted = Regex("id=(\\S+)").find(r1)!!.groupValues[1]
        val renamed = Regex("id=(\\S+)").find(r2)!!.groupValues[1]
        val turn = landVoice(vm)

        val note = capture(taskId = noted, body = "mention the damp in the bedroom")
        vm.assistantApi.upsertCapture(note)
        vm.assistantApi.upsertTask(vm.assistantApi.getTasks().single { it.id == renamed }.copy(name = "Call the landlord about May"))

        vm.undoAssistantReceipt(turn.id!!, 0)
        vm.undoAssistantReceipt(turn.id!!, 1)
        settleUntil { vm.receiptUndoNotes.value.size == 2 }

        assertEquals(ReceiptUndoRefusal.NOTES, vm.receiptUndoNotes.value[receiptUndoKey(turn.id!!, 0)])
        assertEquals(ReceiptUndoRefusal.CHANGED, vm.receiptUndoNotes.value[receiptUndoKey(turn.id!!, 1)])
        assertFalse(vm.receipt(turn, 0).undone)
        assertFalse(vm.receipt(turn, 1).undone)
        val tasks = vm.assistantApi.getTasks()
        assertTrue("nothing deleted", tasks.any { it.id == noted } && tasks.any { it.id == renamed })
        assertTrue("the note is still there", vm.assistantApi.getCaptures().any { it.id == note.id })
    }

    /** Re-saving a fact the assistant already knew refines it in place (same
     *  id); its Undo put back the old words. Forgetting the id deleted a fact
     *  the user had had for weeks. */
    @Test fun undoingARefinedFactPutsTheOldWordingBack() = runTest {
        val vm = vm()
        val known = vm.saveProfileFact(ProfileFactCategory.PERSON, "Maleek — son", ProfileFactSource.INTERVIEW)!!
        vm.resetVoiceScratch()
        val r = vm.runVoiceTool("save_profile_fact", buildJsonObject { put("category", "person"); put("fact", "Maleek — son, 9") })
        assertTrue(r, r.contains("id=${known.id}"))
        val turn = landVoice(vm)
        assertEquals(ReceiptUndoKind.RESTORE_FACT, vm.receipt(turn).undo?.kind)

        vm.undoAssistantReceipt(turn.id!!, 0)
        settleUntil { vm.receipt(turn).undone }

        val back = facts().single { it.id == known.id }
        assertTrue("still known", back.active)
        assertEquals("Maleek — son", back.fact)
        assertEquals(ProfileFactSource.INTERVIEW, back.source)
    }

    /** Saying "don't use my name" again changes nothing, so its receipt offers
     *  no Undo that would forget the preference saved before. */
    @Test fun aRepeatedStylePreferenceOffersNoForget() = runTest {
        val vm = vm()
        val first = vm.saveStylePreference("please stop saying my name")!!
        assertEquals(ReceiptUndoKind.FORGET_FACT, first.undo?.kind)
        val again = vm.saveStylePreference("please stop saying my name")!!
        assertNull(again.undo)
        assertEquals(1, facts().count { it.active })
    }

    /** "Undo all" runs one undo at a time, newest first: "Completed X" reopens
     *  it, then "Created X" — re-stamped by that revert — deletes it. */
    @Test fun undoAllRevertsACreateAndCompleteOfTheSameTaskInTurn() = runTest {
        val vm = vm()
        vm.resetVoiceScratch()
        val made = vm.runVoiceTool("create_task", buildJsonObject { put("name", "Post the parcel") })
        val id = Regex("id=(\\S+)").find(made)!!.groupValues[1]
        assertTrue(vm.runVoiceTool("complete_task", buildJsonObject { put("taskId", id) }).startsWith("ok"))
        val turn = landVoice(vm)
        assertEquals(listOf(ReceiptUndoKind.DELETE_TASK, ReceiptUndoKind.UNCOMPLETE_TASK), turn.receipts!!.map { it.undo?.kind })

        vm.undoAllAssistantReceipts(turn.id!!)
        settleUntil { vm.receipt(turn, 0).undone && vm.receipt(turn, 1).undone }

        assertTrue(vm.assistantApi.getTasks().none { it.id == id })
        assertTrue(vm.receiptUndoNotes.value.isEmpty())
    }

    @Test fun forgetProfileFact_tombstonesAndForgetAllClears() = runTest {
        val vm = vm()
        val a = vm.saveProfileFact(ProfileFactCategory.PERSON, "A", ProfileFactSource.SETTINGS)!!
        vm.saveProfileFact(ProfileFactCategory.RHYTHM, "B", ProfileFactSource.SETTINGS)
        assertTrue(vm.forgetProfileFact(a.id))
        assertFalse(vm.forgetProfileFact(a.id))
        assertEquals(1, facts().count { it.active })
        vm.forgetAllProfileFacts()
        assertEquals(0, facts().count { it.active })
        assertEquals("tombstones stay so the server learns", 2, facts().size)
    }
}
