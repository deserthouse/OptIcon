package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PicpZipScanner 单测（批 SYNC V1 验收第一组）。
 *
 * SYNTHESIS U01 反例三类输入：合法但无匹配条目的 ZIP、非 ZIP 文本、正常 ZIP
 * ——前两类必须返回 0（调用方据此保留旧态、报失败），且 staging 构建本身
 * 不触碰 live 目录（结构性：函数签名无 live 路径）。
 */
class PicpZipScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(entries: Map<String, ByteArray?>): File {
        val f = tmp.newFile("payload.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                if (bytes != null) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `normal zip extracts first icon per package into staging`() {
        val zip = zipOf(mapOf(
            "repo-main/themes/dark/icons/com.a/1.png" to "pngA".toByteArray(),
            "repo-main/themes/dark/icons/com.a/2.png" to "altA".toByteArray(),
            "repo-main/themes/light/icons/com.a/1.png" to "altA2".toByteArray(),
            "repo-main/themes/dark/icons/com.b/1.png" to "pngB".toByteArray(),
            "repo-main/README.md" to "readme".toByteArray()
        ))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(2, count)
        assertEquals("pngA", File(staging, "com.a.png").readText()) // first theme wins
        assertEquals("pngB", File(staging, "com.b.png").readText())
        assertTrue(File(staging, "index.txt").readText().split("\n").contains("com.a"))
    }

    @Test
    fun `legal zip with no matching icons returns zero`() {
        // U01 反例②：合法 ZIP 结构，但没有任何 themes/…/icons/{pkg}/1.png
        val zip = zipOf(mapOf("repo-main/README.md" to "just a readme".toByteArray()))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(0, count)
        assertEquals(listOf("index.txt"), staging.listFiles()!!.map { it.name }) // 空索引，无包文件
        assertEquals("", File(staging, "index.txt").readText())
    }

    @Test
    fun `plain text body yields zero without throwing`() {
        // U01 反例③：非 ZIP 文本（如代理返回的 HTML 错误页另经 catch 重试；
        // 本例=字节合法但非 PK magic 的文本文件——ZipInputStream 读不到 entry，
        // 不抛异常，历史实现在此返回 0 后仍报成功——修复后调用方按 0 判失败）
        val textFile = tmp.newFile("payload.txt")
        textFile.writeText("<html>Service Unavailable</html>")
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(textFile, staging)

        assertEquals(0, count)
    }

    @Test
    fun `cancel between entries raises CancelledException and leaves staging partial`() {
        val zip = zipOf(mapOf(
            "repo-main/themes/dark/icons/com.a/1.png" to "pngA".toByteArray(),
            "repo-main/themes/dark/icons/com.b/1.png" to "pngB".toByteArray()
        ))
        val staging = tmp.newFolder("staging")

        // 第二个 entry 前置取消
        val seen = intArrayOf(0)
        try {
            PicpZipScanner.extractToStaging(zip, staging) {
                seen[0]++ >= 1
            }
            throw AssertionError("extract should have been cancelled")
        } catch (e: PicpZipScanner.CancelledException) {
            // expected
        }
        // 中止态：调用方（syncIndex）负责清 staging——断言未收尾即可
        assertFalse(File(staging, "index.txt").exists() && staging.listFiles()!!.size == 1)
    }

    @Test
    fun `stale staging content is cleared before rebuild`() {
        val zip = zipOf(mapOf("repo-main/themes/dark/icons/com.a/1.png" to "pngA".toByteArray()))
        val staging = tmp.newFolder("staging")
        File(staging, "stale.png").writeText("leftover") // 上次失败残留

        PicpZipScanner.extractToStaging(zip, staging)

        assertFalse(File(staging, "stale.png").exists())
        assertEquals("pngA", File(staging, "com.a.png").readText())
    }
}
