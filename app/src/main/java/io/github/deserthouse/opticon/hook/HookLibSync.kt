package io.github.deserthouse.opticon.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * Sync triggers:
 *   - once at hook init (background thread, ~30s delay to let the network
 *     settle after boot)
 *   - every SYNC_INTERVAL_MS thereafter while SystemUI lives
 *   - immediately when the App-side sync lands a fresh meta.json — the
 *     hook's FileObserver on that directory calls requestSync() (no Binder;
 *     the observer already watches the dir for cache busts)
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

    private val syncing = AtomicBoolean(false)
    private val lastSyncAt = AtomicLong(0L)

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
        // 收敛失败时 ensureDir 不建目录：读方暂时什么都读不到，但完好的旧版还在备份里。
        io.github.deserthouse.opticon.engine.RuleLibrary.ensureDir(d)
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
     * Launch the background self-sync loop from hook init. Batch SYNC V5:
     * opt-in — with the auto-update flag off (the default) no loop thread is
     * created at all; requestSync() below stays always-available as the
     * manual-sync extension path.
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!autoUpdateEnabled()) {
            TraceLogger.i(TAG, "auto lib update disabled (default) — no background sync loop")
            return
        }
        Thread({
            Thread.sleep(BOOT_DELAY_MS)
            var interval = SYNC_INTERVAL_MS
            while (true) {
                if (!autoUpdateEnabled()) {
                    // Flag flipped off at runtime — park quietly, keep checking
                    // at the base cadence without ever syncing.
                    TraceLogger.i(TAG, "auto lib update flag off — skipping round")
                } else if (!masterEnabled()) {
                    TraceLogger.i(TAG, "master switch off — skipping round")
                } else {
                    val ok = syncNow(context)
                    lastSyncAt.set(System.currentTimeMillis())
                    interval = nextSyncInterval(interval, ok)
                    TraceLogger.i(TAG, "self-sync ${if (ok) "ok" else "failed"}, next in ${interval / 3600000}h")
                }
                Thread.sleep(interval)
            }
        }, "OptIconHookLibSync").apply { isDaemon = true }.start()
    }

    /** App→hook wakeup: the App just published fresh rules (meta.json event).
     *  Debounced by the syncing flag — a call during an in-flight sync is a
     *  no-op because that run already sees the new data. */
    fun requestSync() {
        val ctx = appContext ?: return
        Thread({ syncNow(ctx) }, "OptIconHookSyncWake").apply { isDaemon = true }.start()
    }

    /** One-shot sync: download ANIP manifests + PNGs into cacheDir. */
    fun syncNow(context: Context): Boolean {
        if (!syncing.compareAndSet(false, true)) return false
        try {
            val count = io.github.deserthouse.opticon.engine.AnipSync.syncTo(dir(context), context = context)
            TraceLogger.i(TAG, "ingested $count rule icons into hook-local cache")
            return count > 0
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "syncNow: ${e.message}")
            return false
        } finally {
            syncing.set(false)
        }
    }
}
