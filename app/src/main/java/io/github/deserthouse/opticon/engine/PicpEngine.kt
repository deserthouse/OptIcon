package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * PicpEngine — downloads ALL PICP icons as a GitHub zipball, extracts locally.
 * No more API rate limits, no more step-by-step enumeration.
 * One big download, then done.
 *
 * Batch SYNC V1: updates are atomic. The zip is extracted into a staging dir
 * which is validated (non-empty) BEFORE the live icons/ dir is touched; the
 * switch itself is an atomic directory swap (FileSnapshotSwitch) with rollback
 * on failure and crash recovery on next load. A hostile/empty/broken source
 * can no longer wipe the usable index while reporting success (SYNTHESIS U01).
 *
 * Batch SYNC V4: downloads carry a byte budget (MAX_ZIP_BYTES) and a
 * cooperative cancel flag (syncCancel) — polled between download chunks and
 * zip entries; cancellation and budget breach never touch the live state.
 */
object PicpEngine {

    private const val TAG = "OptIcon/PicpEngine"
    private const val CACHE_DIR = "picp"
    private const val INDEX_FILE = "picp_index.txt"
    private const val ZIPBALL_URL = "https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/zipball/main"

    /** Batch SYNC V4: hard download budget — real zipball is ~0.5GB; 2GB
     *  absorbs reasonable growth while still capping a hostile source. */
    const val MAX_ZIP_BYTES: Long = 2L * 1024 * 1024 * 1024

    private val index = ConcurrentHashMap<String, Boolean>(2000)
    private val cancelFlag = AtomicBoolean(false)

    fun getIndexedCount(context: Context): Int {
        if (index.isEmpty()) loadIndex(context)
        return index.size
    }

    fun hasIcon(packageName: String, context: Context): Boolean {
        if (index.isEmpty()) loadIndex(context)
        return index.containsKey(packageName)
    }

    /** Batch SYNC V4: request cancellation of an in-flight sync. Safe to call
     *  anytime; a sync not running simply leaves the flag set for the next
     *  run — syncIndex clears it on entry. */
    fun syncCancel() { cancelFlag.set(true) }

    /** Poll point for [PicpZipScanner] cancellation — live flag, no stale copy. */
    internal fun isCancelled(): Boolean = cancelFlag.get()

    /** Download entire PICP repo as zipball, extract into staging, validate,
     *  atomically switch. Streams download to disk (atomic tmp+rename) with a
     *  byte budget and cancel support, retries transient download failures
     *  once. An empty/invalid payload fails WITHOUT touching the live index. */
    fun syncIndex(context: Context, url: String, onProgress: ((String) -> Unit)?, onResult: (Boolean, Int, String?) -> Unit) {
        cancelFlag.set(false)
        Thread {
            val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val zipFile = File(cacheDir, "picp_repo.zip")
            val staging = FileSnapshotSwitch.stagingOf(cacheDir, "icons")

            fun downloadZip(): NetworkExecutor.DownloadOutcome {
                onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_streaming))
                // Write to a tmp file first so a failed/interrupted download can
                // never leave a missing or half-written zip behind.
                val tmp = File(cacheDir, "picp_repo.zip.tmp")
                tmp.delete()
                val outcome = NetworkExecutor.fetchToFileBounded(url, tmp, MAX_ZIP_BYTES, { cancelFlag.get() }) { downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    val sizeText = "${downloaded / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB ($pct %)" else ""}"
                    onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_bytes, sizeText))
                }
                if (outcome != NetworkExecutor.DownloadOutcome.OK) {
                    tmp.delete()
                    return outcome
                }
                if (!tmp.exists() || tmp.length() == 0L) {
                    TraceLogger.w(TAG, "PICP download empty (fileExists=${tmp.exists()} size=${if (tmp.exists()) tmp.length() else -1})")
                    tmp.delete()
                    return NetworkExecutor.DownloadOutcome.FAILED
                }
                zipFile.delete()
                return if (tmp.renameTo(zipFile)) NetworkExecutor.DownloadOutcome.OK else NetworkExecutor.DownloadOutcome.FAILED
            }

            /** Extract matched icons into [staging]; returns count. Never
             *  touches the live index — pure staging build (logic in
             *  [PicpZipScanner], JVM-testable). */

            try {
                var lastError: String? = null
                // Two attempts for TRANSIENT failures only (network/invalid zip).
                // Deterministic failures (empty index, cancelled, budget) and
                // switch failures return immediately.
                for (attempt in 1..2) {
                    if (cancelFlag.get()) { onResult(false, 0, context.getString(io.github.deserthouse.opticon.R.string.picp_fail_cancelled)); return@Thread }
                    if (attempt > 1) {
                        onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_retrying))
                        Thread.sleep(1500)
                    }
                    val outcome = downloadZip()
                    if (outcome != NetworkExecutor.DownloadOutcome.OK) {
                        lastError = when (outcome) {
                            NetworkExecutor.DownloadOutcome.CANCELLED -> context.getString(io.github.deserthouse.opticon.R.string.picp_fail_cancelled)
                            NetworkExecutor.DownloadOutcome.TOO_LARGE -> context.getString(io.github.deserthouse.opticon.R.string.picp_fail_too_large)
                            else -> context.getString(io.github.deserthouse.opticon.R.string.picp_fail_download)
                        }
                        if (outcome != NetworkExecutor.DownloadOutcome.FAILED) {
                            onResult(false, 0, lastError)
                            return@Thread
                        }
                        continue
                    }
                    if (!zipFile.exists() || zipFile.length() == 0L) {
                        // Should be impossible after the atomic tmp+rename —
                        // reported as ENOENT on some devices before this guard.
                        TraceLogger.w(TAG, "PICP zip missing after download (attempt $attempt)")
                        lastError = context.getString(io.github.deserthouse.opticon.R.string.picp_fail_missing)
                        continue
                    }
                    onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_extracting))
                    // Extract matched icons into staging (pure staging build —
                    // the live index is only touched after a validated switch).
                    val count = try {
                        PicpZipScanner.extractToStaging(zipFile, staging) { isCancelled() }
                    } catch (e: PicpZipScanner.CancelledException) {
                        staging.deleteRecursively()
                        zipFile.delete()
                        onResult(false, 0, context.getString(io.github.deserthouse.opticon.R.string.picp_fail_cancelled))
                        return@Thread
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "PICP extract failed: ${e}")
                        // A non-zip payload (HTML error page from a proxy, a
                        // truncated download) surfaces here — retry cleanly.
                        lastError = context.getString(io.github.deserthouse.opticon.R.string.picp_fail_invalid, e.message ?: "")
                        continue
                    }
                    zipFile.delete()
                    // V1 gate: an empty index means the source has no usable
                    // PICP payload. Fail WITHOUT touching the live state —
                    // reporting success here used to wipe the whole usable
                    // index (SYNTHESIS U01). Empty is deterministic: no retry.
                    if (count == 0) {
                        staging.deleteRecursively()
                        TraceLogger.w(TAG, "PICP payload contains no usable icons — keeping live index")
                        onResult(false, 0, context.getString(io.github.deserthouse.opticon.R.string.picp_fail_empty))
                        return@Thread
                    }
                    // V1 switch: staging validated, swap atomically. The root
                    // INDEX_FILE swap runs as the afterPromote step so its
                    // failure still rolls the whole thing back (the commit
                    // point sits inside FileSnapshotSwitch, before prev is
                    // dropped — a plain switchIn would leave the root index
                    // stranded if the swap failed).
                    try {
                        FileSnapshotSwitch.switchIn(cacheDir, "icons") {
                            val innerIndex = File(cacheDir, "icons/index.txt")
                            val rootIndex = File(cacheDir, INDEX_FILE)
                            val tmpIndex = File(cacheDir, "$INDEX_FILE.tmp")
                            tmpIndex.delete()
                            if (!innerIndex.renameTo(tmpIndex)) throw FileSnapshotSwitch.SwitchException("index→tmp")
                            rootIndex.delete()
                            if (!tmpIndex.renameTo(rootIndex)) throw FileSnapshotSwitch.SwitchException("tmp→index")
                        }
                        index.clear()
                        loadIndex(context)
                        TraceLogger.i(TAG, "PICP extracted: $count icons (atomic switch ok)")
                        onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_picp_extracted, count))
                        onResult(true, count, null)
                        return@Thread
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "PICP switch failed, rolled back: ${e.message}")
                        index.clear()
                        loadIndex(context)
                        onResult(false, 0, context.getString(io.github.deserthouse.opticon.R.string.picp_fail_switch))
                        return@Thread
                    }
                }
                onResult(false, 0, lastError ?: context.getString(io.github.deserthouse.opticon.R.string.picp_fail_generic, ""))
            } catch (e: Exception) {
                TraceLogger.w(TAG, "PICP sync error: ${e}")
                onResult(false, 0, context.getString(io.github.deserthouse.opticon.R.string.picp_fail_generic, e.message ?: ""))
            } finally {
                staging.deleteRecursively() // belt & braces: no staging residue
            }
        }.start()
    }

    private fun loadIndex(context: Context) {
        val cacheDir = File(context.filesDir, CACHE_DIR)
        // Crash recovery: a switch interrupted between steps converges before
        // we read the index (live missing + prev present → back to old live).
        FileSnapshotSwitch.recoverIfInterrupted(cacheDir, "icons")
        val file = File(cacheDir, INDEX_FILE)
        if (!file.exists()) {
            // Defense in depth: the inner staging index may still exist if a
            // crash hit between the inner-index swap and the root rename.
            val inner = File(cacheDir, "icons/index.txt")
            if (inner.exists()) {
                try { inner.copyTo(file, overwrite = true) } catch (_: Exception) { return }
            } else return
        }
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
