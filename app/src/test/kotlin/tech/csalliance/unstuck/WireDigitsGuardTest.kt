package tech.csalliance.unstuck

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The pickers, the calendar's tap/drag, the notification's Reschedule and the
// assistant each built a block's 'HH:MM' (and core its 'YYYY-MM-DD') with a bare
// "%02d:%02d".format(…), which follows the phone's locale: on an Arabic, Persian,
// Bengali, Marathi, Nepali or Burmese phone the server refused the block and it
// never left the device (Android audit 2026-09-23, A12). Those shapes go through
// core's WireTime now; this keeps a new one from coming back. Text built only
// for display (the 12-hour labels, the timer face) uses other shapes and may
// stay localised.
class WireDigitsGuardTest {

    private val machineShape = "(%04d-%02d-%02d|%02d:%02d)"
    private val extensionFormat = Regex("\"$machineShape\"\\s*\\.format\\((?!\\s*Locale\\.)")
    private val stringFormat = Regex("String\\.format\\(\\s*\"$machineShape\"")

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "core/src/main").isDirectory && File(it, "app/src/main").isDirectory }
            ?: error("project root not found from ${File(".").absolutePath}")

    @Test fun `no date or HH-MM is formatted with the phone's digits`() {
        val root = projectRoot()
        val offenders = mutableListOf<String>()
        for (module in listOf("core", "data", "sync", "design", "app")) {
            val src = File(root, "$module/src/main")
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                for (m in extensionFormat.findAll(text) + stringFormat.findAll(text)) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${f.relativeTo(root)}:$line  ${m.value.trim()}"
                }
            }
        }
        assertTrue(
            "format these with WireTime (core/time/Time.kt), or pass Locale.ROOT:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
