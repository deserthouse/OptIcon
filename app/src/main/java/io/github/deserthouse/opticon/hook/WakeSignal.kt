package io.github.deserthouse.opticon.hook

/**
 * WakeSignal — 可被唤醒的等待（修复批 X-13，纯 Kotlin）。
 *
 * 修复批之前 hook 侧的循环用的是 `Thread.sleep(interval)`：开关关了再开，或者开关
 * 在循环启动之后才打开，最坏要睡满一个周期（12 小时，失败退避后 48 小时）才会
 * 回头看一眼。现在等待可以被事件打断——开关变化、规则库更新都来叫醒它，循环醒来
 * 后重新读状态再决定这一轮做不做。
 *
 * 多次唤醒合并成一次：循环只关心「有事件」，不关心有几次。
 */
internal class WakeSignal {

    private val lock = Object()
    private var pending = false

    /** 唤醒等待者。可从任意线程调用。 */
    fun wake() = synchronized(lock) {
        pending = true
        (lock as Object).notifyAll()
    }

    /** @return true = 被唤醒；false = 等满 [timeoutMs] 没人来叫。 */
    fun await(timeoutMs: Long): Boolean = synchronized(lock) {
        if (pending) {
            pending = false
            return true
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var remaining = timeoutMs
        while (remaining > 0) {
            try {
                (lock as Object).wait(remaining)
            } catch (_: InterruptedException) {
                break
            }
            if (pending) break
            remaining = (deadline - System.nanoTime()) / 1_000_000
        }
        val woken = pending
        pending = false
        return woken
    }
}
