package io.github.deserthouse.opticon.engine.publish

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PublishCore 不变量回归（F1-1，post-change-review-2026-10-19 审查夹具收编）。
 *
 * 审查时四不变量在生产 PublishTransaction 的内存替身夹具上全部失败；
 * 本测试以 JUnit TemporaryFolder（真临时目录）+ 内存 PublishStore 假体
 * 逐条转 PASS，常驻防回归：
 * ①恢复未完成意图前必须有一致性证据（不得按"文件存在"清记录）
 * ②补偿写回失败必须返回失败
 * ③首次自定义失败不得移除既有共享旧版
 * ④发布成功必须核对实际交付内容
 */
class PublishCoreInvariantsTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeStore : PublishStore {
        var refuseDeliver = false
        var corruptBytes = false
        val delivered = LinkedHashMap<String, ByteArray>()

        override fun deliver(pkg: String, bytes: ByteArray): Boolean {
            if (refuseDeliver) return false
            // corruptBytes 模拟"声称成功但交付了错误字节"的替身配送端
            delivered[pkg] = if (corruptBytes) byteArrayOf(0xDE.toByte(), 0xAD.toByte()) else bytes.copyOf()
            return true
        }

        override fun readDelivered(pkg: String): ByteArray? = delivered[pkg]?.copyOf()

        override fun remove(pkg: String): Boolean {
            delivered.remove(pkg)
            return true
        }
    }

    private class FakeLog : PublishLog {
        val lines = ArrayList<String>()
        override fun i(m: String) { lines.add("I $m") }
        override fun e(m: String) { lines.add("E $m") }
    }

    private val OLD = byteArrayOf(1, 1, 1, 1)
    private val NEW = byteArrayOf(2, 2, 2, 2)
    private val RULES_OLD = byteArrayOf(9, 9, 9, 9)
    private val OLD_CFG = """{"enabled":true,"strategy":"FANKES"}"""
    private val NEW_CFG = """{"enabled":true,"strategy":"ALGORITHM"}"""

    private lateinit var store: FakeStore
    private lateinit var log: FakeLog
    private lateinit var core: PublishCore
    private lateinit var opsDir: File

    private fun newCore() {
        store = FakeStore()
        log = FakeLog()
        opsDir = tmp.newFolder("ops${System.nanoTime()}")
        core = PublishCore(store, opsDir, tmp.newFolder("baked${System.nanoTime()}"), log)
    }

    // ━━ 不变量①：中断于 record 后 publish 前 → 恢复旧产物+旧配置，不按存在清记录 ━━

    @Test
    fun `recover restores old artifact and config when interrupted before publish`() {
        newCore()
        // 旧态：私有旧产物 + 共享位为旧版
        core.prepare("com.a", OLD, OLD_CFG, NEW_CFG)
        store.deliver("com.a", OLD)
        // 新一次保存：prepare（旧产物进备份）→ record → 崩溃（未 publish）
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op))
        assertArrayEquals(NEW, File(op.newPath).readBytes())

        val applied = ArrayList<String?>()
        core.recover { applied.add(it); true }

        // 恢复后：共享位与私有位都是旧产物、配置回滚到旧快照
        assertArrayEquals(OLD, store.readDelivered("com.a"))
        assertArrayEquals(OLD, File(op.newPath).readBytes())
        assertEquals(listOf(OLD_CFG), applied)
        // 记录与备份仅在恢复成功后清除
        assertFalse(File(opsDir, "${op.opId}.json").exists())
        assertFalse(File(op.backupPath!!).exists())
    }

    @Test
    fun `recover keeps record as Partial when restore deliver is refused`() {
        newCore()
        core.prepare("com.a", OLD, OLD_CFG, NEW_CFG)
        store.deliver("com.a", OLD)
        val op2 = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op2))
        store.refuseDeliver = true

        val applied = ArrayList<String?>()
        core.recover { applied.add(it); true }

        // 恢复失败：记录保留（Partial），不做任何配置变更
        assertTrue(File(opsDir, "${op2.opId}.json").exists())
        assertTrue(applied.isEmpty())
        assertTrue(log.lines.any { it.startsWith("E ") && it.contains("Partial") })
    }

    // ━━ 不变量②：补偿写回失败必须返回失败 ━━

    @Test
    fun `compensate returns false when restore deliver is refused`() {
        newCore()
        val op = core.prepare("com.a", OLD, OLD_CFG, NEW_CFG)!!
        store.deliver("com.a", OLD)
        val op2 = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op2))
        assertTrue(core.publish(op2)) // 共享位 = NEW
        store.refuseDeliver = true

        assertFalse(core.compensate(op2))
        // 共享位保持 NEW（没有虚报恢复成功）
        assertArrayEquals(NEW, store.readDelivered("com.a"))
    }

    @Test
    fun `compensate returns false when restored bytes do not verify`() {
        newCore()
        core.prepare("com.a", OLD, OLD_CFG, NEW_CFG) // 种子：使下一发 prepare 产生旧产物备份
        store.deliver("com.a", OLD)
        val op2 = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op2))
        assertTrue(core.publish(op2))
        store.corruptBytes = true // 配送端声称成功但写错字节

        // 补偿尝试把交付位写成了错误内容 → 必须报失败（Partial，记录保留待重启重试）
        assertFalse(core.compensate(op2))
        assertTrue(File(opsDir, "${op2.opId}.json").exists())
    }

    // ━━ 不变量③：首次自定义失败不得移除既有共享旧版 ━━

    @Test
    fun `first custom publish failure preserves pre-existing shared artifact`() {
        newCore()
        // 共享位已有规则库镜像（非本事务产物），无私有备份
        store.deliver("com.a", RULES_OLD)
        val op = core.prepare("com.a", NEW, null, NEW_CFG)!!
        assertNull(op.backupPath)
        assertTrue(core.record(op))
        store.refuseDeliver = true
        assertFalse(core.publish(op))

        // 补偿：交付位内容不是本次产物 → 保留，不删除
        assertTrue(core.compensate(op))
        assertArrayEquals(RULES_OLD, store.readDelivered("com.a"))
    }

    @Test
    fun `first publish failure removes own artifact only when delivered`() {
        newCore()
        val op = core.prepare("com.a", NEW, null, NEW_CFG)!!
        assertTrue(core.record(op))
        assertTrue(core.publish(op)) // 交付位 = NEW（本次产物）
        assertArrayEquals(NEW, store.readDelivered("com.a"))

        assertTrue(core.compensate(op))
        assertNull(store.readDelivered("com.a")) // 本次创建的 → 删除
    }

    // ━━ 不变量④：发布成功必须核对实际交付内容 ━━

    @Test
    fun `publish fails when delivered bytes differ from sent`() {
        newCore()
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op))
        store.corruptBytes = true // 假体：声称成功、交付错误字节

        assertFalse(core.publish(op))
        // 记录仍为 PREPARED（未升级 PUBLISHED）
        val record = File(opsDir, "${op.opId}.json").readText()
        assertTrue(record.contains("\"stage\":\"PREPARED\""))
    }

    // ━━ Happy path ━━

    @Test
    fun `full happy path publishes then completes cleanly`() {
        newCore()
        core.prepare("com.a", OLD, null, null)
        store.deliver("com.a", OLD)

        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op))
        assertTrue(core.publish(op))
        assertArrayEquals(NEW, store.readDelivered("com.a"))
        // 发布后记录升级 PUBLISHED
        assertTrue(File(opsDir, "${op.opId}.json").readText().contains("\"stage\":\"PUBLISHED\""))

        core.complete(op)
        assertFalse(File(opsDir, "${op.opId}.json").exists())
        assertFalse(File(op.backupPath!!).exists())
        assertArrayEquals(NEW, File(op.newPath).readBytes())
    }

    // ━━ 补充：已发布但配置未提交 → recover 幂等补提交 ━━

    @Test
    fun `recover applies new config when publish confirmed but commit interrupted`() {
        newCore()
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op))
        assertTrue(core.publish(op))
        // 崩溃于 publish 后、配置提交前

        val applied = ArrayList<String?>()
        core.recover { applied.add(it); true }
        assertEquals(listOf(NEW_CFG), applied)
        assertFalse(File(opsDir, "${op.opId}.json").exists())
        op.backupPath?.let { assertFalse(File(it).exists()) }
    }

    // ━━ 补充：损坏记录 → .corrupt 隔离，不阻断后续恢复 ━━

    @Test
    fun `corrupt record is quarantined and does not block recovery`() {
        newCore()
        opsDir.mkdirs()
        File(opsDir, "broken.json").writeText("{not json at all")
        core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)
        store.deliver("com.a", OLD)
        val op2 = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op2))
        store.refuseDeliver = true // 第二条走 Partial，验证 broken 被隔离后仍处理到第二条

        core.recover { true }
        assertTrue(File(opsDir, "broken.json.corrupt").exists())
        assertFalse(File(opsDir, "broken.json").exists())
        assertTrue(File(opsDir, "${op2.opId}.json").exists())
    }

    // ━━ 补充：旧版本记录（无 stage/sha/config 字段）兼容恢复 ━━

    @Test
    fun `legacy record without config fields is handled artifact-only`() {
        newCore()
        store.deliver("com.a", RULES_OLD)
        opsDir.mkdirs()
        // v0.13/14 记录形态：无 stage/sha/config；backup 为空串
        File(opsDir, "legacy.json").writeText(
            """{"opId":"legacy-1","pkg":"com.a","ts":123,"backup":"","new":"${tmp.root.path.replace(92.toChar(), 47.toChar())}/nowhere.png"}"""
        )

        val applied = ArrayList<String?>()
        core.recover { applied.add(it); true }
        // 交付位非本次产物 → 保留不动；无旧配置 → 不触碰配置
        assertArrayEquals(RULES_OLD, store.readDelivered("com.a"))
        assertTrue(applied.isEmpty())
        assertFalse(File(opsDir, "legacy.json").exists())
    }

    // ━━ 补充：JSON 嵌套转义往返 ━━

    @Test
    fun `config json with escapes survives record round trip`() {
        newCore()
        val tricky = """{"scale":1.5,"path":"C:\\Users\\x","emoji":"🌸\n\t\"q\""}"""
        val op = core.prepare("com.a", NEW, tricky, NEW_CFG)!!
        assertTrue(core.record(op))

        val parsed = core.fromRecord(File(opsDir, "${op.opId}.json").readText())
        assertEquals(tricky, parsed?.oldConfigJson)
        assertEquals(NEW_CFG, parsed?.newConfigJson)
        assertEquals(PublishCore.Stage.PREPARED, parsed?.stage)
        assertEquals(PublishCore.sha256(NEW), parsed?.newSha)
    }

    // ━━ C03：恢复配置提交失败 → 记录保留（Partial），不静默清账 ━━

    @Test
    fun `recover keeps record when config commit returns false`() {
        newCore()
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertTrue(core.record(op))
        assertTrue(core.publish(op))
        // 崩溃于 publish 后；恢复时配置提交失败
        var failConfig = true
        core.recover { if (it == NEW_CFG && failConfig) false else true }
        assertTrue(File(opsDir, "${op.opId}.json").exists()) // 记录保留
        // 重试成功后清除
        failConfig = false
        core.recover { true }
        assertFalse(File(opsDir, "${op.opId}.json").exists())
    }

    // ━━ C04：首次自定义覆盖共享旧版后失败 → 补偿恢复原共享内容 ━━

    @Test
    fun `first custom publish overwriting shared rules artifact restores it on compensation`() {
        newCore()
        // 共享位已有规则库镜像（9），无私有 baked——prepare 应快照共享旧态
        store.delivered["com.a"] = RULES_OLD.copyOf()
        val op = core.prepare("com.a", NEW, null, NEW_CFG)!!
        assertNull(op.backupPath)
        assertNotNull(op.sharedBackupPath)
        assertTrue(core.record(op))
        // deliver 成功（共享位已被覆盖为 NEW），随后 verify/commit 失败 → 补偿
        store.corruptBytes = true
        assertFalse(core.publish(op))
        store.corruptBytes = false
        assertTrue(core.compensate(op))
        assertArrayEquals(RULES_OLD, store.readDelivered("com.a")) // 原共享内容恢复
    }

    @Test
    fun `shared backup cleaned on complete`() {
        newCore()
        store.delivered["com.a"] = RULES_OLD.copyOf()
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        val sharedBak = File(op.sharedBackupPath!!)
        assertTrue(sharedBak.exists())
        assertTrue(core.record(op))
        assertTrue(core.publish(op))
        core.complete(op)
        assertFalse(sharedBak.exists())
    }

    // ━━ V07：恢复走共享快照（无私有备份、交付位错位、补偿曾失败） ━━

    @Test
    fun `recover restores shared snapshot when private backup is absent and delivery is misplaced`() {
        newCore()
        // 探针顺序：shared=9、无私有旧文件 → prepare(2)（此时快照 9）→ record
        // → 交付写错 3 且报告失败 → 同步补偿再失败 → journal 保留 → recover
        store.delivered["com.a"] = RULES_OLD.copyOf()
        val op = core.prepare("com.a", NEW, OLD_CFG, NEW_CFG)!!
        assertNull(op.backupPath)
        assertNotNull(op.sharedBackupPath)
        assertTrue(core.record(op))
        store.corruptBytes = true
        assertFalse(core.publish(op)) // 交付位被写坏且报告失败
        store.refuseDeliver = true
        assertFalse(core.compensate(op)) // 同步补偿再失败 → Partial
        store.refuseDeliver = false
        store.corruptBytes = false

        val applied = ArrayList<String?>()
        core.recover { applied.add(it) }
        // 交付位恢复为快照 9、配置回旧、记录与快照清理
        assertArrayEquals(RULES_OLD, store.readDelivered("com.a"))
        assertEquals(listOf(OLD_CFG), applied)
        assertFalse(File(opsDir, "${op.opId}.json").exists())
    }
}
