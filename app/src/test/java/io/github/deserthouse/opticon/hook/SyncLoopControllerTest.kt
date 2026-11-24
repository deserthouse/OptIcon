package io.github.deserthouse.opticon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncLoopController 单测（修复批 X-05 / X-13）：自动更新循环「这一轮要不要同步」的判定。
 * 退避用 HookLibSync.nextSyncInterval（12h 基线，失败翻倍，上限 48h）。
 */
class SyncLoopControllerTest {

    private val hour = 3_600_000L
    private val base = 12 * hour
    private val minGap = 5 * 60_000L

    private fun controller() = SyncLoopController(
        baseIntervalMs = base,
        minGapMs = minGap,
        backoff = { current, success -> HookLibSync.nextSyncInterval(current, success) }
    )

    // ━━ 开关关着 ━━

    @Test
    fun `switch off never syncs - whatever else is true`() {
        val c = controller()
        assertFalse(c.shouldSync(enabled = false, bootDelayElapsed = true, byEvent = false, nowMs = 100 * hour))
        assertFalse(c.shouldSync(enabled = false, bootDelayElapsed = true, byEvent = true, nowMs = 100 * hour))
        assertFalse(c.hasSynced)
    }

    // ━━ 开机宽限期 ━━

    @Test
    fun `switch on during the boot grace period does not sync - even on an event`() {
        val c = controller()
        // 这段时间里网络未必就绪；一次失败的同步会按退避把下一次推迟到一天以后
        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = false, byEvent = false, nowMs = 0))
        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = false, byEvent = true, nowMs = 0))
    }

    @Test
    fun `first poll after the grace period syncs`() {
        val c = controller()
        assertTrue(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = 60_000))
    }

    // ━━ 周期 ━━

    @Test
    fun `after a success the next scheduled sync waits a full interval`() {
        val c = controller()
        c.onSyncFinished(success = true, nowMs = 0)

        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = base - 1))
        assertTrue(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = base))
    }

    // ━━ 事件触发与最小间隔（X-13） ━━

    @Test
    fun `an event triggers a sync once the minimum gap has passed - before the interval is over`() {
        // 关了再开：旧实现要睡满一个周期（最长 48 小时）才会回头看一眼
        val c = controller()
        c.onSyncFinished(success = true, nowMs = 0)

        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = true, nowMs = minGap - 1))
        assertTrue(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = true, nowMs = minGap))
        // 没到最小间隔时，例行巡检仍然按周期判断，不会被事件带偏
        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = minGap))
    }

    @Test
    fun `repeated events inside the minimum gap do not become frequent syncs`() {
        val c = controller()
        c.onSyncFinished(success = true, nowMs = 0)

        for (t in listOf(1L, minGap / 4, minGap / 2, minGap - 1)) {
            assertFalse("event at $t must not sync", c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = true, nowMs = t))
        }
    }

    @Test
    fun `an event after the grace period syncs even if nothing was ever synced`() {
        val c = controller()
        assertTrue(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = true, nowMs = 60_000))
    }

    // ━━ 退避 ━━

    @Test
    fun `failures lengthen the interval and a success resets it`() {
        val c = controller()
        assertEquals(12L, HookLibSync.testBaseIntervalMs() / hour)
        assertEquals(48L, HookLibSync.testMaxIntervalMs() / hour)

        c.onSyncFinished(success = false, nowMs = 0)
        assertEquals(24 * hour, c.currentIntervalMs)
        assertFalse(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = 12 * hour))
        assertTrue(c.shouldSync(enabled = true, bootDelayElapsed = true, byEvent = false, nowMs = 24 * hour))

        c.onSyncFinished(success = false, nowMs = 24 * hour)
        assertEquals(48 * hour, c.currentIntervalMs)
        c.onSyncFinished(success = false, nowMs = 72 * hour)
        assertEquals("capped at the maximum", 48 * hour, c.currentIntervalMs)

        c.onSyncFinished(success = true, nowMs = 120 * hour)
        assertEquals(12 * hour, c.currentIntervalMs)
    }
}
