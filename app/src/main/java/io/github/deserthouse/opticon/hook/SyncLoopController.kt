package io.github.deserthouse.opticon.hook

/**
 * SyncLoopController — hook 侧自同步循环的节奏判定（修复批 X-05 / X-13，纯 Kotlin）。
 *
 * 循环每次被叫醒（例行巡检或事件）都问它一句「这一轮要不要同步」：
 *  - 开关关着 → 永不（关着的时候循环只是定时读一次本地文件，从不联网）；
 *  - 开机后的宽限期内 → 永不（这段时间里事件也不触发——网络未必就绪，一次失败的
 *    同步会按退避把下一次推迟到一天以后）；
 *  - 从未同步过 → 要；
 *  - 例行巡检：距上次同步满一个周期（失败后按退避加长）→ 要；
 *  - 事件触发（开关刚打开 / 规则库刚更新）：距上次同步满最小间隔 → 要。
 *    最小间隔挡住反复切换开关触发的频繁同步。
 */
internal class SyncLoopController(
    baseIntervalMs: Long,
    private val minGapMs: Long,
    /** 退避：给当前周期与本次成败，返回下一个周期。 */
    private val backoff: (currentMs: Long, success: Boolean) -> Long
) {
    private var intervalMs = baseIntervalMs
    private var lastSyncAtMs = NEVER

    /** 当前周期（上次同步之后生效的那个）。 */
    val currentIntervalMs: Long get() = intervalMs

    val hasSynced: Boolean get() = lastSyncAtMs != NEVER

    fun shouldSync(enabled: Boolean, bootDelayElapsed: Boolean, byEvent: Boolean, nowMs: Long): Boolean {
        if (!enabled || !bootDelayElapsed) return false
        if (lastSyncAtMs == NEVER) return true
        val gap = if (byEvent) minGapMs else intervalMs
        return nowMs - lastSyncAtMs >= gap
    }

    fun onSyncFinished(success: Boolean, nowMs: Long) {
        lastSyncAtMs = nowMs
        intervalMs = backoff(intervalMs, success)
    }

    private companion object {
        private const val NEVER = Long.MIN_VALUE
    }
}
