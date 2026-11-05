package io.github.deserthouse.opticon.engine

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * PicpZipScanner — PICP zipball → staging dir extractor (batch SYNC V1).
 *
 * Pure JDK (Zip + File), no Android dependencies — JVM-testable. The live
 * icons dir is never touched here: callers validate the staging result
 * (count > 0) before switching via [FileSnapshotSwitch]. A hostile payload
 * (empty zip, README-only zip, plain-text body) therefore cannot reach the
 * live state at all — the previous in-place extract used to clear the index
 * first and report success on such inputs (SYNTHESIS U01).
 */
internal object PicpZipScanner {

    /** Match: themes/{theme}/icons/{pkg}/1.png — first icon per package wins. */
    private val ICON_PATH = Regex(".*/themes/[^/]+/icons/([^/]+)/1\\.png$")

    /** Thrown when [shouldCancel] returns true between entries (batch SYNC V4). */
    class CancelledException : Exception("PICP extract cancelled")

    /**
     * Rebuild [staging] from scratch with one {pkg}.png per matched package
     * plus an index.txt manifest; returns the package count (0 = no matches).
     * Raises [CancelledException] promptly when [shouldCancel] fires.
     */
    fun extractToStaging(zipFile: File, staging: File, shouldCancel: (() -> Boolean)? = null): Int {
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val pkgs = LinkedHashMap<String, Boolean>()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (shouldCancel?.invoke() == true) throw CancelledException()
                val name = entry.name
                val match = ICON_PATH.find(name)
                if (match != null && !entry.isDirectory) {
                    val pkg = match.groupValues[1]
                    // Save first icon for each package (across themes)
                    if (!pkgs.containsKey(pkg)) {
                        FileOutputStream(File(staging, "$pkg.png")).use { zis.copyTo(it) }
                        pkgs[pkg] = true
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        File(staging, "index.txt").writeText(pkgs.keys.joinToString("\n"))
        return pkgs.size
    }
}
