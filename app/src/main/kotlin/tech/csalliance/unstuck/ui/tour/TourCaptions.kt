package tech.csalliance.unstuck.ui.tour

// Live captions (round-2 #2) — the Listen bar shows the sentence CURRENTLY
// being spoken, one line, synced from audio progress against CHAR-WEIGHTED
// sentence spans. splitTourSentences is a verbatim port of the web's
// splitSentences (components/tour/tour-voice.ts); the spans normalize the
// web's ~ms/char pacing estimate to the clip's real duration, so each
// sentence owns a progress window proportional to its character count.
// Applies to narration AND Tell-me-more clips. Pure — unit-tested.

/** Split narration into sentences (web splitSentences parity): split on
 *  sentence-ending punctuation (keeping closing quotes/brackets with their
 *  sentence), keep a trailing fragment, and fold stray sub-4-char fragments
 *  into the previous sentence. */
fun splitTourSentences(text: String): List<String> {
    val out = mutableListOf<String>()
    val re = Regex("""[^.!?…]+[.!?…]+["'”’)\]]*\s*""")
    var last = 0
    for (m in re.findAll(text)) {
        val s = m.value.trim()
        if (s.isNotEmpty()) out.add(s)
        last = m.range.last + 1
    }
    val rest = text.substring(last.coerceAtMost(text.length)).trim()
    if (rest.isNotEmpty()) out.add(rest)
    val merged = mutableListOf<String>()
    for (s in out) {
        if (merged.isNotEmpty() && s.length < 4) merged[merged.size - 1] = merged.last() + " " + s
        else merged.add(s)
    }
    if (merged.isEmpty() && text.isNotBlank()) return listOf(text.trim())
    return merged
}

/** One sentence's window of the clip, as progress fractions [start, end). */
data class TourSentenceSpan(val text: String, val start: Float, val end: Float)

/** Char-weighted spans over 0..1: sentence i's window width is proportional
 *  to its character count (the narration pace is near-constant per char, so
 *  this tracks the spoken sentence without per-clip timestamps). */
fun tourSentenceSpans(text: String): List<TourSentenceSpan> {
    val sentences = splitTourSentences(text)
    val total = sentences.sumOf { it.length }.toFloat()
    if (total <= 0f) return emptyList()
    var acc = 0f
    return sentences.map { s ->
        val start = acc / total
        acc += s.length
        TourSentenceSpan(s, start, acc / total)
    }
}

/** The sentence being spoken at [progress] (0..1). Progress ≥ 1 (or any
 *  overshoot) sticks to the last sentence; empty spans → null. */
fun tourCaptionAt(spans: List<TourSentenceSpan>, progress: Float): String? {
    if (spans.isEmpty()) return null
    val p = progress.coerceIn(0f, 1f)
    return (spans.firstOrNull { p < it.end } ?: spans.last()).text
}
