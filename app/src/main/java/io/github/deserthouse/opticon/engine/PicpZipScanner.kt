package io.github.deserthouse.opticon.engine

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * PicpZipScanner — PICP zipball → staging dir extractor (batch SYNC V1).
 *
 * Pure JDK (Zip + File), no Android dependencies — JVM-testable. The live
 * icons dir is never touched here: callers validate the staging result
 * (count > 0) before switching via [FileSnapshotSwitch]. A hostile payload
 * (empty zip, README-only zip, plain-text body) therefore cannot reach the
 * live state at all — the previous in-place extract used to clear the index
 * first and report success on such inputs (SYNTHESIS U01).
 *
 * 修复批（X-16）：归档改用 [ZipFile] 按**中央目录**读取。打开时必须读到文件
 * 尾部的中央目录，所以下载被截断的归档——无论切在数据中间、条目边界还是
 * 中央目录区——在这里就会被拒绝。此前的顺序流读法（ZipInputStream）只看各
 * 条目自己的局部头，切在条目边界的截断归档会被当成一份完整的短归档接受。
 * 用到的每个条目再按中央目录登记的长度与 CRC-32 复核，数据区损坏的图标
 * 不会以坏图的身份进入新版本。
 */
internal object PicpZipScanner {

    /** Match: themes/{theme}/icons/{pkg}/1.png — first icon per package wins. */
    private val ICON_PATH = Regex(".*/themes/[^/]+/icons/([^/]+)/1\\.png$")

    /** Thrown when [shouldCancel] returns true while working. */
    class CancelledException : Exception("PICP extract cancelled")

    /** 归档不完好：不是压缩包、中央目录缺失或损坏（截断的典型形态）、条目数据
     *  与中央目录登记的不一致（解压出错、长度或校验和不符）。 */
    class InvalidArchiveException(detail: String) : Exception("invalid archive: $detail")

    /**
     * Rebuild [staging] from scratch with one {pkg}.png per matched package
     * plus an index.txt manifest; returns the package count (0 = no matches).
     * Raises [CancelledException] promptly when [shouldCancel] fires, and
     * [InvalidArchiveException] when the archive cannot be trusted.
     */
    fun extractToStaging(zipFile: File, staging: File, shouldCancel: (() -> Boolean)? = null): Int {
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw InvalidArchiveException("cannot create staging dir")
        }
        val pkgs = LinkedHashMap<String, Boolean>()
        val zip = try {
            ZipFile(zipFile)
        } catch (e: IOException) {
            throw InvalidArchiveException(
                "not a complete zip (${e.javaClass.simpleName}: ${e.message})"
            )
        }
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (shouldCancel?.invoke() == true) throw CancelledException()
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val pkg = ICON_PATH.find(entry.name)?.groupValues?.get(1) ?: continue
                // 每个包取先遇到的那个主题里的图
                if (pkgs.containsKey(pkg)) continue
                // 包目录名只会拼成 "{pkg}.png" 一个文件名。挡掉路径分隔符与相对
                // 路径成分——恶意归档无法把文件写出暂存目录（Windows 上反斜杠也是分隔符）。
                if (pkg == "." || pkg == ".." || pkg.contains('\\')) continue
                copyVerified(zip, entry, File(staging, "$pkg.png"), shouldCancel)
                pkgs[pkg] = true
            }
        } finally {
            zip.close()
        }
        File(staging, PicpLibrary.INDEX_FILE).writeText(pkgs.keys.joinToString("\n"))
        return pkgs.size
    }

    /** 解压一个条目到 [target]，并按中央目录登记的长度与 CRC-32 复核。 */
    private fun copyVerified(
        zip: ZipFile,
        entry: ZipEntry,
        target: File,
        shouldCancel: (() -> Boolean)?
    ) {
        val crc = CRC32()
        var written = 0L
        try {
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (shouldCancel?.invoke() == true) throw CancelledException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        written += read
                    }
                }
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw InvalidArchiveException(
                "entry ${entry.name} (${e.javaClass.simpleName}: ${e.message})"
            )
        }
        // 中央目录没登记长度/校验和时取值为 -1，此时无从复核
        if (entry.size >= 0 && written != entry.size) {
            target.delete()
            throw InvalidArchiveException(
                "entry ${entry.name}: $written bytes, declared ${entry.size}"
            )
        }
        if (entry.crc >= 0L && crc.value != entry.crc) {
            target.delete()
            throw InvalidArchiveException("entry ${entry.name}: checksum mismatch")
        }
    }
}
