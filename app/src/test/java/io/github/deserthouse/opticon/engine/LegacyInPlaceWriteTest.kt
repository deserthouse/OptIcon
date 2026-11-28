package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧版规则就地写入时对库目录做的两件事（2026-11-28 盲审 BU-05）：留下来源标记、作废规则库的版本记录。
 * 对称的那一半是 [SubscriptionManager.needsApply]（切回旧版源时看标记），见 LegacyNeedsApplyTest；
 * 这一半管的是切回规则库时不再误报「已是最新」，见 AnipReleaseFlowTest 里带 legacy origin marker 的用例。
 */
class LegacyInPlaceWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rulesWithRecord(): File = tmp.newFolder("fankes_cache").apply {
        File(this, "com.a.png").writeBytes("anip-a".toByteArray())
        File(this, RuleLibrary.SOURCE_FILE).writeText("""{"channel":"release","tag":"abc1234","timestamp":1,"sha256":null,"icons":1}""")
    }

    @Test
    fun `a legacy write invalidates the rule library version record and leaves its origin marker`() {
        val dir = rulesWithRecord()

        IconLibEngine.markLegacyInPlace(dir, 3)

        assertFalse("版本记录留着的话，下一次规则库同步会误判成已是最新", File(dir, RuleLibrary.SOURCE_FILE).exists())
        assertNull(RuleLibrary.readSource(dir))
        assertEquals("legacy", File(dir, IconLibEngine.LEGACY_ORIGIN_FILE).readText())
    }

    @Test
    fun `a legacy write that produced no icons still invalidates the record`() {
        // 清单里一个图标都没有：meta 已被覆盖成空的，记录同样不能再信
        val dir = rulesWithRecord()

        IconLibEngine.markLegacyInPlace(dir, 0)

        assertFalse(File(dir, RuleLibrary.SOURCE_FILE).exists())
        assertTrue("没有图标就不留标记", !File(dir, IconLibEngine.LEGACY_ORIGIN_FILE).exists())
    }
}
