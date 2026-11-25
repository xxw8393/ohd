package ohd.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelpTextTest {

    @Test
    fun deliveredCapsAreListedOnceAndPlannedBlockIsGone() {
        val help = globalHelpText()
        assertFalse(help.contains("规划中"), "已交付能力不应再出现在规划中")

        val commands = help.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("""[a-z][a-z0-9-]*\s{2,}.*""")) }
            .map { it.substringBefore(' ') }
            .toList()

        assertEquals(commands, commands.distinct(), "能力名重复：${commands.groupingBy { it }.eachCount().filter { it.value > 1 }}")
        for (name in listOf(
            "install", "ab", "summary", "salvage",
            "dmabuf", "soak", "analyze", "repro", "report", "record",
        )) {
            assertTrue(name in commands, "帮助缺 $name")
        }
    }
}
