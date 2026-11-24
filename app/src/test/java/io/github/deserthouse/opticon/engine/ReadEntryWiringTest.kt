package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.FakeFilesContext
import io.github.deserthouse.opticon.hook.HookLibSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 读入口的接线测试（修复批 X-07 / X-08）。RuleLibrary 自身的收敛逻辑另有测试；
 * 这里执行的是生产代码里真正被调用的入口——IconLibEngine.hasMetaIndex（应用启动）
 * 与 HookLibSync.dir（hook 侧首次取目录）——第二轮审查的变异实验显示，把这两处的收敛
 * 删掉或改回「先建目录」，现有测试全都不会变红。
 */
class ReadEntryWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetHookDirCache() {
        // HookLibSync.dir 每个进程只收敛一次（结果缓存）；测试之间把这个缓存清掉
        HookLibSync::class.java.getDeclaredField("iconDir").apply { isAccessible = true }.set(HookLibSync, null)
    }

    private fun write(dir: File, files: Map<String, String>) {
        dir.mkdirs()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
    }

    private val meta = """{"apps":[{"packageName":"A","appName":"A"}]}"""

    @Test
    fun `app startup entry - an interrupted switch still yields the old snapshot`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.filesDir, "fankes_cache")
        val prev = FileSnapshotSwitch.prevOf(ctx.filesDir, "fankes_cache")
        write(dir, mapOf("A.png" to "png-A", "meta.json" to meta))
        dir.renameTo(prev) // 旧版已让位、新版未上位时进程被杀

        assertTrue("the old snapshot must be restored and visible", IconLibEngine.hasMetaIndex(ctx))

        assertFalse(prev.exists())
        assertEquals("png-A", File(dir, "A.png").readText())
    }

    @Test
    fun `app startup entry - a clean snapshot is left alone`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.filesDir, "fankes_cache")
        write(dir, mapOf("A.png" to "png-A", "meta.json" to meta))

        assertTrue(IconLibEngine.hasMetaIndex(ctx))
        assertEquals("png-A", File(dir, "A.png").readText())
        assertEquals(setOf("A.png", "meta.json"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `hook entry - an interrupted switch is restored and never replaced by an empty dir`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.cacheDir, "opticon_hook_icons")
        val prev = FileSnapshotSwitch.prevOf(ctx.cacheDir, "opticon_hook_icons")
        write(dir, mapOf("A.png" to "png-A"))
        dir.renameTo(prev)

        val d = HookLibSync.dir(ctx)

        assertEquals(dir, d)
        // 让位失败时把备份删掉的那套老回滚、以及「先建空目录再收敛」，都会在这里丢掉 A
        assertEquals("png-A", File(d, "A.png").readText())
        assertFalse(prev.exists())
        assertEquals(listOf("A.png"), d.listFiles()!!.map { it.name })
    }

    @Test
    fun `hook entry - a first install just gets an empty dir`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))

        val d = HookLibSync.dir(ctx)

        assertTrue(d.isDirectory)
        assertEquals(0, d.listFiles()!!.size)
    }

    @Test
    fun `read path removes a leftover download archive when idle and keeps it while a sync holds the gate`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.filesDir, "fankes_cache")
        write(dir, mapOf("A.png" to "png-A", "meta.json" to meta))
        val leftover = File(ctx.filesDir, "fankes_cache" + RuleLibrary.ARCHIVE_TMP_SUFFIX)
        leftover.writeText("half-downloaded archive")

        assertTrue(SyncGate.tryAcquire()) // 模拟「有同步正在跑」：归档可能正被写入
        try {
            IconLibEngine.hasMetaIndex(ctx)
            assertTrue("must not touch the archive while a sync may be writing it", leftover.exists())
        } finally {
            SyncGate.release()
        }

        IconLibEngine.hasMetaIndex(ctx)
        assertFalse("an idle read path cleans the leftover archive", leftover.exists())
    }
}
