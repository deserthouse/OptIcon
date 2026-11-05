package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FileSnapshotSwitch 单测（批 SYNC V1/V2 共用切换原语）。
 *
 * 覆盖 V1 验收（切换成功/失败回滚/首装/中断恢复）与 V2 验收的语义等价夹具：
 * ANIP 撤销 = 新快照不含该包 → 切换后旧包物理消失；meta 与 PNG 同快照 =
 * 两者同处 staging 目录随切换整体生效（结构性保证，夹具断言切换后一致性）。
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

    // ━━ 失败回滚 ━━

    @Test
    fun `rollback restores old live when staging promotion fails`() {
        val parent = tmp.newFolder("p3")
        makeLive(parent, "icons", mapOf("a.png" to "old-a", "b.png" to "old-b"))
        // staging 目录不存在 → switchIn 的 staging→live 步骤必然失败（b 已执行：
        // live 已让位到 prev，模拟图纸"步骤 b/d 失败注入"的 b 侧）
        try {
            FileSnapshotSwitch.switchIn(parent, "icons")
            throw AssertionError("switchIn should have thrown")
        } catch (e: FileSnapshotSwitch.SwitchException) {
            // expected
        }
        FileSnapshotSwitch.rollback(parent, "icons")

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertTrue(contentOf(live, "b.png") == "old-b")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `afterPromote failure rolls back to old snapshot`() {
        // PicpEngine 的根索引换位走 afterPromote：它失败时提交点未到，
        // prev 尚在，必须能完整回到旧快照（单测曾抓出"先删 prev 后跑
        // 配套步骤"的设计缺陷——本用例钉死该回归）
        val parent = tmp.newFolder("p4")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a", "index.txt" to "a"))

        try {
            FileSnapshotSwitch.switchIn(parent, "icons") {
                throw FileSnapshotSwitch.SwitchException("index→tmp")
            }
            throw AssertionError("switchIn should have rethrown")
        } catch (e: FileSnapshotSwitch.SwitchException) {
            // expected
        }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a") // 回到旧版
        assertFalse(File(live, "index.txt").exists())
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
        assertFalse(FileSnapshotSwitch.stagingOf(parent, "icons").exists())
    }

    @Test
    fun `afterPromote success reaches commit and cleans prev`() {
        val parent = tmp.newFolder("p4b")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))

        FileSnapshotSwitch.switchIn(parent, "icons") {
            // 模拟配套落位：成功
        }

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "new-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
    }

    @Test
    fun `rollback after committed switch is a safe no-op keeping new live`() {
        val parent = tmp.newFolder("p4c")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))
        makeStaging(parent, "icons", mapOf("a.png" to "new-a"))
        FileSnapshotSwitch.switchIn(parent, "icons") // 已提交（prev 已清理）

        // 提交点之后 prev 不存在——rollback 不得破坏新 live
        FileSnapshotSwitch.rollback(parent, "icons")

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "new-a")
    }

    @Test
    fun `rollback is a safe no-op without switch residue`() {
        val parent = tmp.newFolder("p5")
        makeLive(parent, "icons", mapOf("a.png" to "old-a"))

        FileSnapshotSwitch.rollback(parent, "icons") // 不抛、不动旧态

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "old-a")
    }

    // ━━ 崩溃恢复 ━━

    @Test
    fun `recover rolls back when live missing and prev present`() {
        val parent = tmp.newFolder("p6")
        // 模拟 b 完成后被杀：live 已改名 prev，staging 尚未上位
        val prev = FileSnapshotSwitch.prevOf(parent, "icons")
        prev.mkdirs()
        File(prev, "a.png").writeText("old-a")
        FileSnapshotSwitch.stagingOf(parent, "icons").mkdirs()

        FileSnapshotSwitch.recoverIfInterrupted(parent, "icons")

        val live = File(parent, "icons")
        assertTrue(contentOf(live, "a.png") == "old-a")
        assertFalse(prev.exists())
    }

    @Test
    fun `recover keeps committed live and drops stale prev`() {
        val parent = tmp.newFolder("p7")
        makeLive(parent, "icons", mapOf("a.png" to "new-a")) // 已提交
        FileSnapshotSwitch.prevOf(parent, "icons").mkdirs() // d 清理被中断

        FileSnapshotSwitch.recoverIfInterrupted(parent, "icons")

        assertTrue(contentOf(File(parent, "icons"), "a.png") == "new-a")
        assertFalse(FileSnapshotSwitch.prevOf(parent, "icons").exists())
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
