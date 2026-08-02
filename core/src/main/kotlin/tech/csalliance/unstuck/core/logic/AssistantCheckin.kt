package tech.csalliance.unstuck.core.logic

// Port of lib/assistant/checkin.ts. Daily check-in — a grounded one-liner the
// assistant "says" on the first open of each day, built ENTIRELY client-side
// from real data (zero tokens). Injected as a LOCAL display turn: it is never
// sent to the model, so it costs nothing and can't drift into the context.

private fun greetingWord(hour: Int): String = when {
    hour < 12 -> "Morning"
    hour < 18 -> "Afternoon"
    else -> "Evening"
}

/** [usableLabel] is the same string the Today rail shows (see [fmtHrs]); null
 *  when there's no usable time to report. [hour] is the local hour 0-23. */
fun buildCheckin(firstName: String?, openTodayCount: Int, usableLabel: String?, hour: Int): String {
    val word = greetingWord(hour)
    val hi = if (!firstName.isNullOrBlank()) "$word, $firstName." else "$word."
    if (openTodayCount == 0) {
        return "$hi Nothing scheduled yet — want me to help plan today?"
    }
    val things = "$openTodayCount thing${if (openTodayCount == 1) "" else "s"} on today"
    val time = if (usableLabel != null) ", $usableLabel usable" else ""
    return "$hi $things$time. Want me to sequence them, or take something off the list?"
}
