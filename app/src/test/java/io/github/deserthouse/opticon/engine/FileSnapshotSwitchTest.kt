package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FileSnapshotSwitch 单测（批 SYNC V1/V2 共用切换原语）。
 *
 * 覆盖：切换成功 / 首装 / 每一步出错后的目录状态 / 遗留中间态的收敛 / 崩溃恢复，
 * 以及 V2 验收的语义等价夹具：ANIP 撤销 = 新快照不含该包 → 切换后旧包物理消失；
 * meta 与 PNG 同快照 = 两者同处 staging 目录随切换整体生效。
 *
 * 修复批（X-06/X-23）钉死的回归：
 *  - 没有「配套步骤」，提交点唯一，失败由原语自己恢复，调用方不回滚；
 *  - live 完好 + prev 只是陈旧残留时，任何路径都不删 live。
 */
class FileSnapshotSwitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 构建一个"已发布"目录：files 映射 name→内容 */
    private fun makeLive(parent: File, name: String, files: Map<String, String>): File {
        val dir = File(parent, name)
        dir.mkdirs()
        files.forEach { (n, c) -> File(dir, n).writeText(c) }
        return dir
    }

    private fun makeStaging(parent: File, name: String, files: Map<String, String>): File {
        val staging = FileSnapshotSwitch.stagingOf(parent, name)
        staging.mkdirs()
        files.forEach { (n, c) -> File(staging, n).writeText(c) }
        return staging
    }

    private fun contentOf(dir: File, name: String) = File(dir, name).readText()

    /** 在指定的改名步骤上失败（按源路径名匹配），其余动作走真实文件系统。 */
    private class FailOnRename(vararg val fromNames: String) : FileSnapshotSwitch.FsOps {
        override fun rename(from: File, to: File): Boolean =
            if (from.name in fromNames) false else from.renameTo(to)

        override fun deleteTree(file: File): Boolean = file.deleteRecursively()
    }

    private fun expectSwitchFailure(block: () -> Unit): FileSnapshotSwitch.SwitchException {
        try {
            block()
        } catch (e: FileSnapshotSwitch.SwitchException) {
            return e
        }
        fail("switchIn should have thrown")
        throw AssertionError("unreachable")
    }

    // ━━ switchIn 成功路径 ━━

    @Test
    fun `switchIn replaces live dir wholesale and cleans prev and staging`() {
        val parent = tmp.newFolder("p1")
        makeLive(parent, "icons", mapOf("a.png" to "old-a", "b.png" to "old-b"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a", "index.txt" to "a"))

        FileSnapshotSwitch.switchIn(parent, "icons")

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "new-a")
        assertTrue(contentOf(live, "index.txt") == "a")
        assertFalse(File(live, "b.png").exists()) // 旧包物理消失（V2 撤销语义）
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `switchIn works on first install with no live dir`() {
        val parent = tmp.newFolder("p2")
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        FileSnapshotSwitch.switchIn(parent, "icons")

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "new-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    // ━━ 每一步出错后的状态：原语自己恢复 ━━

    @Test
    fun `staging promotion failure restores old live without caller rollback`() {
        val parent = tmp.newFolder("p3")
        makeLive(parent, "icons", mapOf("a.png" to "old-a", "b.png" to "old-b"))
        // staging 目录不存在 → staging→live 必然失败（b 已执行：live 已让位到 prev）
        expectSwitchFailure { FileSnapshotSwitch.switchIn(parent, "icons") }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertTrue(contentOf(live, "b.png") == "old-b")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `step c failure with staging present restores old live and drops staging`() {
        val parent = tmp.newFolder("p3b")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        expectSwitchFailure { FileSnapshotSwitch.switchIn(parent, "icons", FailOnRename("icons.staging")) }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `step b failure leaves live untouched`() {
        val parent = tmp.newFolder("p3c")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        expectSwitchFailure { FileSnapshotSwitch.switchIn(parent, "icons", FailOnRename("icons")) }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `step c failure whose restore also fails stays recoverable by the read path`() {
        val parent = tmp.newFolder("p3d")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        val e = expectSwitchFailure {
            FileSnapshotSwitch.switchIn(parent, "icons", FailOnRename("icons.staging", "icons.prev"))
        }
        assertTrue(e.message!!.contains("restore deferred"))
        // 中间态：live 缺失、prev 里是完好的旧版、staging 已清
        assertFalse(File(parent, "icons").exists())
        assertTrue(contentOf(FileSnapshotSwitch.prevOf(parent, "icons"), "a.png") == "old-a")
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())

        // 读取路径冷启恢复 → 旧版放回
        assertTrue(FileSnapshotSwitch.recoverIfInterrupted(parent, "icons"))
        assertTrue(contentOf(File(parent, "icons"), "a.png") == "old-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    // ━━ 遗留中间态：切换入口先收敛 ━━

    @Test
    fun `live missing and prev present is converged before switching`() {
        val parent = tmp.newFolder("p4a")
        // 上次在 b 之后、c 之前被杀：旧版停在 prev 里，新版本没上线
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        FileSnapshotSwitch.prevOf(parent, "icons").let { prev ->
            File(parent, "icons").renameTo(prev)
        }
        // 本次同步已建好新的 staging
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        FileSnapshotSwitch.switchIn(parent, "icons")

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "new-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    @Test
    fun `stale prev next to a live dir means committed and is dropped`() {
        val parent = tmp.newFolder("p4b")
        makeLive(parent, "icons", mapOf("a.png" to "current-a"))
        FileSnapshotSwitch.prevOf(parent, "icons").let { prev ->
            prev.mkdirs()
            File(prev, "a.png").writeText("older-a")
        }
        makeStaging(parent, "icons", mapOf("a.png" to "newer-a"))

        FileSnapshotSwitch.switchIn(parent, "icons")

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "newer-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    @Test
    fun `an intact live dir survives a failed switch even with a stale prev next to it`() {
        // X-23 的核心场景：live 完好，旁边只是上次留下的陈旧 prev，本次切换失败。
        // 旧的回滚函数在这里会「删掉 live、把陈旧的 prev 放回来」——完好的当前版本
        // 被一个过时的备份顶掉。陈旧 prev 必须在入口就被当作「已提交」丢弃，
        // 失败后放回的才是真正的当前版本。
        val parent = tmp.newFolder("p4d")
        makeLive(parent, "icons", mapOf("a.png" to "current-a", "b.png" to "current-b"))
        FileSnapshotSwitch.prevOf(parent, "icons").let { prev ->
            prev.mkdirs()
            File(prev, "a.png").writeText("older-a")
        }
        makeStaging(parent, "icons", mapOf("a.png" to "newer-a"))

        expectSwitchFailure {
            FileSnapshotSwitch.switchIn(parent, "icons", FailOnRename("icons.staging"))
        }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "current-a")
        assertTrue(contentOf(live, "b.png") == "current-b")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `a prev that cannot be put back makes switchIn refuse without deleting anything`() {
        val parent = tmp.newFolder("p4c")
        // live 缺失、prev 里是唯一完好的旧版；本次同步建好了新的 staging
        FileSnapshotSwitch.prevOf(parent, "icons").let { prev ->
            prev.mkdirs()
            File(prev, "a.png").writeText("old-a")
        }
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        expectSwitchFailure { FileSnapshotSwitch.switchIn(parent, "icons", FailOnRename("icons.prev")) }

        // prev 原样保留（没被当成陈旧残留删掉），live 仍然缺失，staging 已清
        assertTrue(contentOf(FileSnapshotSwitch.prevOf(parent, "icons"), "a.png") == "old-a")
        assertFalse(File(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())

        // 文件系统恢复之后，读取路径放回旧版
        assertTrue(FileSnapshotSwitch.recoverIfInterrupted(parent, "icons"))
        assertTrue(contentOf(File(parent, "icons"), "a.png") == "old-a")
    }

    // ━━ 崩溃恢复 ━━

    @Test
    fun `recover rolls back when live missing and prev present and clears staging`() {
        val parent = tmp.newFolder("p6")
        // 模拟 b 完成后被杀：live 已改名 prev，staging 尚未上位
        val prev = FileSnapshotSwitch.prevOf(parent, "icons")
        prev.mkdirs()
        File(prev, "a.png").writeText("old-a")
        FileSnapshotSwitch.stagingOf(parent, "icons").let { staging ->
            staging.mkdirs()
            File(staging, "half.png").writeText("half-built")
        }

        assertTrue(FileSnapshotSwitch.recoverIfInterrupted(parent, "icons"))

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertFalse(prev.exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `recover keeps committed live and drops stale prev`() {
        val parent = tmp.newFolder("p7")
        makeLive(parent, "icons", mapOf("a.png" to "new-a")) // 已提交
        FileSnapshotSwitch.prevOf(parent, "icons").mkdirs() // d 清理被中断

        assertTrue(FileSnapshotSwitch.recoverIfInterrupted(parent, "icons"))

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "new-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    @Test
    fun `recover keeps staging when a sync is building it`() {
        val parent = tmp.newFolder("p7b")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        FileSnapshotSwitch.stagingOf(parent, "icons").let { staging ->
            staging.mkdirs()
            File(staging, "half.png").writeText("half-built")
        }

        // 同步进行期间读路径调用：只收敛中间态，不碰正在构建的暂存目录
        FileSnapshotSwitch.recoverIfInterrupted(parent, "icons", clearStaging = false)

        assertTrue(contentOf(FileSnapshotSwitch.stagingOf(parent, "icons"), "half.png") == "half-built")
        assertTrue(contentOf(File(parent, "icons"), "a.png") == "old-a")
    }

    @Test
    fun `recover reports false and keeps prev when prev cannot be put back`() {
        val parent = tmp.newFolder("p7c")
        FileSnapshotSwitch.prevOf(parent, "icons").let { prev ->
            prev.mkdirs()
            File(prev, "a.png").writeText("old-a")
        }

        val converged = FileSnapshotSwitch.recoverIfInterrupted(
            parent, "icons", ops = FailOnRename("icons.prev")
        )

        assertFalse(converged)
        // 唯一完好的旧版没有被删
        assertTrue(contentOf(FileSnapshotSwitch.prevOf(parent, "icons"), "a.png") == "old-a")
        assertFalse(File(parent, "icons").exists())
    }

    @Test
    fun `recover is a no-op on a clean state`() {
        val parent = tmp.newFolder("p8")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))

        FileSnapshotSwitch.recoverIfInterrupted(parent, "icons")

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "old-a")
        assertEquals(1, File(parent, "icons").listFiles()!!.size)
    }

    // ━━ V2 语义等价夹具：ANIP 快照撤销 + meta/PNG 一致 ━━

    @Test
    fun `anip revocation fixture - version N to N plus one drops revoked package`() {
        val parent = tmp.newFolder("p9")
        // 版本 N：A、B 两包 + meta 引用两者
        makeLive(parent, "fankes_cache", mapOf(
            "A.png" to "png-a-n", "B.png" to "png-b-n",
            "meta.json" to """{"apps":["A","B"]}"""
        ))
        // 版本 N+1：上游撤销 B（staging 只含 A）
        makeStaging(parent, "fankes_cache", mapOf(
            "A.png" to "png-a-n1",
            "meta.json" to """{"apps":["A"]}"""
        ))

        FileSnapshotSwitch.switchIn(parent, "fankes_cache")

        val live = File(parent, "fankes_cache")
        // hasIcon(B)=false 等价：文件存在性查询的依据已消失
        assertFalse(File(live, "B.png").exists())
        assertTrue(File(live, "A.png").exists())
        // meta 与 PNG 同快照：meta 引用集与文件集一致（无"新 meta 引旧图"窗口）
        val meta = contentOf(live, "meta.json")
        assertFalse(meta.contains("B"))
        assertEquals(setOf("A.png", "meta.json"), live.listFiles()!!.map { it.name }.toSet())
    }
}
