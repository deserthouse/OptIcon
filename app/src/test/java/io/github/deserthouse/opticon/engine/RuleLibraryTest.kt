package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RuleLibrary 单测（修复批 F4 / X-07：规则库的读取路径恢复）。
 *
 * 应用侧启动加载与 hook 侧取目录都经由这里的入口。验收：切换中断后，读取入口
 * 能拿回旧版本；随后一次失败的同步不改变这个结果。
 */
class RuleLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: File
    private val rules get() = File(files, "fankes_cache")
    private val staging get() = FileSnapshotSwitch.stagingOf(files, "fankes_cache")
    private val prev get() = FileSnapshotSwitch.prevOf(files, "fankes_cache")

    /** 在指定的改名步骤上失败，其余动作走真实文件系统。 */
    private class FailOnRename(vararg val fromNames: String) : FileSnapshotSwitch.FsOps {
        override fun rename(from: File, to: File): Boolean =
            if (from.name in fromNames) false else from.renameTo(to)

        override fun deleteTree(file: File): Boolean = file.deleteRecursively()
    }

    @Before
    fun setUp() {
        files = tmp.newFolder("files")
    }

    private fun write(dir: File, files: Map<String, String>) {
        dir.mkdirs()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
    }

    private fun contentOf(name: String) = File(rules, name).readText()

    @Test
    fun `cold start after a kill between any two switch steps yields one complete version`() {
        val old = mapOf("A.png" to "old-A", "B.png" to "old-B", "meta.json" to """{"apps":["A","B"]}""")
        val new = mapOf("A.png" to "new-A", "C.png" to "new-C", "meta.json" to """{"apps":["A","C"]}""")

        for (step in 0..4) {
            files = tmp.newFolder("kill-after-step-$step")
            write(rules, old)
            write(staging, new)
            when (step) {
                0 -> Unit
                1 -> Unit
                2 -> rules.renameTo(prev)
                3 -> {
                    rules.renameTo(prev)
                    staging.renameTo(rules)
                }
                4 -> {
                    rules.renameTo(prev)
                    staging.renameTo(rules)
                    prev.deleteRecursively()
                }
            }

            assertTrue("read entry must succeed (step $step)", RuleLibrary.prepareForRead(rules))

            val expected = if (step <= 2) setOf("A", "B") else setOf("A", "C")
            assertEquals("killed after step $step", expected, RuleLibrary.packages(rules))
            assertEquals("killed after step $step", if (step <= 2) "old-A" else "new-A", contentOf("A.png"))
            assertFalse("killed after step $step", prev.exists())
            assertFalse("killed after step $step", staging.exists())
        }
    }

    @Test
    fun `a failed sync after recovery leaves the restored version intact`() {
        write(rules, mapOf("A.png" to "old-A", "B.png" to "old-B", "meta.json" to """{"apps":["A","B"]}"""))
        rules.renameTo(prev) // 上次同步在「旧版已让位、新版未上位」时被杀
        write(staging, mapOf("A.png" to "half-A"))

        assertTrue(RuleLibrary.prepareForRead(rules))
        // 随后一次失败的同步（连接被拒，下载不到归档）
        val outcome = AnipSync.syncDetailed(rules, url = "http://127.0.0.1:1/bundle.zip")

        assertEquals(0, outcome.count)
        assertEquals(setOf("A", "B"), RuleLibrary.packages(rules))
        assertEquals("old-B", contentOf("B.png"))
        assertFalse(prev.exists())
        assertFalse(staging.exists())
        assertFalse(File(files, "fankes_cache.zip.tmp").exists())
    }

    @Test
    fun `sync entry refuses and creates no dir when the old version cannot be restored`() {
        // live 缺失、prev 里是唯一完好的旧版，而此刻放不回去。
        // 入口必须放弃同步——建一个空的 live 就会让下一次收敛删掉 prev。
        prev.mkdirs()
        File(prev, "A.png").writeText("old-A")

        assertFalse(RuleLibrary.prepareForSync(rules, FailOnRename("fankes_cache.prev")))

        assertFalse(rules.exists())
        assertEquals("old-A", File(prev, "A.png").readText())
    }

    @Test
    fun `the sync shell itself refuses without touching anything when restore is impossible`() {
        prev.mkdirs()
        File(prev, "A.png").writeText("old-A")

        val outcome = AnipSync.syncDetailed(
            rules, url = "http://127.0.0.1:1/bundle.zip", ops = FailOnRename("fankes_cache.prev")
        )

        assertEquals(0, outcome.count)
        assertFalse(rules.exists())
        assertEquals("old-A", File(prev, "A.png").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun `ensureDir creates the dir only once the state is clean`() {
        assertFalse(rules.exists())

        assertTrue(RuleLibrary.ensureDir(rules))
        assertTrue(rules.isDirectory)

        // 已有中间态且放不回去：不建目录
        val other = File(tmp.newFolder("other"), "rules")
        FileSnapshotSwitch.prevOf(other.parentFile, other.name).let { p ->
            p.mkdirs()
            File(p, "A.png").writeText("old-A")
        }
        assertFalse(RuleLibrary.ensureDir(other, FailOnRename("rules.prev")))
        assertFalse(other.exists())
    }

    @Test
    fun `packages lists rule pngs only`() {
        write(rules, mapOf("A.png" to "x", "B.png" to "x", "meta.json" to "{}", "meta.json.tmp" to "x", "note.txt" to "x"))

        assertEquals(setOf("A", "B"), RuleLibrary.packages(rules))
        assertEquals(emptySet<String>(), RuleLibrary.packages(File(files, "absent")))
    }

    @Test
    fun `read entry leaves a staging dir alone while a sync may be building it`() {
        write(rules, mapOf("A.png" to "old-A"))
        write(staging, mapOf("half.png" to "x"))
        try {
            SyncGate.tryAcquire() // 模拟「同步进行中」

            assertTrue(RuleLibrary.prepareForRead(rules))

            assertTrue(File(staging, "half.png").exists())
        } finally {
            SyncGate.release()
        }

        // 同步结束后的读取：暂存目录被清掉
        assertTrue(RuleLibrary.prepareForRead(rules))
        assertFalse(staging.exists())
    }
}
