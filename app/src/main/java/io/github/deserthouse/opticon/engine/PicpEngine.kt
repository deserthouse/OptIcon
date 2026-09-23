package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * PicpEngine — downloads ALL PICP icons as a GitHub zipball, extracts locally.
 * No more API rate limits, no more step-by-step enumeration.
 * One big download, then done.
 */
object PicpEngine {

    private const val TAG = "OptIcon/PicpEngine"
    private const val CACHE_DIR = "picp"
    private const val INDEX_FILE = "picp_index.txt"
    private const val ZIPBALL_URL = "https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/zipball/main"

    private val index = ConcurrentHashMap<String, Boolean>(2000)

    fun getIndexedCount(context: Context): Int {
        if (index.isEmpty()) loadIndex(context)
        return index.size
    }

    fun hasIcon(packageName: String, context: Context): Boolean {
        if (index.isEmpty()) loadIndex(context)
        return index.containsKey(packageName)
    }

    /** Download entire PICP repo as zipball, extract all 1.png icons.
     *  Streams download to disk (atomic tmp+rename), validates the zip and
     *  retries once — field reports showed ENOENT on open after a silently
     *  failed download, which this self-heals. */
    fun syncIndex(context: Context, url: String, onProgress: ((String) -> Unit)?, onResult: (Boolean, Int, String?) -> Unit) {
        Thread {
            val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val zipFile = File(cacheDir, "picp_repo.zip")

            fun downloadZip(): Boolean {
                onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_streaming))
                // Write to a tmp file first so a failed/interrupted download can
                // never leave a missing or half-written zip behind.
                val tmp = File(cacheDir, "picp_repo.zip.tmp")
                tmp.delete()
                val ok = NetworkExecutor.fetchToFile(url, tmp) { downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    val sizeText = "${downloaded / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB ($pct %)" else ""}"
                    onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_bytes, sizeText))
                }
                if (!ok || !tmp.exists() || tmp.length() == 0L) {
                    TraceLogger.w(TAG, "PICP download failed (ok=$ok fileExists=${tmp.exists()} size=${if (tmp.exists()) tmp.length() else -1})")
                    tmp.delete()
                    return false
                }
                zipFile.delete()
                return tmp.renameTo(zipFile)
            }

            fun extractZip(): Int {
                index.clear()
                val iconDir = File(cacheDir, "icons").apply { mkdirs() }
                var count = 0
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // Match: * /themes/{theme}/icons/{pkg}/1.png
                        val match = Regex(".*/themes/[^/]+/icons/([^/]+)/1\\.png$").find(name)
                        if (match != null && !entry.isDirectory) {
                            val pkg = match.groupValues[1]
                            // Save first icon for each package (across themes)
                            if (!index.containsKey(pkg)) {
                                val outFile = File(iconDir, "$pkg.png")
                                FileOutputStream(outFile).use { zis.copyTo(it) }
                                index[pkg] = true
                                count++
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                File(cacheDir, INDEX_FILE).writeText(index.keys.joinToString("\n"))
                return count
            }

            try {
                var lastError: String? = null
                // Two attempts: transient network/storage hiccups self-heal.
                for (attempt in 1..2) {
                    if (attempt > 1) {
                        onProgress?.invoke("Retrying download...")
                        Thread.sleep(1500)
                    }
                    if (!downloadZip()) {
                        lastError = "Failed to download PICP zipball"
                        continue
                    }
                    if (!zipFile.exists() || zipFile.length() == 0L) {
                        // Should be impossible after the atomic tmp+rename —
                        // reported as ENOENT on some devices before this guard.
                        TraceLogger.w(TAG, "PICP zip missing after download (attempt $attempt)")
                        lastError = "Downloaded PICP zip is missing — download silently failed"
                        continue
                    }
                    onProgress?.invoke("Extracting icons...")
                    val count = try {
                        extractZip()
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "PICP extract failed: ${e}")
                        // A non-zip payload (HTML error page from a proxy, a
                        // truncated download) surfaces here — retry cleanly.
                        lastError = "PICP archive invalid: ${e.message}"
                        continue
                    }
                    zipFile.delete()
                    TraceLogger.i(TAG, "PICP extracted: $count icons")
                    onProgress?.invoke("PICP synced $count icons")
                    onResult(true, count, null)
                    return@Thread
                }
                onResult(false, 0, lastError ?: "PICP sync failed")
            } catch (e: Exception) {
                TraceLogger.w(TAG, "PICP sync error: ${e}")
                onResult(false, 0, "PICP sync failed: ${e.message}")
            }
        }.start()
    }

    private fun loadIndex(context: Context) {
        val file = File(File(context.filesDir, CACHE_DIR), INDEX_FILE)
        if (!file.exists()) return
        try {
            index.clear()
            file.readLines().forEach { if (it.isNotBlank()) index[it] = true }
        } catch (_: Exception) {}
    }

    /** Download PICP icon — reads from local cache, trims transparent padding, scales for preview. */
    fun downloadIcon(context: Context, packageName: String): Bitmap? {
        if (index.isEmpty()) loadIndex(context)
        if (!index.containsKey(packageName)) return null

        val iconDir = File(context.filesDir, CACHE_DIR)
        val cacheFile = File(iconDir, "icons/$packageName.png")
        if (!cacheFile.exists()) return null
        val src = BitmapFactory.decodeFile(cacheFile.absolutePath) ?: return null
        return IconNormalizer.normalize(src)
    }

    /** Prefetch PICP icons — no-op since zipball extraction already has all icons. */
    fun prefetchForInstalled(
        context: Context,
        installedPackages: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((Int) -> Unit)? = null
    ) {
        Thread {
            val targets = installedPackages.filter { hasIcon(it, context) }
            onProgress?.invoke(targets.size, targets.size) // all icons already local
            onDone?.invoke(targets.size)
        }.start()
    }

    const val FEEDBACK_URL = "https://github.com/pzcn/Perfect-Icons-Completion-Project/issues/new?template=icon_request.yml"
}
