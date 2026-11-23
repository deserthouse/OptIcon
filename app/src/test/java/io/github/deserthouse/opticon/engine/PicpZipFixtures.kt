package io.github.deserthouse.opticon.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用 PICP 归档夹具：按仓库的目录约定构造归档，并提供各种「切点」。
 */
internal object PicpZipFixtures {

    private const val REPO_ROOT = "repo-main/"

    /** themes/<theme>/icons/<pkg>/1.png（按给出顺序写入），外加任意附带条目。 */
    fun build(
        pkgs: List<String>,
        theme: String = "dark",
        extra: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            extra.forEach { (name, bytes) -> zos.putNext(name, bytes, deflated = true) }
            pkgs.forEach { pkg ->
                zos.putNext("$REPO_ROOT/themes/$theme/icons/$pkg/1.png", "png-of-$pkg".toByteArray(), deflated = true)
            }
        }
        return out.toByteArray()
    }

    /** 全部条目以 STORED 方式写入——数据原样落在归档里，便于定位后做单字节破坏。 */
    fun buildStored(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) -> zos.putNext(name, bytes, deflated = false) }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putNext(name: String, bytes: ByteArray, deflated: Boolean) {
        val entry = ZipEntry(name)
        if (!deflated) {
            val crc = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    fun write(file: File, bytes: ByteArray): File {
        file.writeBytes(bytes)
        return file
    }

    /** 中央目录的起始偏移。测试归档不带注释，EOCD 固定在末尾 22 字节。 */
    fun centralDirectoryOffset(zip: ByteArray): Int {
        val eocd = zip.size - 22
        require(
            zip[eocd] == 'P'.code.toByte() && zip[eocd + 1] == 'K'.code.toByte() &&
                zip[eocd + 2] == 5.toByte() && zip[eocd + 3] == 6.toByte()
        ) { "no end-of-central-directory record at the tail" }
        return readUInt32(zip, eocd + 16)
    }

    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readUInt32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
