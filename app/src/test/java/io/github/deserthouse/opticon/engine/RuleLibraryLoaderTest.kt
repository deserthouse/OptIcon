package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.FakeFilesContext
import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.util.InMemoryPrefs
import io.github.deserthouse.opticon.util.PreferenceManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 规则库从磁盘加载到内存索引的路径（应用启动与同步收尾共用 RuleLibraryLoader）。
 *
 * 钉死第二轮审查 N-01：设备上残留着 base64 时代的规则文件、当前源是规则库（ANIP）时，
 * 启动不得再导入旧文件覆盖刚同步好的快照。走的是生产代码的加载函数与未改动的
 * IconLibEngine；Context 只提供目录。
 */
class RuleLibraryLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val snapshotMeta = """{"apps":[{"packageName":"com.anip.x1","appName":"One"},{"packageName":"com.anip.x2","appName":"Two"}]}"""
    private val legacyJson = """[{"packageName":"com.legacy.x1","appName":"Legacy","iconBitmap":"AAAA"}]"""

    @Before
    fun setUp() {
        PreferenceManager.initForTest(InMemoryPrefs())
    }

    private fun contextWith(vararg legacy: String): FakeFilesContext {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.filesDir, "fankes_cache")
        dir.mkdirs()
        File(dir, "com.anip.x1.png").writeText("png-1")
        File(dir, "com.anip.x2.png").writeText("png-2")
        File(dir, "meta.json").writeText(snapshotMeta)
        legacy.forEach { File(ctx.filesDir, "icon_rules.json").writeText(it) }
        return ctx
    }

    @Test
    fun `startup with a legacy rules file and a rule-library snapshot keeps the snapshot byte for byte`() {
        val ctx = contextWith(legacyJson)
        val metaFile = File(ctx.filesDir, "fankes_cache/meta.json")
        val before = metaFile.readBytes()

        val plan = RuleLibraryLoader.load(ctx, SourceFormat.ANIP_BUNDLE)

        assertEquals(RuleSyncFollowUp.Load.READ_SNAPSHOT, plan)
        assertArrayEquals(before, metaFile.readBytes())
        assertEquals(2, IconLibEngine.getIndexedCount())
        assertTrue(IconLibEngine.hasIcon("com.anip.x1"))
        assertTrue(IconLibEngine.hasIcon("com.anip.x2"))
        assertFalse(IconLibEngine.hasIcon("com.legacy.x1"))
        assertFalse("the legacy file must not be imported into the snapshot dir", File(ctx.filesDir, "fankes_cache/com.legacy.x1.png").exists())
    }

    @Test
    fun `startup with a legacy source still ingests the legacy rules file`() {
        val ctx = contextWith(legacyJson)

        val plan = RuleLibraryLoader.load(ctx, SourceFormat.LEGACY_JSON)

        assertEquals(RuleSyncFollowUp.Load.INGEST_LEGACY_RULES, plan)
        assertEquals(1, IconLibEngine.getIndexedCount())
        assertEquals("Legacy", IconLibEngine.getMeta("com.legacy.x1")?.appName)
    }

    @Test
    fun `startup with a rule-library source and no snapshot ignores the legacy rules file`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        File(ctx.filesDir, "icon_rules.json").writeText(legacyJson)

        val plan = RuleLibraryLoader.load(ctx, SourceFormat.ANIP_BUNDLE)

        assertEquals(RuleSyncFollowUp.Load.NOTHING, plan)
        assertEquals(0, IconLibEngine.getIndexedCount())
        assertFalse(File(ctx.filesDir, "fankes_cache/meta.json").exists())
    }
}
