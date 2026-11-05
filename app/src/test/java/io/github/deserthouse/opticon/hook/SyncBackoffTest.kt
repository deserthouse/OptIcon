package io.github.deserthouse.opticon.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HookLibSync 退避序列单测（批 SYNC V5）。
 * nextSyncInterval：成功重置 12h 基线；连续失败翻倍封顶 48h。
 */
class SyncBackoffTest {

    private val base = HookLibSync.testBaseIntervalMs()

    @Test
    fun `success resets interval to base 12h`() {
        assertEquals(base, HookLibSync.nextSyncInterval(base, success = true))
        // 失败抬升后一次成功即回基线
        assertEquals(base, HookLibSync.nextSyncInterval(base * 2, success = true))
        assertEquals(base, HookLibSync.nextSyncInterval(HookLibSync.testMaxIntervalMs(), success = true))
    }

    @Test
    fun `consecutive failures double the wait capped at 48h`() {
        var interval = HookLibSync.nextSyncInterval(base, success = false)
        assertEquals(base * 2, interval) // 24h
        interval = HookLibSync.nextSyncInterval(interval, success = false)
        assertEquals(base * 4, interval) // 48h
        // 封顶：不超 48h
        interval = HookLibSync.nextSyncInterval(interval, success = false)
        assertEquals(HookLibSync.testMaxIntervalMs(), interval)
        interval = HookLibSync.nextSyncInterval(interval, success = false)
        assertEquals(HookLibSync.testMaxIntervalMs(), interval)
    }
}
