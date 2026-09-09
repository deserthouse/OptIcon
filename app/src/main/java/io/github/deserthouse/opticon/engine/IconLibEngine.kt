package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * IconLibEngine —— Icon library lookup engine (memory-safe)
 *
 * Architecture change (batch 3 memory optimization):
 *   - Discarded HashMap<String, IconLibEntry> holding Base64 permanently.
 *   - Download JSON —— immediately decode Base64 —— write to filesDir/fankes_cache/{pkg}.png.
 *   - Only keep lightweight HashMap<String, IconLibMeta> (appName/contributor etc.).
 *   - lookup() changed from HashMap lookup to O(1) File.exists() + decodeFile.
 *   - L1 LRU cache for hot icons (<=20 entries), L2 = disk PNG (primary storage).
 *
 * Data source: https://github.com/fankes/AndroidNotifyIconAdapt (AGPL-3.0)
 */
object IconLibEngine {

    private const val TAG = "OptIcon/IconLibEngine"
    private const val ICON_SIZE_PX_TARGET = 96
    private const val L1_CACHE_MAX_ENTRIES = 20
    private const val CACHE_DIR = "fankes_cache"
    private const val META_FILE = "meta.json"

    // Lightweight metadata (no Base64)
    data class IconLibMeta(
        val appName: String = "",
        val contributorName: String = "",
        val isEnabled: Boolean = true
    )

    // State
    private var isInitialized = false
    private var cacheDir: File? = null
    private val lock = ReentrantReadWriteLock()

    // Lightweight index: only appName/contributor (no Base64)
    private val metaIndex = HashMap<String, IconLibMeta>(800)

    // L1 bitmap cache
    private var l1Cache: LruCache<String, Bitmap>? = null

    /**
     * Initialize from disk cache files.
     * Called after SubscriptionManager downloads and decodes the JSON.
     */
    fun initializeFromFiles(context: Context, cacheFiles: List<File>): Boolean {
        lock.write {
            cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
            l1Cache = LruCache(L1_CACHE_MAX_ENTRIES)

            // Rebuild meta index from disk
            val metaFile = File(cacheDir, META_FILE)
            if (metaFile.exists()) {
                try {
                    val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                    val arr = json.optJSONArray("apps") ?: JSONArray()
                    metaIndex.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val pkg = obj.getString("packageName")
                        metaIndex[pkg] = IconLibMeta(
                            appName = obj.optString("appName", ""),
                            contributorName = obj.optString("contributorName", ""),
                            isEnabled = obj.optBoolean("isEnabled", true)
                        )
                    }
                } catch (e: Exception) {
                    metaIndex.clear()
                }
            }

            isInitialized = true
        }
        return true
    }

    fun hasIcon(packageName: String): Boolean {
        if (!isInitialized) return false
        return lock.read {
            val dir = cacheDir ?: return@read false
            File(dir, "$packageName.png").exists()
        }
    }

    fun lookup(packageName: String): Bitmap? {
        if (!isInitialized) return null
        return lock.read {
            // L1 cache check
            l1Cache?.get(packageName)?.let { return@read it }

            // L2 disk read
            val dir = cacheDir ?: return@read null
            val file = File(dir, "$packageName.png")
            if (!file.exists()) return@read null

            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@read null
                l1Cache?.put(packageName, bitmap)
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getMeta(packageName: String): IconLibMeta? = lock.read { metaIndex[packageName] }

    fun getIndexedCount(): Int = lock.read { metaIndex.size }

    /**
     * Decode and persist a single JSON download: parse Base64 icons —— write PNG files,
     * then GC the JSON object.
     */
    fun ingestJson(context: Context, jsonString: String): Int {
        val dir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        // Make directory world-traversable for SystemUI hook access
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        var count = 0

        try {
            val s = jsonString.trim()
            val apps = if (s.startsWith("[")) JSONArray(s)
            else JSONObject(s).optJSONArray("apps") ?: JSONArray()
            val metaArr = JSONArray()

            for (i in 0 until apps.length()) {
                val obj = apps.getJSONObject(i)
                val pkg = obj.getString("packageName")
                // Validate package name format to prevent path traversal
                if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$"))) continue
                val b64 = obj.optString("iconBitmap", "").ifBlank { obj.optString("iconBase64", "") }
                val appName = obj.optString("appName", "")
                val contributor = obj.optString("contributorName", "")
                val enabled = obj.optBoolean("isEnabled", true)

                // Decode Base64 —— write PNG
                if (b64.isNotBlank()) {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val pngFile = File(dir, "$pkg.png")
                        pngFile.writeBytes(bytes)
                        pngFile.setReadable(true, false) // World-readable for SystemUI
                        count++
                    } catch (_: Exception) { }
                }

                // Save meta entry
                val metaObj = JSONObject().apply {
                    put("packageName", pkg)
                    put("appName", appName)
                    put("contributorName", contributor)
                    put("isEnabled", enabled)
                }
                metaArr.put(metaObj)
            }

            // Persist meta index
            val metaJson = JSONObject().apply { put("apps", metaArr) }
            File(dir, META_FILE).writeText(metaJson.toString(), Charsets.UTF_8)

            // Rebuild in-memory meta index (lightweight, no Base64)
            lock.write {
                cacheDir = dir
                metaIndex.clear()
                for (i in 0 until metaArr.length()) {
                    val obj = metaArr.getJSONObject(i)
                    metaIndex[obj.getString("packageName")] = IconLibMeta(
                        appName = obj.optString("appName", ""),
                        contributorName = obj.optString("contributorName", ""),
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                }
                l1Cache = LruCache(L1_CACHE_MAX_ENTRIES)
                isInitialized = true
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "ingestJson failed: ${e.message}", e)
        }

        return count
    }
}
