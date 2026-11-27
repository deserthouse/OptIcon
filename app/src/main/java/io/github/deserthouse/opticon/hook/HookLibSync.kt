package io.github.deserthouse.opticon.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.engine.AnipSync
import io.github.deserthouse.opticon.engine.RuleLibrary
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HookLibSync — ANIP-style in-process icon rules sync (Plan B).
 *
 * Mirrors fankes/ColorOSNotifyIcon's ANIP SDK architecture: the SystemUI
 * hook process downloads the ANIA/Fankes rules JSON ITSELF and decodes the
 * icons into its OWN cache dir (inside the SystemUI sandbox). Zero
 * cross-process file delivery, zero SELinux exposure — the hook process is
 * fully self-sufficient for strategy-1 (cloud rules) icons.
 *
 * Rules come from AnipSync (ANIP manifests + monochrome PNG resources),
 * producing the same cache layout the App builds via IconLibEngine.
 *
 * 后台自同步是可选项（批 SYNC V5，默认关）。修复批（X-05 / X-13）之后它由一条
 * **常驻的轻量巡检线程**驱动，而不是「启动时开关开着才建线程」：
 *
 *   - 开关关着：线程只是在巡检间隔上醒来读一次本地开关文件，从不联网；
 *   - 开关打开：宽限期过后同步，之后按周期（失败退避）同步；
 *   - 开关变化、规则库更新会立刻叫醒它（[WakeSignal]），醒来后重新读状态再决定；
 *   - 巡检本身是兜底：带锁屏密码的设备首次解锁前共享目录读不到——开关文件读成关、
 *     观察者也建立不起来——只能靠轮询在解锁之后发现真实状态。
 *
 * 是否同步的判定在 [SyncLoopController]（纯 Kotlin、有测试）。
 *
 * Icons land in cacheDir/opticon_hook_icons/{pkg}.png — cacheDir of the
 * SystemUI process, readable without any cross-domain access.
 */
object HookLibSync {

    private const val TAG = "OptIcon/HookLibSync"
    private const val DIR_NAME = "opticon_hook_icons"

    /** Full self-sync every 12h while SystemUI is alive. */
    private const val SYNC_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** Post-boot grace period before first sync (network settle). */
    private const val BOOT_DELAY_MS = 30_000L

    /** Batch SYNC V5: consecutive-failure backoff ceiling. */
    private const val MAX_INTERVAL_MS = 48L * 60 * 60 * 1000

    /** 巡检间隔（共享目录上的观察者还没建立时）：开关状态的变化、共享目录是否已可读，
     *  最终都靠它兜底（事件只是让它更及时）。一次巡检 = 读一两个本地文件。 */
    internal const val POLL_INTERVAL_MS = 60_000L

    /** 巡检间隔（观察者已建立后）：开关文件的变化会经共享目录的事件立刻叫醒循环，
     *  轮询只剩兜底作用。放慢到 15 分钟，少唤醒系统界面进程。 */
    internal const val IDLE_POLL_INTERVAL_MS = 15L * 60 * 1000

    /** 事件触发的两次同步之间的最小间隔：反复切换开关不会变成频繁联网。 */
    internal const val MIN_SYNC_GAP_MS = 5L * 60 * 1000

    private val syncing = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val wakeSignal = WakeSignal()

    /** 上一轮看到的开关状态。巡检自己发现「关 → 开」时按事件处理。 */
    @Volatile
    private var lastKnownEnabled: Boolean = false

    @Volatile
    private var iconDir: File? = null

    @Volatile
    private var appContext: Context? = null

    /** Shared flag dir — same production channel as master_switch (the hook
     *  process cannot read the module's prefs). */
    private val sharedFlagDir: File by lazy {
        File(
            android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "OptIcon"
        )
    }

    /**
     * Batch SYNC V5: background self-sync is opt-in. Default OFF — the
     * published promise is "network only on manual sync" (README FAQ); the
     * App writes auto_lib_update.opticon ("true"/"false") when the user
     * enables it, and absent also means off.
     */
    internal fun autoUpdateEnabled(): Boolean {
        return try {
            val f = File(sharedFlagDir, "auto_lib_update.opticon")
            f.exists() && f.readText().trim() == "true"
        } catch (_: Exception) { false }
    }

    /** Master gate per round — hook-local reads of the shared master flag.
     *  File read (not the hot rate-limited path) is fine at 12h cadence. */
    internal fun masterEnabled(): Boolean {
        return try {
            val f = File(sharedFlagDir, "master_switch.opticon")
            !f.exists() || f.readText().trim() != "false"
        } catch (_: Exception) { true }
    }

    /** Backoff series (batch SYNC V5): success resets to the base 12h cycle;
     *  each consecutive failure doubles the wait, capped at 48h. JVM-testable. */
    internal fun nextSyncInterval(currentMs: Long, success: Boolean): Long {
        if (success) return SYNC_INTERVAL_MS
        return (currentMs * 2).coerceAtMost(MAX_INTERVAL_MS)
    }

    /** Test-visible constant accessors (unit tests live in the same module). */
    internal fun testBaseIntervalMs() = SYNC_INTERVAL_MS
    internal fun testMaxIntervalMs() = MAX_INTERVAL_MS

    /** 这一轮等多久：观察者已建立就放慢，否则保持密集巡检（JVM-testable）。 */
    internal fun pollIntervalMs(sharedDirObserved: Boolean): Long =
        if (sharedDirObserved) IDLE_POLL_INTERVAL_MS else POLL_INTERVAL_MS

    /**
     * 这一轮算不算「事件触发」：被事件叫醒，或者巡检自己发现开关刚从关变成开。
     * 后者也按事件处理——观察者没建立起来的设备上（带锁屏密码的设备解锁后的第一分钟、
     * 或观察者创建失败），关了再开否则仍要等满一个周期（JVM-testable）。
     */
    internal fun countsAsEvent(wokenByEvent: Boolean, enabled: Boolean, wasEnabled: Boolean): Boolean =
        wokenByEvent || (enabled && !wasEnabled)

    /** Directory holding hook-process-local rule icons.
     *  The SystemContext ("android" package) has no accessible cacheDir —
     *  fall back to the real SystemUI Application context when needed. */
    fun dir(context: Context): File {
        iconDir?.let { return it }
        val d = try {
            File(context.cacheDir, DIR_NAME)
        } catch (_: Exception) {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: throw IllegalStateException("no SystemUI app context for hook cache")
            File(app.cacheDir, DIR_NAME)
        }
        // 读取路径恢复（修复批 X-07）：先收敛上次切换留下的中间态，再确保目录存在。
        // 顺序不能反——「旧版在备份里、live 缺失」的中间态下直接建目录，会在完好的
        // prev 旁边造出一个空的 live，下一次收敛就把 prev 当成旧备份删掉。
        // 收敛失败时不建目录、也不缓存：这一次读不到东西，但完好的旧版还在备份里，
        // 下一次调用再试。
        if (!RuleLibrary.ensureDir(d)) return d
        iconDir = d
        return d
    }

    fun bitmapFor(context: Context, pkg: String): Bitmap? {
        val f = File(dir(context), "$pkg.png")
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath) ?: run { f.delete(); null }
        } catch (_: Exception) { null }
    }

    /**
     * 从 hook 本地缓存里删掉这些包（应用侧的规则库同步判定它们已被上游撤销，
     * 清单经共享目录传过来）。本地缓存是独立的一份快照，不随应用侧的手动同步更新；
     * 不删的话，共享目录里的镜像已经没了，hook 退到本地缓存还是会把被撤销的图标显示出来。
     * 只动本地文件，不联网。
     */
    fun dropLocalPackages(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val dir = dir(context)
        var dropped = 0
        for (pkg in packages) {
            // 文件名由包名拼成：只认安全的包名，其余一律忽略
            if (!pkg.matches(SAFE_PKG) || pkg.length > 255) continue
            val f = File(dir, "$pkg.png")
            if (f.isFile && f.delete()) dropped++
        }
        TraceLogger.i(TAG, "dropped $dropped revoked packages from hook-local cache")
    }

    private val SAFE_PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

    /**
     * 启动常驻巡检线程。无论开关当前是开是关都启动——修复批之前开关关着就直接
     * 返回、不建线程，之后用户打开开关也不会生效，除非重启系统界面进程（X-05）。
     * 重复调用无副作用。
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        lastKnownEnabled = autoUpdateEnabled()
        TraceLogger.i(TAG, "self-sync loop starting (auto update now ${if (lastKnownEnabled) "on" else "off"})")
        Thread({ loop(context) }, "OptIconHookLibSync").apply { isDaemon = true }.start()
    }

    private fun loop(context: Context) {
        val controller = SyncLoopController(
            baseIntervalMs = SYNC_INTERVAL_MS,
            minGapMs = MIN_SYNC_GAP_MS,
            backoff = ::nextSyncInterval
        )
        val bootAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            // 这是一条常驻线程：任何一轮里的意外都不能让它死掉（死了自动更新就从此失效，
            // 直到系统界面重启）。每一轮整体兜底。
            val waitMs = try {
                pollIntervalMs(NotificationHook.sharedDirObserved())
            } catch (t: Throwable) {
                POLL_INTERVAL_MS
            }
            val woken = try {
                wakeSignal.await(waitMs)
            } catch (t: Throwable) {
                TraceLogger.w(TAG, "sync loop wait failed: $t")
                true
            }
            try {
                runRound(context, controller, bootAt, woken)
            } catch (t: Throwable) {
                TraceLogger.w(TAG, "sync loop round failed: $t")
            }
        }
    }

    private fun runRound(context: Context, controller: SyncLoopController, bootAt: Long, woken: Boolean) {
        // 共享目录可能到这时才变得可读（带锁屏密码的设备首次解锁之后）：
        // hook 初始化时没建起来的观察者在这里补上
        NotificationHook.ensureSharedDirObserver()
        val enabled = autoUpdateEnabled()
        val byEvent = countsAsEvent(woken, enabled, lastKnownEnabled)
        if (enabled != lastKnownEnabled) {
            TraceLogger.i(TAG, "auto lib update is now ${if (enabled) "on" else "off"}")
            lastKnownEnabled = enabled
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val due = controller.shouldSync(
            enabled = enabled,
            bootDelayElapsed = now - bootAt >= BOOT_DELAY_MS,
            byEvent = byEvent,
            nowMs = now
        )
        // 总开关关着：这一轮跳过。不记为「同步过」——总开关一开，下一轮巡检就同步。
        if (!due || !masterEnabled()) return
        val outcome = syncNow(context)
        val ok = outcome?.ok == true
        // 快照没动（上游没有新版本）就不作废图标缓存——图标一个都没变
        if (outcome != null && outcome.ok && !outcome.unchanged) NotificationHook.onHookLibSynced()
        controller.onSyncFinished(ok, android.os.SystemClock.elapsedRealtime())
        TraceLogger.i(
            TAG,
            "self-sync " + when {
                outcome == null -> "skipped (busy)"
                !outcome.ok -> "failed (${outcome.failure})"
                outcome.unchanged -> "ok (upstream unchanged, release ${outcome.tag})"
                else -> "ok"
            } + ", next in ${controller.currentIntervalMs / 3600000}h" +
                if (byEvent) " (event-triggered)" else ""
        )
    }

    /**
     * 开关文件变了（共享目录的观察者事件）。只叫醒循环，不在这里决定做不做——
     * 循环醒来后读的是当下真实的状态。
     */
    fun onAutoUpdateFlagChanged() {
        wakeSignal.wake()
    }

    /**
     * 应用侧规则库同步收尾（共享目录里的规则版本戳变化，修复批 X-04）。
     * 叫醒循环；自动更新开着且过了最小间隔，它就会同步一次，让 hook 侧的本地
     * 规则缓存与共享目录一致。关着的时候循环醒来只读一次文件，不做任何联网——
     * 被撤销的包由 [dropLocalPackages] 经共享目录传来的清单直接删掉，不依赖联网。
     */
    fun onRulesChanged() {
        wakeSignal.wake()
    }

    /**
     * 共享目录被移走、观察者已放掉（NotificationHook）。要叫醒循环：不叫的话它按「观察者在」
     * 时的节奏（15 分钟）睡到下一次巡检，目录回来之后的这段时间里既没有事件也没有轮询。
     * 醒来后目录还没回来就按 60 秒的节奏轮询，回来即重建观察者。只动本地文件，不联网。
     */
    fun onSharedDirGone() {
        wakeSignal.wake()
    }

    /** 仅限开发 / Root 环境的唤醒通道（模块私有目录 fankes_cache 上的观察者，
     *  见 NotificationHook 里的说明，X-25）。同样是叫醒循环。 */
    fun requestSync() {
        wakeSignal.wake()
    }

    /**
     * One-shot sync: 把规则库同步进 hook 自己的缓存目录。同一时刻只跑一个。
     *
     * @return null = 已有一次在跑；否则是这次同步的结果（[AnipSync.SyncOutcome.ok] 为 false 表示没做成）。
     */
    fun syncNow(context: Context): AnipSync.SyncOutcome? {
        if (!syncing.compareAndSet(false, true)) return null
        // 也占用进程级同步锁：读路径据此判断「能不能清暂存目录」（RuleLibrary.prepareForRead）。
        // 同步期间读路径只收敛中间态，不会删掉正在构建的暂存目录。
        if (!io.github.deserthouse.opticon.engine.SyncGate.tryAcquire()) {
            syncing.set(false)
            return null
        }
        try {
            val outcome = AnipSync.syncDetailed(dir(context), context = context)
            when {
                // 上游没有新版本：只取了一次清单（260 字节），快照没动。设备验证套件靠日志区分
                // 「同步过了、没变化」和「没同步」，所以这两种结果各有一句固定的话。
                outcome.ok && outcome.unchanged ->
                    TraceLogger.i(TAG, "hook rule cache is already at release ${outcome.tag} (${outcome.count} icons) — nothing to sync")
                outcome.ok ->
                    TraceLogger.i(TAG, "ingested ${outcome.count} rule icons into hook-local cache")
                else ->
                    TraceLogger.w(TAG, "syncNow did not finish: ${outcome.failure}")
            }
            return outcome
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "syncNow: ${e.message}")
            return AnipSync.SyncOutcome(0, failure = AnipSync.Failure.LOCAL)
        } finally {
            syncing.set(false)
            io.github.deserthouse.opticon.engine.SyncGate.release()
        }
    }
}
