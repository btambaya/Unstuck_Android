package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CallKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// CallScript — the deterministic part of a call from Unstuck. Pure (no audio,
// no network) and unit-tested; 1:1 port of iOS App/Calls/CallScript.swift.
// Given the ring payload it yields
//   • `opening`       — the first utterance the model MUST speak verbatim
//                       (per KIND: the notes read back + the offer for a
//                       requested call; the test-call line; "want to walk
//                       through today?"; "quick wrap-up?"; "<task> was on till
//                       <time> — how did it go?"), and
//   • `instructions`  — the extra system lines for a call turn (open verbatim,
//                       then act ONLY through tools, keep it short, English,
//                       the name once, never claim an action without its tool
//                       result), and
//   • `callToolNames` — the tool names live during a call: EVERY voice tool
//                       (the registry's voice surface — a call is the full
//                       assistant on the phone, calls build-out 2026-09-20)
//                       plus the call-only extras (the registry's call
//                       surface: `snooze_call`; `update_call` is guaranteed).
// CallVoiceService appends `instructions` to the standard voice instructions
// and builds the session's tool schemas from `callToolNames`, adding the
// call-level `snooze_call` (always the launcher's own schema).
//
// THE LABEL'S SHAPE. The assistant prompt asks for the call label as a VERB
// phrase ("speak to James" — prompt.ts request_call), but a model — or the
// task-anchored default (the task's name) — may hand over a noun ("James",
// "the dentist", "Dentist appointment"). Splicing either into one frame reads
// wrong for the other ("you asked me to ring about speak to James"), so the
// opening renders by shape: a verb phrase → "you asked me to ring so you'd
// speak to James", anything else → "you asked me to ring about James".

object CallScript {
    /** The call-only tools every call MUST carry even when the registry the
     *  launcher was handed lacks them (the launcher synthesises both schemas):
     *  `update_call` changes this call's notes for later, `snooze_call` is
     *  CALL-LEVEL — the voice service handles it locally (outcome `snoozed` +
     *  snoozeMinutes, then hangs up) and it never reaches the app's executor. */
    val CALL_EXTRAS: List<String> = listOf("update_call", "snooze_call")

    /** Pure: the names of `voice` (the registry's voice surface, in registry
     *  order) then `call` (its call surface), de-duplicated, order kept, then
     *  any of [CALL_EXTRAS] still missing — iOS `CallScript.callToolNames`. */
    fun callToolNames(voice: List<String>, call: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (n in voice + call + CALL_EXTRAS) if (n.isNotBlank()) seen.add(n)
        return seen.toList()
    }

    /** The verbatim first utterance, by kind:
     *    requested   "Hi <name> — you asked me to ring about <noun label> |
     *                so you'd <verb label>. [You wanted to remember: <A>; <B>;
     *                <C>.] [It starts in N minutes.] [Your first step was: …]
     *                <closing question>" — ONE short question chosen by what
     *                applies ([offerSentence]): a task → the timer or a
     *                ring-back; notes only → anything to add; neither →
     *                anything to note. Never a menu.
     *    test        "Hi <name> — this is your test call from Unstuck.
     *                Everything works. Want to try something — ask me what's
     *                on today?"
     *    morning     "Morning, <name>. Want to walk through today?"
     *    evening     "Evening, <name>. Quick wrap-up?"
     *    after_block "Hi <name> — <task> was on till <time>. How did it go?" */
    fun opening(
        p: IncomingCallPayload,
        preferredName: String? = p.preferredName,
        nowMs: Long = System.currentTimeMillis(),
        receivedAtMs: Long = nowMs,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val name = preferredName?.trim()?.takeIf { it.isNotEmpty() }
        return when (p.resolvedKind) {
            CallKind.REQUESTED -> {
                val parts = ArrayList<String>()
                parts.add(hiGreeting(name) + "you asked me to ring ${reasonPhrase(p.label)}.")
                val notes = notesSentence(p.notes)
                if (notes != null) parts.add(notes)
                startLine(p, nowMs, receivedAtMs, zone)?.let { parts.add(it) }
                p.firstAction?.let { parts.add("Your first step was: $it.") }
                parts.add(offerSentence(hasTask = p.taskId != null, hasNotes = notes != null))
                parts.joinToString(" ")
            }
            CallKind.TEST ->
                hiGreeting(name) + "this is your test call from Unstuck. Everything works. Want to try something — ask me what's on today?"
            CallKind.MORNING -> (name?.let { "Morning, $it." } ?: "Morning.") + " Want to walk through today?"
            CallKind.EVENING -> (name?.let { "Evening, $it." } ?: "Evening.") + " Quick wrap-up?"
            CallKind.AFTER_BLOCK -> {
                val what = p.taskName ?: p.label
                val till = p.spokenEnd(receivedAtMs, zone)?.let { "was on till $it" } ?: "just finished"
                hiGreeting(name) + "$what $till. How did it go?"
            }
        }
    }

    /** "Hi <name> — " / "Hi — ". */
    fun hiGreeting(name: String?): String = name?.let { "Hi $it — " } ?: "Hi — "

    // ── label shape ─────────────────────────────────────────────────────────

    enum class LabelShape { VERB_PHRASE, NOUN_PHRASE }

    /** Imperative verbs a call label commonly opens with. A label whose first
     *  word is one of these (or that starts "to …") is a verb phrase; anything
     *  else — a name, "the dentist", "Dentist appointment" — is a noun phrase.
     *  Unknown first words default to NOUN: "about chase the invoice" is a
     *  mild miss, "so you'd dentist appointment" is not. */
    val leadingVerbs: List<String> = listOf(
        "speak", "talk", "call", "ring", "phone", "dial", "text", "message", "email", "mail", "dm", "whatsapp",
        "facetime", "zoom", "write", "reply", "respond", "answer", "ask", "tell", "remind", "check", "confirm",
        "chase", "follow", "nudge", "ping", "poke", "book", "cancel", "reschedule", "schedule", "plan", "prep",
        "prepare", "pack", "unpack", "buy", "order", "pay", "send", "submit", "sign", "renew", "register", "apply",
        "review", "read", "finish", "start", "begin", "do", "get", "go", "pick", "drop", "collect", "fetch",
        "bring", "take", "make", "cook", "clean", "tidy", "wash", "water", "feed", "walk", "run", "exercise",
        "stretch", "meditate", "sleep", "rest", "eat", "drink", "leave", "meet", "see", "visit", "join", "attend",
        "watch", "listen", "practice", "practise", "study", "revise", "learn", "fix", "repair", "update", "upload",
        "download", "install", "set", "sort", "file", "print", "scan", "post", "ship", "return", "drive", "catch",
        "wake", "hand", "deliver", "complete", "wrap", "close", "open", "look", "find", "search", "log", "track",
        "record", "note", "decide", "choose", "move", "transfer", "charge", "push", "pull", "merge", "deploy",
        "test", "debug", "release", "publish", "draft", "outline", "edit", "proofread", "share", "invite", "thank",
        "apologise", "apologize", "congratulate", "celebrate", "wish", "greet", "put", "hang", "turn", "switch",
        "lock", "unlock", "fill", "refill", "vacuum", "iron", "fold", "mend", "hire", "rent", "swap", "sell",
        "list", "dispute", "claim", "report", "escalate", "stand", "sit", "breathe", "focus", "work", "tackle",
        "kick", "launch", "stop", "quit", "resume", "continue", "try", "give", "help", "let", "keep", "wait",
        "arrange", "organise", "organize", "clear", "empty", "top", "back", "pop", "nip", "head",
    ).distinct()

    private val verbSet: Set<String> = leadingVerbs.toSet()

    fun labelShape(label: String): LabelShape {
        val trimmed = label.trim()
        val first = trimmed.split(' ').firstOrNull { it.isNotEmpty() } ?: return LabelShape.NOUN_PHRASE
        val word = first.lowercase().trim { !it.isLetterOrDigit() }
        if (word == "to") return LabelShape.VERB_PHRASE
        return if (word in verbSet) LabelShape.VERB_PHRASE else LabelShape.NOUN_PHRASE
    }

    /** "about James" / "so you'd speak to James" — the phrase after "you
     *  asked me to ring". A leading "to " is folded into the frame. */
    fun reasonPhrase(label: String): String {
        val trimmed = label.trim()
        return when (labelShape(trimmed)) {
            LabelShape.VERB_PHRASE -> {
                var body = trimmed
                if (body.lowercase().startsWith("to ")) body = body.drop(3).trim()
                "so you'd $body"
            }
            LabelShape.NOUN_PHRASE -> "about $trimmed"
        }
    }

    // ── sentences ───────────────────────────────────────────────────────────

    /** "You wanted to remember: A; B; C." — verbatim, trailing punctuation
     *  left alone; null when the request carried no notes (nothing to say). */
    fun notesSentence(notes: List<String>): String? {
        val clean = notes.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        return "You wanted to remember: " + clean.joinToString("; ") + "."
    }

    /** The closing question — one short question, never a menu: a task
     *  attached → the timer or a ring-back; notes only → anything to add;
     *  neither → anything to note. */
    fun offerSentence(hasTask: Boolean, hasNotes: Boolean): String = when {
        hasTask -> "Start the timer, or ring you back in ten?"
        hasNotes -> "Anything to add?"
        else -> "Anything you want me to note?"
    }

    /** "It starts in N minutes." / "It starts now." / "It started N minutes ago." */
    fun startLine(
        p: IncomingCallPayload, nowMs: Long, receivedAtMs: Long = nowMs, zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val m = p.minutesUntilStart(nowMs, receivedAtMs, zone) ?: return null
        return when {
            m >= 2 -> "It starts in $m minutes."
            m == 1 -> "It starts in a minute."
            m == 0 -> "It starts now."
            m == -1 -> "It started a minute ago."
            else -> "It started ${-m} minutes ago."
        }
    }

    private val STARTS_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The rule every kind carries: the name is spoken ONCE, in the opening,
     *  and never again (a test call on 2026-09-20 used it three times). */
    const val NAME_ONCE_RULE = "Their name is in the opening — say it there once and not again during the call."

    /** Extra system lines for the call turn. Appended AFTER the standard voice
     *  instructions (which carry the scope guardrail + live app state + every
     *  tool's rules). Line 1 (the verbatim opening) and the "never claim an
     *  action without its tool result" rule hold for every kind; line 2 is
     *  the kind's own shape of conversation; line 3 the manner, including the
     *  name-once rule. `dayContext`: lines read from the local store as the
     *  call connects ([CallDayContext.lines]) — what got done today, what is
     *  still open, today's plan, tomorrow's first thing — so the model answers
     *  from facts, not from a tool call it may skip (parity with iOS build 75). */
    fun instructions(
        p: IncomingCallPayload,
        preferredName: String? = p.preferredName,
        nowMs: Long = System.currentTimeMillis(),
        receivedAtMs: Long = nowMs,
        zone: ZoneId = ZoneId.systemDefault(),
        dayContext: List<String> = emptyList(),
    ): String {
        val kind = p.resolvedKind
        val ctx = ArrayList<String>()
        ctx.add("- kind: ${kind.wire}")
        ctx.add("- label: ${p.label}")
        p.taskId?.let { ctx.add("- task: ${p.taskName ?: "(unnamed)"} [id=$it]") }
        p.blockId?.let { ctx.add("- block: $it") }
        p.startMs(receivedAtMs, zone)?.let {
            ctx.add("- starts at: " + STARTS_AT.format(Instant.ofEpochMilli(it).atZone(zone)))
        }
        p.endMs(receivedAtMs, zone)?.let {
            ctx.add("- block ended at: " + STARTS_AT.format(Instant.ofEpochMilli(it).atZone(zone)))
        }
        p.firstAction?.let { ctx.add("- first step: $it") }
        ctx.add("- notes (verbatim): " + if (p.notes.isEmpty()) "(none)" else p.notes.joinToString(", ") { "\"$it\"" })
        if (p.captures.isNotEmpty()) {
            ctx.add("- recent captures on it: " + p.captures.joinToString(", ") { "\"$it\"" })
        }
        for (line in dayContext) ctx.add("- $line")
        val open = opening(p, preferredName, nowMs, receivedAtMs, zone)
        val openingRule = when (kind) {
            CallKind.REQUESTED -> "read the notes word for word, do not summarise or reorder them"
            else -> "then wait for their answer"
        }
        return """
${headline(p)} Speak English, calm and brief, like a friend on the phone.
1. Open by saying EXACTLY this, verbatim, before anything else — $openingRule:
"$open"
2. ${conversationRule(kind)} You have every tool you have in Talk — reschedule, add tasks, tick things off, capture, share, plus update_call (changes this call's notes for later) and snooze_call ("call me back in ten" — say the minutes). Never claim an action happened without its tool result; if a tool errors, say so plainly.
3. One or two sentences a turn, one question at a time, times the way people say them. $NAME_ONCE_RULE When they're done, say bye — they hang up from the screen.
Call context (read from the app as the call connected — answer "what got done" / "what's on today" from it; call get_schedule / get_tasks only for what it doesn't cover):
${ctx.joinToString("\n")}
""".trim()
    }

    /** The first line of the call instructions — what kind of call this is. */
    fun headline(p: IncomingCallPayload): String = when (p.resolvedKind) {
        CallKind.REQUESTED -> "THIS IS A PHONE CALL the user asked you to make (call id ${p.callId})."
        CallKind.TEST -> "THIS IS A TEST CALL the user booked from Settings to hear what a call from Unstuck sounds like (call id ${p.callId})."
        CallKind.MORNING -> "THIS IS THE MORNING PLANNING CALL the user opted into (call id ${p.callId})."
        CallKind.EVENING -> "THIS IS THE EVENING WRAP-UP CALL the user opted into (call id ${p.callId})."
        CallKind.AFTER_BLOCK -> "THIS IS THE CHECK-IN AFTER A BLOCK the user opted into: the block ended and its task isn't marked done (call id ${p.callId})."
    }

    /** Line 2 — how the conversation goes for this kind. */
    fun conversationRule(kind: CallKind): String = when (kind) {
        CallKind.REQUESTED ->
            "Then act ONLY through tools: complete_task ticks a task off, add_capture notes something they say, schedule_task moves it, start_focus starts the timer."
        CallKind.TEST ->
            "If they try something, do it for real through the tools (get_schedule / get_tasks answer \"what's on today\"); keep it light — this call proves the ring works."
        // The morning / evening rules read the day from the call context
        // (CallDayContext) — the evening one NEVER asks what got done (parity
        // with iOS build 75: Zubair's evening call asked him).
        CallKind.MORNING ->
            "If they say yes, read today's plan from the call context below, briefly (times the way people say them) — call get_schedule only if the context has no plan — then plan with them: move things with schedule_task / block_time, add what's missing with create_task, drop what won't happen with set_task_later or carry_to_tomorrow. Act ONLY through tools."
        CallKind.EVENING ->
            "If they say yes, say from the call context below what got done today and what is still open, in one sentence — NEVER ask them what got done, and call get_tasks(view: completed) only if the context has no such line — then ask what moves to tomorrow: carry_to_tomorrow ONLY when they ask for it, complete_task for anything they finished, add_capture for a loose thought. Act ONLY through tools."
        CallKind.AFTER_BLOCK ->
            "Listen, then settle it in one move: done → complete_task (or complete_occurrence for a recurring one); not now → skip_occurrence / set_task_later; needs another go → schedule_task or block_time for a new slot. Act ONLY through tools."
    }
}
