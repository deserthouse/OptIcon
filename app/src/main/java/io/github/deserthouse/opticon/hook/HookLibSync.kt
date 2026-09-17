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
 *   - immediately when the shared-dir manifest bumps its version
 *     (App → hook wakeup channel, no Binder involved)
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

    private val syncing = AtomicBoolean(false)
    private val lastSyncAt = AtomicLong(0L)

    @Volatile
    private var iconDir: File? = null

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
        d.mkdirs()
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

    /** Launch the background self-sync loop. Call once from hook init. */
    fun start(context: Context) {
        Thread({
            Thread.sleep(BOOT_DELAY_MS)
            while (true) {
                val ok = syncNow(context)
                lastSyncAt.set(System.currentTimeMillis())
                TraceLogger.i(TAG, "self-sync ${if (ok) "ok" else "failed"}, next in ${SYNC_INTERVAL_MS / 3600000}h")
                Thread.sleep(SYNC_INTERVAL_MS)
            }
        }, "OptIconHookLibSync").apply { isDaemon = true }.start()
    }

    /** One-shot sync: download ANIP manifests + PNGs into cacheDir. */
    fun syncNow(context: Context): Boolean {
        if (!syncing.compareAndSet(false, true)) return false
        try {
            val count = io.github.deserthouse.opticon.engine.AnipSync.syncTo(dir(context))
            TraceLogger.i(TAG, "ingested $count rule icons into hook-local cache")
            return count > 0
        } catch (e: Exception) {
            TraceLogger.w(TAG, "syncNow: ${e.message}")
            return false
        } finally {
            syncing.set(false)
        }
    }
}
