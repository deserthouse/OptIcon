package io.github.deserthouse.opticon.hook

import io.github.deserthouse.opticon.engine.AnipSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台自同步「一轮」的外围规矩（批 MT M2）。此前这段接线只在 hook 进程的线程里，
 * 审查时把它改坏（不看总开关、开关变化不算事件、没变化也作废缓存）没有任何测试变红。
 */
class SyncRoundTest {

    /** 记录调用的假环境：开关状态、总开关、时钟、这一轮同步的结果都由测试摆布。 */
    private class FakeEnv(var enabled: Boolean = true, var master: Boolean = true) : RoundEnv {
        val calls = ArrayList<String>()
        var clock = 100_000L
        var next: AnipSync.SyncOutcome? = AnipSync.SyncOutcome(3)

        override fun ensureSharedDirObserver() {
            calls += "observer"
        }

        override fun autoUpdateEnabled(): Boolean = enabled

        override fun masterEnabled(): Boolean {
            calls += "master"
            return master
        }

        override fun sync(): AnipSync.SyncOutcome? {
            calls += "sync"
            return next
        }

        override fun onIconsChanged() {
            calls += "invalidate"
        }

        override fun now(): Long = clock
    }

    private val logs = ArrayList<String>()

    private fun controller(backoff: (Long, Boolean) -> Long = { current, _ -> current }) =
        SyncLoopController(baseIntervalMs = 12 * 3600_000L, minGapMs = 5 * 60_000L, backoff = backoff)

    private fun round(env: FakeEnv, initiallyEnabled: Boolean = env.enabled) =
        SyncRound(env, initiallyEnabled, logs::add, bootDelayMs = 30_000L)

    @Test
    fun `the master switch off means no sync at all and nothing is recorded as synced`() {
        val env = FakeEnv(master = false)
        val round = round(env)
        val c = controller()

        assertFalse(round.run(c, bootAt = 0, woken = true))

        assertEquals(listOf("observer", "master"), env.calls)
        assertFalse("被总开关拦下的一轮不记账", c.hasSynced)

        // 总开关一开，下一轮（哪怕只是例行巡检）就同步
        env.master = true
        assertTrue(round.run(c, bootAt = 0, woken = false))
        assertEquals(listOf("observer", "master", "observer", "master", "sync", "invalidate"), env.calls)
    }

    @Test
    fun `the master switch is only consulted when the round was going to sync anyway`() {
        val env = FakeEnv(enabled = false, master = false)
        assertFalse(round(env).run(controller(), bootAt = 0, woken = true))
        assertEquals("开关关着的一轮不同步，也就不必去读总开关", listOf("observer"), env.calls)
    }

    @Test
    fun `a changed snapshot invalidates the icon cache and an unchanged one does not`() {
        val env = FakeEnv().apply { next = AnipSync.SyncOutcome(924, unchanged = true, tag = "abc1234") }
        val round = round(env)
        val c = controller()

        assertTrue(round.run(c, bootAt = 0, woken = false))

        assertEquals(listOf("observer", "master", "sync"), env.calls)
        assertEquals("self-sync ok (upstream unchanged, release abc1234), next in 12h", logs.single())
    }

    @Test
    fun `a real update invalidates the icon cache`() {
        val env = FakeEnv().apply { next = AnipSync.SyncOutcome(925, tag = "abc1234") }
        val round = round(env)
        val c = controller()

        round.run(c, bootAt = 0, woken = false)

        assertEquals(listOf("observer", "master", "sync", "invalidate"), env.calls)
        assertEquals("self-sync ok, next in 12h", logs.single())
    }

    @Test
    fun `the shared dir observer is re-established on every round, synced or not`() {
        val env = FakeEnv(enabled = false)

        assertFalse(round(env).run(controller(), bootAt = 0, woken = false))
        assertFalse(round(env).run(controller(), bootAt = 0, woken = true))

        assertEquals(listOf("observer", "observer"), env.calls)
    }

    @Test
    fun `nothing syncs during the boot grace period`() {
        val env = FakeEnv().apply { clock = 29_999L }
        val round = round(env)
        val c = controller()

        assertFalse(round.run(c, bootAt = 0, woken = true))
        env.clock = 30_000L
        assertTrue(round.run(c, bootAt = 0, woken = true))
        assertEquals(1, env.calls.count { it == "sync" })
    }

    @Test
    fun `a flag change noticed by the poll counts as an event`() {
        // 观察者没建立起来的设备上，开关文件的变化没有事件，只能靠巡检发现「关 → 开」
        val env = FakeEnv(enabled = false)
        val round = round(env, initiallyEnabled = false)
        val c = controller()

        assertFalse(round.run(c, bootAt = 0, woken = false))
        assertEquals("开关没变，不记日志", 0, logs.size)

        env.enabled = true
        assertTrue(round.run(c, bootAt = 0, woken = false))

        assertEquals(listOf("auto lib update is now on", "self-sync ok, next in 12h (event-triggered)"), logs)
    }

    @Test
    fun `a plain poll inside the minimum gap does not sync, an event after it does`() {
        val env = FakeEnv()
        val round = round(env)
        val c = controller()
        assertTrue(round.run(c, bootAt = 0, woken = true))

        env.clock += 60_000L
        assertFalse("距上次同步一分钟：例行巡检不同步", round.run(c, bootAt = 0, woken = false))

        // 关了再开（两次都只有巡检看到）：算事件，但最小间隔还没到
        env.enabled = false
        assertFalse(round.run(c, bootAt = 0, woken = false))
        env.enabled = true
        assertFalse("事件也要等满最小间隔", round.run(c, bootAt = 0, woken = false))

        // 开关的变化在上一轮已经被看到：之后又是普通巡检，仍然要等满整个周期
        env.clock += 5 * 60_000L
        assertFalse("变化已被消费，之后只是普通巡检", round.run(c, bootAt = 0, woken = false))

        env.clock += 5 * 60_000L
        assertTrue("过了最小间隔，事件触发同步", round.run(c, bootAt = 0, woken = true))
        assertEquals(2, env.calls.count { it == "sync" })
    }

    @Test
    fun `a busy sync is logged as skipped and still counts as an attempt`() {
        val env = FakeEnv().apply { next = null }
        val c = controller()

        assertTrue(round(env).run(c, bootAt = 0, woken = false))

        assertTrue(c.hasSynced)
        assertEquals("self-sync skipped (busy), next in 12h", logs.single())
        assertFalse("没同步成，不作废缓存", "invalidate" in env.calls)
    }

    @Test
    fun `a failed sync is logged with its reason and the backoff shows in the log`() {
        val env = FakeEnv().apply { next = AnipSync.SyncOutcome(0, failure = AnipSync.Failure.NETWORK) }
        val c = controller(backoff = { current, _ -> current * 2 })

        assertTrue(round(env).run(c, bootAt = 0, woken = false))

        assertEquals("self-sync failed (NETWORK), next in 24h", logs.single())
    }
}
