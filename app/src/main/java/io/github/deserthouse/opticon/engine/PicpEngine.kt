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
     *  Streams download to disk — zero heap pressure. */
    fun syncIndex(context: Context, url: String, onProgress: ((String) -> Unit)?, onResult: (Boolean, Int, String?) -> Unit) {
        Thread {
            try {
                val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
                val zipFile = File(cacheDir, "picp_repo.zip")

                // Stream download directly to disk (no byte array in memory)
                onProgress?.invoke("Downloading PICP (streaming)...")
                val ok = NetworkExecutor.fetchToFile(url, zipFile) { downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    onProgress?.invoke("Downloading ${downloaded / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB ($pct %)" else ""}")
                }
                if (!ok) {
                    zipFile.delete()
                    onResult(false, 0, "Failed to download PICP zipball")
                    return@Thread
                }

                onProgress?.invoke("Extracting icons...")
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

                // Cleanup zip
                zipFile.delete()

                // Save index
                File(cacheDir, INDEX_FILE).writeText(index.keys.joinToString("\n"))
                TraceLogger.i(TAG, "PICP extracted: $count icons")
                onProgress?.invoke("PICP synced $count icons")
                onResult(true, count, null)
            } catch (e: Exception) {
                onResult(false, 0, "PICP extraction failed: ${e.message}")
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
