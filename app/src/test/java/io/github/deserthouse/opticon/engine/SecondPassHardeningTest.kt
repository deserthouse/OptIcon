package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.FakeFilesContext
import io.github.deserthouse.opticon.hook.HookLibSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第二轮审查里几处小改动的测试：互斥锁的「空闲才做」、hook 循环的两处判定、
 * hook 本地缓存的撤销清理。
 */
class SecondPassHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetHookDirCache() {
        HookLibSync::class.java.getDeclaredField("iconDir").apply { isAccessible = true }.set(HookLibSync, null)
    }

    @After
    fun gateMustBeFree() {
        assertFalse(SyncGate.isHeld)
    }

    // ━━ SyncGate.ifIdle ━━

    @Test
    fun `ifIdle runs the idle branch only while it holds the gate and always releases it`() {
        var heldInside = false
        var ran = false

        val result = SyncGate.ifIdle(
            whenIdle = { heldInside = SyncGate.isHeld; ran = true; "idle" },
            whenBusy = { "busy" }
        )

        assertEquals("idle", result)
        assertTrue("the idle branch must run under the gate (no gap between check and cleanup)", heldInside)
        assertTrue(ran)
        assertFalse(SyncGate.isHeld)
    }

    @Test
    fun `ifIdle runs the busy branch while a sync holds the gate and never takes it`() {
        assertTrue(SyncGate.tryAcquire())
        var idleRan = false
        try {
            val result = SyncGate.ifIdle(whenIdle = { idleRan = true; "idle" }, whenBusy = { "busy" })
            assertEquals("busy", result)
            assertFalse(idleRan)
        } finally {
            SyncGate.release()
        }
    }

    @Test
    fun `ifIdle releases the gate even when the idle branch throws`() {
        try {
            SyncGate.ifIdle<Unit>(whenIdle = { throw IllegalStateException("boom") }, whenBusy = {})
            throw AssertionError("expected the exception to propagate")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(SyncGate.isHeld)
    }

    // ━━ hook 循环的判定 ━━

    @Test
    fun `polling stays dense until the shared-dir observer exists and then slows down`() {
        assertEquals(HookLibSync.POLL_INTERVAL_MS, HookLibSync.pollIntervalMs(sharedDirObserved = false))
        assertEquals(HookLibSync.IDLE_POLL_INTERVAL_MS, HookLibSync.pollIntervalMs(sharedDirObserved = true))
        assertTrue("the settled cadence must be slower than the dense one", HookLibSync.IDLE_POLL_INTERVAL_MS > HookLibSync.POLL_INTERVAL_MS)
    }

    @Test
    fun `polling that finds the switch just turned on counts as an event`() {
        // 观察者没建立时，关了再开只能靠轮询发现——仍要按事件处理，否则要等满一个周期
        assertTrue(HookLibSync.countsAsEvent(wokenByEvent = false, enabled = true, wasEnabled = false))
        assertTrue(HookLibSync.countsAsEvent(wokenByEvent = true, enabled = true, wasEnabled = true))
        assertFalse("nothing changed and no event: an ordinary poll", HookLibSync.countsAsEvent(wokenByEvent = false, enabled = true, wasEnabled = true))
        assertFalse(HookLibSync.countsAsEvent(wokenByEvent = false, enabled = false, wasEnabled = true))
    }

    // ━━ hook 本地缓存的撤销清理 ━━

    @Test
    fun `dropping revoked packages deletes only their files and only for safe names`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val dir = File(ctx.cacheDir, "opticon_hook_icons")
        dir.mkdirs()
        listOf("com.a", "com.b", "com.keep").forEach { File(dir, "$it.png").writeText("png-$it") }
        val outside = File(ctx.cacheDir, "outside.png").apply { writeText("must survive") }

        HookLibSync.dropLocalPackages(ctx, listOf("com.a", "../outside", "com.b", "", "com/keep", "com.a"))

        assertFalse(File(dir, "com.a.png").exists())
        assertFalse(File(dir, "com.b.png").exists())
        assertTrue("a package not in the list keeps its icon", File(dir, "com.keep.png").exists())
        assertEquals("must survive", outside.readText())
    }

    // ━━ 应用图标库读路径 ━━

    @Test
    fun `icon library read path keeps a staging dir while a sync holds the gate and clears it once idle`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val picp = File(ctx.filesDir, "picp")
        val staging = FileSnapshotSwitch.stagingOf(picp, "icons")
        staging.mkdirs()
        File(staging, "half.png").writeText("being built")

        assertTrue(SyncGate.tryAcquire()) // 模拟「有同步正在跑」
        try {
            assertEquals(0, PicpEngine.getIndexedCount(ctx)) // 库是空的 → 每次都会重新读盘
            assertTrue("must not delete a staging dir a sync may be building", File(staging, "half.png").exists())
        } finally {
            SyncGate.release()
        }

        assertEquals(0, PicpEngine.getIndexedCount(ctx))
        assertFalse("once idle the read path cleans the staging dir", staging.exists())
    }

    // ━━ 加载路径的判断 ━━

    @Test
    fun `loadPlan ingests legacy rules only for a legacy source`() {
        val anip = io.github.deserthouse.opticon.domain.SourceFormat.ANIP_BUNDLE
        val legacy = io.github.deserthouse.opticon.domain.SourceFormat.LEGACY_JSON
        assertEquals(RuleSyncFollowUp.Load.READ_SNAPSHOT, RuleSyncFollowUp.loadPlan(anip, legacyRulesPresent = true, snapshotMetaPresent = true))
        assertEquals(RuleSyncFollowUp.Load.NOTHING, RuleSyncFollowUp.loadPlan(anip, legacyRulesPresent = true, snapshotMetaPresent = false))
        assertEquals(RuleSyncFollowUp.Load.INGEST_LEGACY_RULES, RuleSyncFollowUp.loadPlan(legacy, legacyRulesPresent = true, snapshotMetaPresent = true))
        assertEquals(RuleSyncFollowUp.Load.READ_SNAPSHOT, RuleSyncFollowUp.loadPlan(legacy, legacyRulesPresent = false, snapshotMetaPresent = true))
        assertEquals(RuleSyncFollowUp.Load.NOTHING, RuleSyncFollowUp.loadPlan(legacy, legacyRulesPresent = false, snapshotMetaPresent = false))
    }
}
