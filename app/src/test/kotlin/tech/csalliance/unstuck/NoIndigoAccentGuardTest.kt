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

    // A hand-written indigo/violet literal — oklch(L, C, H) with a real chroma
    // (≥ 0.03) at an indigo/violet hue (255…310) — outside the palette file. The
    // tour answer bubble was one (lavender, 0.04 @ 280) until it went neutral.
    private val indigoLiteral = Regex("""oklch\(\s*[0-9.]+\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)""")

    /** The one deliberate exception: the Focus takeover's full-screen deep-indigo
     *  radial background (FocusScreen + the tour's Focus demo) — a background
     *  mood, shared with web's ambient treatment, not an accent. */
    private val focusBackdrop = "listOf(oklch(0.30, 0.10, 280.0), oklch(0.16, 0.02, 280.0))"

    @Test fun `no hand-written indigo colour outside the palette`() {
        val root = projectRoot()
        val offenders = mutableListOf<String>()
        for (module in listOf("design", "app")) {
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" && it.name != "Oklch.kt" }
                .forEach { f ->
                    f.readLines().forEachIndexed { i, line ->
                        if (focusBackdrop in line) return@forEachIndexed
                        for (m in indigoLiteral.findAll(line)) {
                            val chroma = m.groupValues[1].toDoubleOrNull() ?: continue
                            val hue = m.groupValues[2].toDoubleOrNull() ?: continue
                            if (chroma >= 0.03 && hue in 255.0..310.0) offenders += "${f.relativeTo(root)}:${i + 1}  ${m.value}"
                        }
                    }
                }
        }
        assertTrue("indigo is not an accent — use the palette's neutrals / ink:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun `the Material colour scheme does not map primary to indigo`() {
        val theme = File(projectRoot(), "design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt").readText()
        assertTrue("UnstuckTheme must build its scheme with unstuckColorScheme()", "unstuckColorScheme(colors)" in theme)
        assertTrue("primary = colors.primary is back in the Material scheme", "primary = colors.primary" !in theme)
    }
}
