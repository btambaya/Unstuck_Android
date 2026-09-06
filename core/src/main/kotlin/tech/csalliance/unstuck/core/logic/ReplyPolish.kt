package tech.csalliance.unstuck.core.logic

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Reply polish — a DETERMINISTIC layer over the model's FINAL chat text.
// Port of lib/assistant/polish.ts (+ UnstuckCore/Logic/ReplyPolish.swift),
// rule for rule, so the web vectors hold on Android.
//
// Testers said the assistant "sounds unnatural": a "Done —" reflex, "Let me
// know if…" closers, cheering "!" and raw 2026-09-05 / 14:00 tokens echoed
// from tool results. Every attempt to fix that in the system prompt made
// qwen-turbo stop calling tools or lie (naturalness rounds 1–2, 2026-09-05),
// so the register is fixed HERE, on the client, on text only — it cannot
// touch tool calling, and the fabrication guard always sees the raw text.
//
// Rules (all case-insensitive, idempotent, never touching text inside
// quotes / backticks / URLs / id=… tokens, never returning an empty string):
//  1. openers   — strip a leading status tic ("Done —", "Got it.", "Sure,").
//  2. closers   — drop a trailing generic offer ("Let me know if…") when the
//                 reply has ≥2 sentences; keep specific one-question offers.
//  3. "!"       — "!" → "." at sentence end in a confirmation; greetings keep it.
//  4. dates     — 2026-09-05 → "Sat 5 Sep" (year only when not this year),
//                 14:30 → "2:30pm"; standalone tokens only.
//  5. markdown  — **bold** → plain; a single-item bullet list → a sentence.
//  6. whitespace — doubled spaces collapsed, trimmed.
//
// Kotlin strings are UTF-16, so every offset below maps 1:1 onto the Swift
// NSString / JS string offsets the reference implementations use.

/** Options for [polishReply]: "now" (and its zone) for the this-year date rule. */
data class PolishOptions(
    val nowMs: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val thisYear: Int get() = Instant.ofEpochMilli(nowMs).atZone(zone).year
}

/** Polish the model's FINAL chat text. Pure; idempotent; never empty. */
fun polishReply(text: String, opts: PolishOptions = PolishOptions()): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return text
    var out = ReplyPolish.collapseMarkdown(trimmed)
    out = ReplyPolish.stripOpener(out)
    out = ReplyPolish.stripCloser(out)
    out = ReplyPolish.restrainExclamations(out)
    out = ReplyPolish.speakDatesAndTimes(out, opts.thisYear)
    out = ReplyPolish.tidyWhitespace(out)
    return if (out.trim().isEmpty()) trimmed else out
}

/** "Sat 5 Sep" — the year only when it is not `thisYear`; null for an invalid
 *  civil date. Locale-agnostic (English abbreviations, `EEE d MMM`). */
fun spokenDate(year: Int, month: Int, day: Int, thisYear: Int): String? {
    if (month !in 1..12 || day < 1 || day > ReplyPolish.daysInMonth(year, month)) return null
    val core = "${ReplyPolish.DAYS[ReplyPolish.dayOfWeek(year, month, day)]} $day ${ReplyPolish.MONTHS[month - 1]}"
    return if (year == thisYear) core else "$core $year"
}

/** 14:00 → "2pm", 14:30 → "2:30pm", 00:05 → "12:05am". */
fun spokenTime(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "am" else "pm"
    return if (minute == 0) "$h12$suffix" else "$h12:${"%02d".format(minute)}$suffix"
}

internal object ReplyPolish {

    /** UTF-16 offsets, end exclusive. */
    class Span(val start: Int, val end: Int)

    private fun re(pattern: String, ignoreCase: Boolean = true): Regex =
        if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)

    // ---- protected spans

    // URLs and id=… tokens are never rewritten (a date inside an id is data).
    private val protectedToken = re("\\bhttps?://\\S+|\\bwww\\.\\S+|\\bids?=\\S+")
    // Balanced quote pairs; a straight apostrophe is NOT a quote (contractions).
    private val quotePairs = listOf("\"" to "\"", "“" to "”", "‘" to "’", "`" to "`")
    private val quoteChars = re("[\"“”‘’`]", ignoreCase = false)

    fun protectedSpans(s: String): List<Span> {
        val spans = ArrayList<Span>()
        for (m in protectedToken.findAll(s)) spans += Span(m.range.first, m.range.last + 1)
        for ((open, close) in quotePairs) {
            var i = 0
            while (i < s.length) {
                val a = s.indexOf(open, i)
                if (a < 0) break
                val from = a + open.length
                val b = s.indexOf(close, from)
                if (b < 0) break   // an unmatched opener protects nothing
                spans += Span(a, b + close.length)
                i = b + close.length
            }
        }
        return spans
    }

    private fun inSpan(i: Int, spans: List<Span>): Boolean = spans.any { i >= it.start && i < it.end }

    // ---- sentences

    /** Trimmed sentence spans. Boundaries: ".", "!", "?" (a run of them, plus a
     *  closing bracket) OUTSIDE protected spans and followed by whitespace/end —
     *  not a decimal point — and every line break. */
    fun sentenceSpans(s: String, spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var start = 0
        val n = s.length
        fun space(i: Int) = i in 0 until n && s[i].isWhitespace()
        fun digit(i: Int) = i in 0 until n && s[i] in '0'..'9'
        fun push(end: Int) {
            var a = start
            var b = end
            while (a < b && space(a)) a++
            while (b > a && space(b - 1)) b--
            if (b > a) out += Span(a, b)
            start = end
        }
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == '\n') { push(i); start = i + 1; i++; continue }
            if ((c == '.' || c == '!' || c == '?') && !inSpan(i, spans)) {
                if (c == '.' && digit(i - 1) && digit(i + 1)) { i++; continue }
                var j = i
                while (j + 1 < n && s[j + 1] in ".!?" && !inSpan(j + 1, spans)) j++
                while (j + 1 < n && s[j + 1] in ")]") j++
                if (j + 1 >= n || space(j + 1)) { push(j + 1); i = j }
            }
            i++
        }
        push(n)
        return out
    }

    // ---- 5. markdown residue

    private val bold = re("\\*\\*([^*\\n]+?)\\*\\*", ignoreCase = false)
    private val boldUnderscore = re("__([^_\\n]+?)__", ignoreCase = false)
    private val bullet = re("^\\s*(?:[-*•]|\\d+[.)])\\s+", ignoreCase = false)
    private val terminated = re("[.!?…]$", ignoreCase = false)

    private fun unwrap(s: String, rx: Regex): String {
        val spans = protectedSpans(s)
        val out = StringBuilder(s)
        for (m in rx.findAll(s).toList().asReversed()) {
            if (inSpan(m.range.first, spans)) continue
            out.replace(m.range.first, m.range.last + 1, m.groupValues[1])
        }
        return out.toString()
    }

    fun collapseMarkdown(s: String): String {
        var out = unwrap(unwrap(s, bold), boldUnderscore)
        // A one-item list is a sentence that lost its way; multi-item lists stay.
        val lines = out.split("\n").toMutableList()
        val bullets = lines.indices.filter { bullet.containsMatchIn(lines[it]) }
        if (bullets.size == 1) {
            val i = bullets[0]
            var item = bullet.replaceFirst(lines[i], "").trim()
            if (item.isNotEmpty() && !terminated.containsMatchIn(item)) item += "."
            var p = i - 1
            while (p >= 0 && lines[p].isBlank()) p--
            if (p >= 0) {
                lines[p] = lines[p].trimEnd() + " " + item
                for (k in i downTo p + 1) lines.removeAt(k)
            } else {
                lines[i] = item
            }
            out = lines.joinToString("\n")
        }
        return out
    }

    // ---- 1. openers

    private val opener = re(
        "^(?:all\\s+)?(?:done|got\\s+it|sure(?:\\s+thing)?|alright|all\\s+right|okay|ok|great|perfect|absolutely" +
            "|certainly|of\\s+course|no\\s+problem)\\s*[—–\\-:,.!]+\\s*",
    )

    private fun capitaliseFirst(s: String): String {
        if (s.isEmpty()) return s
        val first = s.substring(0, 1)
        val up = first.uppercase(Locale.ROOT)
        return if (up == first) s else up + s.substring(1)
    }

    fun stripOpener(s: String): String {
        var out = s
        for (k in 0 until 3) {
            val m = opener.find(out) ?: break
            if (m.range.first != 0) break
            val rest = out.substring(m.range.last + 1)
            if (rest.isBlank()) break   // "Done." alone stays a reply
            out = rest
        }
        return if (out == s) s else capitaliseFirst(out)
    }

    // ---- 2. closers

    private val closer = re(
        "^(?:and\\s+|so\\s+|but\\s+)?(?:let me know|just let me know|just say|just ask|just tell me|feel free|if you'?d like" +
            "|if you would like|if you want|if you need|if there'?s anything|anything else|is there anything else" +
            "|need anything else|happy to help|hope that helps|hope this helps|want me to|would you like me to" +
            "|do you want me to|shall i)\\b",
    )

    // A closer that names a day / time is a real offer, not a tic.
    private val specific = re(
        "\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|today|tomorrow" +
            "|tonight|noon|midnight|morning|afternoon|evening)\\b|\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b" +
            "|\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b",
    )

    // Words a generic "anything else?" is built from. A "?" offer made ONLY of
    // these is dropped; one with a real object ("the report", "the calendar") stays.
    private val filler: Set<String> = (
        "a an the and or to with for of on in at it that this these those them me you your i " +
            "can could would should will want wants need needs needed like help helping assist assistance anything something " +
            "else more further other another any some all just also too if whether when what how about adjust adjusting " +
            "adjustments change changes changing tweak tweaks tweaking edit edits update updates do done know let say feel free " +
            "ask questions question happy hope helps glad here there is are be have has please sure okay ok go ahead now next " +
            "again thing things stuff otherwise ready wish shall details detail " +
            "there's that's it's i'd i'll i'm you'd you'll you're"
        ).split(" ").filter { it.isNotEmpty() }.toSet()
    private val nonWord = re("[^a-z'\\s]", ignoreCase = false)

    private fun isGenericOffer(sentence: String): Boolean {
        val lowered = sentence.lowercase(Locale.ROOT).replace("’", "'")
        val cleaned = nonWord.replace(lowered, " ")
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.all { it in filler }
    }

    private fun shouldDropCloser(sentence: String): Boolean {
        if (!closer.containsMatchIn(sentence)) return false
        if (quoteChars.containsMatchIn(sentence)) return false   // names a task/list/date
        if (specific.containsMatchIn(sentence)) return false
        if (sentence.endsWith("?") && !isGenericOffer(sentence)) return false
        return true
    }

    fun stripCloser(s: String): String {
        var out = s
        for (k in 0 until 3) {
            val sentences = sentenceSpans(out, protectedSpans(out))
            if (sentences.size < 2) break
            val last = sentences[sentences.size - 1]
            if (!shouldDropCloser(out.substring(last.start, last.end))) break
            out = out.substring(0, last.start).trimEnd()
        }
        return out
    }

    // ---- 3. exclamation restraint

    private val confirmVerb = re(
        "\\b(?:added|scheduled|rescheduled|moved|booked|skipped|saved|noted|removed|created|updated|reopened|cancelled|canceled" +
            "|blocked|captured|deleted|completed|renamed|started|set|ticked|unticked|unscheduled|shared|paused|resumed|extended" +
            "|cleared|marked|archived|logged|done)\\b",
    )
    private val greeting = re("^(?:hi|hey|hello|welcome|good\\s+(?:morning|afternoon|evening)|morning|afternoon|evening|happy)\\b")
    private val trailingBang = re("!+$", ignoreCase = false)

    fun restrainExclamations(s: String): String {
        if (!confirmVerb.containsMatchIn(s)) return s
        val sentences = sentenceSpans(s, protectedSpans(s))
        val out = StringBuilder(s)
        for (sent in sentences.asReversed()) {   // edit from the end: earlier offsets stay valid
            val t = s.substring(sent.start, sent.end)
            val bang = trailingBang.find(t) ?: continue
            if (greeting.containsMatchIn(t)) continue
            out.replace(sent.start + bang.range.first, sent.start + bang.range.last + 1, ".")
        }
        return out.toString()
    }

    // ---- 4. dates and times spoken

    val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** 0 = Sunday. Pure civil-date arithmetic (Sakamoto), no time zone. */
    fun dayOfWeek(y: Int, m: Int, d: Int): Int {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val yy = if (m < 3) y - 1 else y
        return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    }

    fun daysInMonth(y: Int, m: Int): Int {
        if (m == 2) return if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        return if (m in setOf(4, 6, 9, 11)) 30 else 31
    }

    // Standalone only: not glued to letters/digits/ids.
    private val date = re("(^|[^A-Za-z0-9_/=-])(\\d{4})-(\\d{2})-(\\d{2})(?![A-Za-z0-9_/-])", ignoreCase = false)
    private val time = re("(^|[^A-Za-z0-9_:/=.-]|[-–—])([01]\\d|2[0-3]):([0-5]\\d)(?![0-9A-Za-z_:]|\\s*[ap]\\.?m\\b)")

    fun speakDatesAndTimes(s: String, thisYear: Int): String {
        var spans = protectedSpans(s)
        var out = StringBuilder(s)
        for (m in date.findAll(s).toList().asReversed()) {
            val pre = m.groupValues[1]
            if (inSpan(m.range.first + pre.length, spans)) continue
            val y = m.groupValues[2].toIntOrNull() ?: continue
            val mo = m.groupValues[3].toIntOrNull() ?: continue
            val d = m.groupValues[4].toIntOrNull() ?: continue
            val spoken = spokenDate(y, mo, d, thisYear) ?: continue
            out.replace(m.range.first, m.range.last + 1, pre + spoken)
        }
        val afterDates = out.toString()
        spans = protectedSpans(afterDates)
        out = StringBuilder(afterDates)
        for (m in time.findAll(afterDates).toList().asReversed()) {
            val pre = m.groupValues[1]
            if (inSpan(m.range.first + pre.length, spans)) continue
            val h = m.groupValues[2].toIntOrNull() ?: continue
            val min = m.groupValues[3].toIntOrNull() ?: continue
            out.replace(m.range.first, m.range.last + 1, pre + spokenTime(h, min))
        }
        return out.toString()
    }

    // ---- 6. whitespace

    private val doubledSpaces = re("[ \\t]{2,}", ignoreCase = false)
    private val trailingLineSpaces = re("[ \\t]+\\n", ignoreCase = false)
    private val blankRuns = re("\\n{3,}", ignoreCase = false)

    fun tidyWhitespace(s: String): String {
        val spans = protectedSpans(s)
        val out = StringBuilder(s)
        for (m in doubledSpaces.findAll(s).toList().asReversed()) {
            if (inSpan(m.range.first, spans)) continue
            out.replace(m.range.first, m.range.last + 1, " ")
        }
        var text = out.toString()
        text = trailingLineSpaces.replace(text, "\n")
        text = blankRuns.replace(text, "\n\n")
        return text.trim()
    }
}
