package io.github.deserthouse.opticon.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WakeSignal 单测（修复批 X-13：循环的等待可以被事件打断）。 */
class WakeSignalTest {

    @Test
    fun `a wake interrupts a long wait promptly`() {
        // 旧实现的 Thread.sleep(interval) 打不断：开关关了再开，最长要等一个周期
        val signal = WakeSignal()
        val result = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            result.set(signal.await(timeoutMs = 120_000)) // 等两分钟
            done.countDown()
        }.apply { isDaemon = true }.start()

        Thread.sleep(150) // 让等待者真的进入等待
        val started = System.nanoTime()
        signal.wake()

        assertTrue("waiter was not woken within 2 seconds", done.await(2, TimeUnit.SECONDS))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("woke after ${elapsedMs}ms — not prompt", elapsedMs < 1_500)
        assertTrue(result.get())
    }

    @Test
    fun `no wake means the wait runs to its timeout`() {
        val signal = WakeSignal()
        val started = System.nanoTime()

        val woken = signal.await(timeoutMs = 80)

        assertFalse(woken)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 70)
    }

    @Test
    fun `a wake that arrived before waiting is consumed by the next await`() {
        val signal = WakeSignal()
        signal.wake()

        val started = System.nanoTime()
        assertTrue(signal.await(timeoutMs = 60_000))
        assertTrue("should return immediately", (System.nanoTime() - started) / 1_000_000 < 500)
    }

    @Test
    fun `several wakes coalesce into one`() {
        val signal = WakeSignal()
        repeat(5) { signal.wake() }

        assertTrue(signal.await(timeoutMs = 60_000))
        assertFalse("the extra wakes must not leak into later waits", signal.await(timeoutMs = 40))
    }
}
