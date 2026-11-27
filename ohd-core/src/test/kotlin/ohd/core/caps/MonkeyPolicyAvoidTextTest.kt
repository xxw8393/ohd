package ohd.core.caps

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonkeyPolicyAvoidTextTest {
    @Test
    fun emptyAvoidNeverMatches() {
        assertFalse(MonkeyPolicy.matchesAvoidText("OHOS运行时开关", null, emptyList()))
    }

    @Test
    fun matchesTextSubstringIgnoreCase() {
        assertTrue(
            MonkeyPolicy.matchesAvoidText(
                "打开 OHOS运行时开关",
                null,
                listOf("ohos运行时开关"),
            ),
        )
    }

    @Test
    fun matchesDescription() {
        assertTrue(
            MonkeyPolicy.matchesAvoidText(
                null,
                "OHOS运行时开关",
                listOf("OHOS运行时开关"),
            ),
        )
    }

    @Test
    fun unrelatedLabelPasses() {
        assertFalse(
            MonkeyPolicy.matchesAvoidText(
                "CustomFont",
                "demo",
                listOf("OHOS运行时开关"),
            ),
        )
    }
}
