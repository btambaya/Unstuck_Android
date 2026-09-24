package tech.csalliance.unstuck.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// `.verticalScroll(…).imePadding()` puts the keyboard's padding INSIDE the
// scroll: the content gets longer, but the scroll's viewport still runs down
// under the keyboard, so a field focused near the bottom counts as "in view"
// while the keyboard covers it and nothing scrolls it up. That is how an item
// held to edit near the bottom of a list ended up under the keyboard (Ahmad
// 2026-09-24; reproduced in KeyboardInsetsTest). `.imePadding().verticalScroll(…)`
// shrinks the viewport instead, and Compose then keeps the focused field in
// sight. Inside a ModalBottomSheet either order is a no-op (the sheet pads its
// content for the keyboard already) — one rule everywhere keeps the wrong one
// from being copied back onto a full screen.
class ImePaddingOrderGuardTest {

    private val insideTheScroll = Regex("""\.verticalScroll\((?:[^()]|\([^()]*\))*\)\s*\.imePadding\(\)""")

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "core/src/main").isDirectory && File(it, "app/src/main").isDirectory }
            ?: error("project root not found from ${File(".").absolutePath}")

    @Test fun `the keyboard padding goes outside a vertical scroll, never inside it`() {
        val root = projectRoot()
        val offenders = mutableListOf<String>()
        for (module in listOf("app", "design")) {
            val src = File(root, "$module/src/main")
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                for (m in insideTheScroll.findAll(text)) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${f.relativeTo(root)}:$line  ${m.value.trim()}"
                }
            }
        }
        assertTrue(
            "write .imePadding().verticalScroll(…), not .verticalScroll(…).imePadding():\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The pattern itself: it must catch the shape that shipped, and pass the fix. */
    @Test fun `the guard tells the two orders apart`() {
        assertTrue(insideTheScroll.containsMatchIn("Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp)"))
        assertTrue(insideTheScroll.containsMatchIn("Modifier.verticalScroll(state)\n    .imePadding()"))
        assertTrue(!insideTheScroll.containsMatchIn("Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp)"))
    }
}
