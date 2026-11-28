package io.github.deserthouse.opticon.hook

import io.github.deserthouse.opticon.engine.AnipSync

/**
 * 后台自同步的「一轮」里碰到的外部世界（批 MT M2）。
 * 生产实现读文件、起同步、作废位图缓存；单测换成记录调用的假实现——这一层外围的规矩
 * （总开关闸、补建观察者、开关变化算事件、没变化不作废缓存）此前只存在于线程与 Android 调用之间，
 * JVM 测试进不去，改坏了也没有测试变红。
 */
internal interface RoundEnv {
    /** 共享目录可能到这时才变得可读（带锁屏密码的设备首次解锁之后）：观察者没建立就在这里补上。 */
    fun ensureSharedDirObserver()

    /** 「规则库自动更新」开关此刻的状态。 */
    fun autoUpdateEnabled(): Boolean

    /** 模块总开关此刻的状态。 */
    fun masterEnabled(): Boolean

    /** 跑一次同步。null = 已有一次在跑。 */
    fun sync(): AnipSync.SyncOutcome?

    /** hook 本地缓存换了新内容：位图缓存里的可能是旧图，整体作废。 */
    fun onIconsChanged()

    /** 单调时钟（毫秒）。 */
    fun now(): Long
}

/**
 * 后台自同步的一轮（批 MT M2：从 [HookLibSync] 的线程与 Android 调用里抽出来）。
 *
 * 一轮 = 补建共享目录观察者 → 读开关 → 判定这一轮该不该同步 → 过模块总开关 → 同步 → 记账。
 * 周期与最小间隔的判定在 [SyncLoopController]；这一层只管它外围的几条规矩：
 *  - 开关自己从关变成开（没收到事件）也算事件——观察者没建立起来的设备上，关了再开否则要等满一个周期；
 *  - 模块总开关关着就不同步，也不记为「同步过」：总开关一开，下一轮就同步；
 *  - 上游没有新版本时同步算成功，但图标一个都没变，不作废位图缓存。
 *
 * 同一个实例要一直用下去（它记得上一轮看到的开关状态）。日志原话是设备验证套件认状态的依据，不要改。
 */
internal class SyncRound(
    private val env: RoundEnv,
    initiallyEnabled: Boolean,
    private val log: (String) -> Unit,
    private val bootDelayMs: Long = 30_000L,
) {
    /** 上一轮看到的开关状态。巡检自己发现「关 → 开」时按事件处理。 */
    var lastKnownEnabled: Boolean = initiallyEnabled
        private set

    /** 跑一轮。[woken] = 被事件叫醒。返回这一轮是否真的同步过（不论成败）。 */
    fun run(controller: SyncLoopController, bootAt: Long, woken: Boolean): Boolean {
        // 共享目录可能到这时才变得可读：hook 初始化时没建起来的观察者在这里补上
        env.ensureSharedDirObserver()
        val enabled = env.autoUpdateEnabled()
        val byEvent = HookLibSync.countsAsEvent(woken, enabled, lastKnownEnabled)
        if (enabled != lastKnownEnabled) {
            log("auto lib update is now ${if (enabled) "on" else "off"}")
            lastKnownEnabled = enabled
        }
        val now = env.now()
        val due = controller.shouldSync(
            enabled = enabled,
            bootDelayElapsed = now - bootAt >= bootDelayMs,
            byEvent = byEvent,
            nowMs = now
        )
        // 总开关关着：这一轮跳过。不记为「同步过」——总开关一开，下一轮巡检就同步。
        if (!due || !env.masterEnabled()) return false
        val outcome = env.sync()
        val ok = outcome?.ok == true
        // 快照没动（上游没有新版本）就不作废图标缓存——图标一个都没变
        if (outcome != null && outcome.ok && !outcome.unchanged) env.onIconsChanged()
        controller.onSyncFinished(ok, env.now())
        log(
            "self-sync " + when {
                outcome == null -> "skipped (busy)"
                !outcome.ok -> "failed (${outcome.failure})"
                outcome.unchanged -> "ok (upstream unchanged, release ${outcome.tag})"
                else -> "ok"
            } + ", next in ${controller.currentIntervalMs / 3600000}h" +
                if (byEvent) " (event-triggered)" else ""
        )
        return true
    }
}
