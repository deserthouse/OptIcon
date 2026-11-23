package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.domain.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RuleSyncFollowUp 单测（修复批 X-04 / X-21）。 */
class RuleSyncFollowUpTest {

    private val enabled = setOf("com.custom")

    private val customized = { pkg: String -> pkg in enabled }

    @Test
    fun `revoked package without customization is selected for deletion`() {
        assertEquals(
            listOf("com.plain"),
            RuleSyncFollowUp.mirrorsToDelete(listOf("com.plain"), customized)
        )
    }

    @Test
    fun `revoked package the user customized is kept - whatever the strategy`() {
        // 保守默认：只要为这个应用启用过自定义，镜像一律不动
        assertEquals(
            emptyList<String>(),
            RuleSyncFollowUp.mirrorsToDelete(listOf("com.custom"), customized)
        )
    }

    @Test
    fun `selection keeps only the non-customized ones in order`() {
        assertEquals(
            listOf("com.a", "com.c"),
            RuleSyncFollowUp.mirrorsToDelete(listOf("com.a", "com.custom", "com.c"), customized)
        )
    }

    @Test
    fun `nothing revoked means nothing to delete`() {
        assertTrue(RuleSyncFollowUp.mirrorsToDelete(emptyList(), customized).isEmpty())
    }

    @Test
    fun `legacy rules file is ingested only when the source really is legacy JSON`() {
        // X-21：规则库（ANIP）同步之后若再导入盘上残留的旧版规则文件，
        // 刚同步好的结果会被旧内容覆盖
        assertTrue(RuleSyncFollowUp.shouldIngestLegacyRules(SourceFormat.LEGACY_JSON))
        assertFalse(RuleSyncFollowUp.shouldIngestLegacyRules(SourceFormat.ANIP_BUNDLE))
        assertFalse(RuleSyncFollowUp.shouldIngestLegacyRules(SourceFormat.PICP_ZIPBALL))
        assertFalse(RuleSyncFollowUp.shouldIngestLegacyRules(SourceFormat.UNSPECIFIED))
    }
}
