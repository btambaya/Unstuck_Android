package tech.csalliance.unstuck

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Owner decision 2026-09-24 (after the Notifications & calls screen showed an
// indigo switch): indigo is not an accent anywhere. ON switches are coral
// (MdToggle); links and inline actions are ink / ink2; chips and pills are the
// black-and-white selection pair (ink fill, bg text — unselected / passive:
// bg2, ink2, line2 ring); eyebrows are the neutral SectionLabel default; the
// voice orb is coral. The palette still HAS its indigo ramp — an area or
// collection a user coloured "indigo" resolves to it through areaColor() —
// but no screen reaches for it by name. This keeps one from coming back.
class NoIndigoAccentGuardTest {

    private val indigoUse = Regex("""(?<![A-Za-z0-9_])(c|colors|UTheme\.colors|LocalUnstuckColors\.current)\s*\.\s*(primary|primarySoft|primaryDeep|violet)\b""")

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "design/src/main").isDirectory && File(it, "app/src/main").isDirectory }
            ?: error("project root not found from ${File(".").absolutePath}")

    @Test fun `no screen paints with the indigo ramp`() {
        val root = projectRoot()
        val offenders = mutableListOf<String>()
        for (module in listOf("design", "app")) {
            File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                for (m in indigoUse.findAll(text)) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${f.relativeTo(root)}:$line  ${m.value}"
                }
            }
        }
        assertTrue(
            "indigo is not an accent (links ink, chips ink/bg, eyebrows neutral, switches coral):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test fun `the Material colour scheme does not map primary to indigo`() {
        val theme = File(projectRoot(), "design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt").readText()
        assertTrue("UnstuckTheme must build its scheme with unstuckColorScheme()", "unstuckColorScheme(colors)" in theme)
        assertTrue("primary = colors.primary is back in the Material scheme", "primary = colors.primary" !in theme)
    }
}
