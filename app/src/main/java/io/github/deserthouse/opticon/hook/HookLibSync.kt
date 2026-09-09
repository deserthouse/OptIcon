package io.github.deserthouse.opticon.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.github.deserthouse.opticon.util.TraceLogger
import org.json.JSONArray
import org.json.JSONObject
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
 * The rules JSON format is identical to what the App ingests via
 * IconLibEngine.ingestJson: an array of
 *   { packageName, iconBitmap|iconBase64, appName, contributorName, isEnabled }
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
    private const val DEFAULT_RULES_URL =
        "https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main/README.md"
    private const val JSON_URL_TEMPLATE =
        "https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main/json/%s.json"

    /** Full self-sync every 12h while SystemUI is alive. */
    private const val SYNC_INTERVAL_MS = 12L * 60 * 60 * 1000
    /** Post-boot grace period before first sync (network settle). */
    private const val BOOT_DELAY_MS = 30_000L

    private val syncing = AtomicBoolean(false)
    private val lastSyncAt = AtomicLong(0L)

    @Volatile
    private var iconDir: File? = null

    /** Directory holding hook-process-local rule icons. */
    fun dir(context: Context): File =
        iconDir ?: File(context.cacheDir, DIR_NAME).also {
            it.mkdirs()
            iconDir = it
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

    /** One-shot sync: download rules JSON, decode icons into cacheDir. */
    fun syncNow(context: Context): Boolean {
        if (!syncing.compareAndSet(false, true)) return false
        try {
            val json = fetchRulesJson() ?: return false
            val count = ingest(context, json)
            TraceLogger.i(TAG, "ingested $count rule icons into hook-local cache")
            return count > 0
        } catch (e: Exception) {
            TraceLogger.w(TAG, "syncNow: ${e.message}")
            return false
        } finally {
            syncing.set(false)
        }
    }

    private fun fetchRulesJson(): String? {
        // Same URL scheme the App uses; hook process downloads independently.
        val candidates = listOf(
            "https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main/notification_icon_adapt.json",
            "https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main/json/notification_icon_adapt.json"
        )
        for (url in candidates) {
            try {
                val body = io.github.deserthouse.opticon.network.NetworkExecutor.fetchStringSync(url)
                if (!body.isNullOrBlank() && (body.trimStart().startsWith("[") || body.trimStart().startsWith("{"))) {
                    return body
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Decode rules JSON → PNGs in the hook-local dir. Returns icon count. */
    private fun ingest(context: Context, jsonString: String): Int {
        val dir = dir(context)
        var count = 0
        val s = jsonString.trim()
        val apps = if (s.startsWith("[")) JSONArray(s)
        else JSONObject(s).optJSONArray("apps") ?: JSONArray()

        for (i in 0 until apps.length()) {
            try {
                val obj = apps.getJSONObject(i)
                val pkg = obj.getString("packageName")
                if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$"))) continue
                if (!obj.optBoolean("isEnabled", true)) continue
                val b64 = obj.optString("iconBitmap", "").ifBlank { obj.optString("iconBase64", "") }
                if (b64.isBlank()) continue
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                File(dir, "$pkg.png").writeBytes(bytes)
                count++
            } catch (_: Exception) {
            }
        }
        return count
    }
}
