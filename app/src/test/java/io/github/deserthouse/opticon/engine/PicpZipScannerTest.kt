package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PicpZipScanner 单测（批 SYNC V1 验收第一组 + 修复批 X-16）。
 *
 * SYNTHESIS U01 反例三类输入：合法但无匹配条目的 ZIP、非 ZIP 文本、正常 ZIP
 * ——前两类必须如实报告（0 / 无效），且 staging 构建本身不触碰 live 目录
 * （结构性：函数签名无 live 路径）。
 *
 * 修复批：归档按中央目录读取，截断的归档——切在数据中间、条目边界、中央目录区——
 * 一律判无效；用到的条目按登记长度与 CRC-32 复核。
 */
class PicpZipScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 按给定顺序写入「条目名 → 内容」的归档（DEFLATED）。 */
    private fun zipOfEntries(entries: List<Pair<String, String>>): File =
        PicpZipFixtures.write(tmp.newFile("payload.zip"), rawZip(entries.map { it.first to it.second.toByteArray() }))

    private fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
        } catch (_: PicpZipScanner.InvalidArchiveException) {
            return
        }
        fail("archive should have been rejected as invalid")
    }

    @Test
    fun `normal zip extracts one icon per package into staging`() {
        val zip = zipOfEntries(listOf(
            "repo-main/themes/dark/icons/com.a/1.png" to "pngA",
            "repo-main/themes/dark/icons/com.a/2.png" to "altA",
            "repo-main/README.md" to "readme",
            "repo-main/themes/dark/icons/com.b/1.png" to "pngB"
        ))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(2, count)
        assertEquals("pngA", File(staging, "com.a.png").readText())
        assertEquals("pngB", File(staging, "com.b.png").readText())
        assertEquals(setOf("com.a", "com.b"), File(staging, "index.txt").readText().split("\n").toSet())
        assertEquals(
            setOf("com.a.png", "com.b.png", "index.txt"),
            staging.listFiles()!!.map { it.name }.toSet()
        )
    }

    @Test
    fun `the outer directory name does not matter`() {
        // 批 UP-U3：内置地址从 GitHub API 的 zipball 接口换成普通归档地址，两者解出来的外层目录名不同
        // （`<用户>-<仓库>-<提交>` 对 `<仓库>-main`）。扫描器按 themes/…/icons/<包名>/1.png 匹配，两种都要能用。
        var n = 0
        for (root in listOf("pzcn-Perfect-Icons-Completion-Project-1a2b3c4d", "Perfect-Icons-Completion-Project-main")) {
            val zip = PicpZipFixtures.write(
                File(tmp.root, "payload-${n++}.zip"),
                rawZip(listOf(
                    "$root/themes/dark/icons/com.a/1.png" to "pngA",
                    "$root/app/src/main/AndroidManifest.xml" to "<manifest/>"
                ).map { it.first to it.second.toByteArray() })
            )
            val staging = File(tmp.root, "staging-${n}")

            assertEquals(1, PicpZipScanner.extractToStaging(zip, staging))
            assertEquals("pngA", File(staging, "com.a.png").readText())
        }
    }

    // ━━ 本地写不进去是存储问题，不是归档的问题（批 MT M3） ━━

    @Test
    fun `a staging dir that cannot be created is a storage problem`() {
        val blocker = tmp.newFile("blocker")   // 同名位置是个文件：建不了目录
        val zip = zipOfEntries(listOf("repo-main/themes/dark/icons/com.a/1.png" to "pngA"))

        expectStorage { PicpZipScanner.extractToStaging(zip, File(blocker, "child")) }
    }

    @Test
    fun `an entry that cannot be written is a storage problem`() {
        val zip = zipOfEntries(listOf(
            "repo-main/themes/dark/icons/com.a/1.png" to "pngA",
            "repo-main/themes/dark/icons/com.b/1.png" to "pngB"
        ))
        val staging = tmp.newFolder("staging")
        var polls = 0
        // 第 1 次询问在第一个条目之前，第 2、3 次在它的读写循环里，第 4 次正好轮到第二个条目：
        // 把它的目标位置占成一个目录
        val shouldCancel = {
            polls++
            if (polls == 4) File(staging, "com.b.png").mkdirs()
            false
        }

        expectStorage { PicpZipScanner.extractToStaging(zip, staging, shouldCancel) }

        assertEquals("第一个条目已经写好", "pngA", File(staging, "com.a.png").readText())
    }

    @Test
    fun `an entry whose data cannot be written is a storage problem, not a bad archive`() {
        // 磁盘满的典型形态：文件建得起来，写了一半写不进。注入一个一写就抛的输出流
        val zip = zipOfEntries(listOf("repo-main/themes/dark/icons/com.a/1.png" to "pngA"))
        val staging = tmp.newFolder("staging")
        PicpZipScanner.openOutput = { _ ->
            object : java.io.OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("disk full")
            }
        }
        try {
            expectStorage { PicpZipScanner.extractToStaging(zip, staging) }
        } finally {
            PicpZipScanner.openOutput = { it.outputStream() }
        }
        assertFalse("写了一半的文件不留", File(staging, "com.a.png").exists())
    }

    private fun expectStorage(block: () -> Unit) {
        try {
            block()
        } catch (_: StorageWriteException) {
            return
        }
        fail("a local write failure should surface as StorageWriteException")
    }

    @Test
    fun `first theme wins for a package that appears in several themes`() {
        val zip = zipOfEntries(listOf(
            "repo-main/themes/dark/icons/com.a/1.png" to "from-dark",
            "repo-main/themes/dark/icons/com.b/1.png" to "b",
            "repo-main/themes/light/icons/com.a/1.png" to "from-light"
        ))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(2, count)
        assertEquals("from-dark", File(staging, "com.a.png").readText())
    }

    @Test
    fun `legal zip with no matching icons returns zero`() {
        // U01 反例②：合法 ZIP 结构，但没有任何 themes/…/icons/{pkg}/1.png
        val zip = zipOfEntries(listOf("repo-main/README.md" to "just a readme"))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(0, count)
        assertEquals(listOf("index.txt"), staging.listFiles()!!.map { it.name }) // 空索引，无包文件
        assertEquals("", File(staging, "index.txt").readText())
    }

    @Test
    fun `plain text body is rejected as invalid`() {
        // U01 反例③：非 ZIP 文本（如代理返回的 HTML 错误页）。
        // 修复批之前它被算成「0 个图标」；现在没有中央目录就不是完好的归档。
        val textFile = tmp.newFile("payload.txt")
        textFile.writeText("<html>Service Unavailable</html>")
        val staging = tmp.newFolder("staging")

        expectInvalid { PicpZipScanner.extractToStaging(textFile, staging) }

        assertFalse(File(staging, "index.txt").exists())
    }

    @Test
    fun `archive truncated at an entry boundary is rejected`() {
        // 修复批 X-16：切点正好在最后一个条目的数据末尾。顺序流读法会把它
        // 当成一份完整的短归档接受。
        val zip = PicpZipFixtures.build(pkgs = listOf("com.a", "com.b"))
        val cut = PicpZipFixtures.centralDirectoryOffset(zip)
        assertTrue(cut in 1 until zip.size)
        val staging = tmp.newFolder("staging")

        expectInvalid { PicpZipScanner.extractToStaging(zipOfBytes(zip.copyOf(cut)), staging) }
    }

    @Test
    fun `archive truncated in the middle of entry data is rejected`() {
        val zip = PicpZipFixtures.build(pkgs = listOf("com.a", "com.b"))
        val cut = PicpZipFixtures.centralDirectoryOffset(zip) / 2
        val staging = tmp.newFolder("staging")

        expectInvalid { PicpZipScanner.extractToStaging(zipOfBytes(zip.copyOf(cut)), staging) }
    }

    @Test
    fun `archive truncated inside the central directory is rejected`() {
        val zip = PicpZipFixtures.build(pkgs = listOf("com.a", "com.b"))
        val cut = PicpZipFixtures.centralDirectoryOffset(zip) + 10
        val staging = tmp.newFolder("staging")

        expectInvalid { PicpZipScanner.extractToStaging(zipOfBytes(zip.copyOf(cut)), staging) }
    }

    @Test
    fun `every possible truncation of a small archive is rejected`() {
        // 穷举切点：从 0 字节到「只差最后一个字节」，没有一个切点能被接受。
        val zip = PicpZipFixtures.build(pkgs = listOf("com.a", "com.b"))
        val file = tmp.newFile("sweep.zip")
        val staging = tmp.newFolder("sweep")
        val accepted = mutableListOf<Int>()
        for (cut in 0 until zip.size) {
            file.writeBytes(zip.copyOf(cut))
            try {
                PicpZipScanner.extractToStaging(file, staging)
                accepted += cut
            } catch (_: PicpZipScanner.InvalidArchiveException) {
                // expected
            }
        }
        assertEquals("truncations that were wrongly accepted", emptyList<Int>(), accepted)

        // 阳性对照：同一份夹具不截断时必须成功——否则上面的「全拒」毫无意义
        PicpZipFixtures.write(file, zip)
        assertEquals(2, PicpZipScanner.extractToStaging(file, staging))
    }

    @Test
    fun `entry data that does not match its declared checksum is rejected`() {
        val payload = "png-of-com.a-0123456789".toByteArray()
        val zip = PicpZipFixtures.buildStored(
            mapOf("repo-main/themes/dark/icons/com.a/1.png" to payload)
        )
        val at = PicpZipFixtures.indexOf(zip, payload)
        assertTrue("payload not found in archive", at > 0)
        val damaged = zip.copyOf()
        damaged[at + 3] = (damaged[at + 3].toInt() xor 0x40).toByte()
        val staging = tmp.newFolder("staging")

        expectInvalid { PicpZipScanner.extractToStaging(zipOfBytes(damaged), staging) }
        assertFalse(File(staging, "com.a.png").exists()) // 坏图不留在暂存目录里
    }

    @Test
    fun `cancel between entries raises CancelledException before the index is written`() {
        val zip = PicpZipFixtures.write(
            tmp.newFile("payload.zip"),
            PicpZipFixtures.build(pkgs = listOf("com.a", "com.b"))
        )
        val staging = tmp.newFolder("staging")

        // 第二个条目之前取消
        val seen = intArrayOf(0)
        try {
            PicpZipScanner.extractToStaging(zip, staging) { seen[0]++ >= 1 }
            fail("extract should have been cancelled")
        } catch (_: PicpZipScanner.CancelledException) {
            // expected
        }
        // 中止态：索引只在全部解完之后才写，所以此刻必然没有索引
        // （调用方负责清暂存目录）。此前这里是一句空断言。
        assertFalse(File(staging, "index.txt").exists())
    }

    @Test
    fun `cancel in the middle of a large entry stops promptly`() {
        val big = ByteArray(512 * 1024) { (it % 251).toByte() }
        val zip = zipOfBytes(
            PicpZipFixtures.buildStored(mapOf("repo-main/themes/dark/icons/com.a/1.png" to big))
        )
        val staging = tmp.newFolder("staging")

        val polls = intArrayOf(0)
        try {
            // 轮询点：条目循环一次，之后每读一块一次。第三次之后才取消 = 已进入数据区
            PicpZipScanner.extractToStaging(zip, staging) { ++polls[0] > 3 }
            fail("extract should have been cancelled")
        } catch (_: PicpZipScanner.CancelledException) {
            // expected
        }
        assertFalse(File(staging, "index.txt").exists())
    }

    @Test
    fun `stale staging content is cleared before rebuild`() {
        val zip = zipOfEntries(listOf("repo-main/themes/dark/icons/com.a/1.png" to "pngA"))
        val staging = tmp.newFolder("staging")
        File(staging, "stale.png").writeText("leftover") // 上次失败残留

        PicpZipScanner.extractToStaging(zip, staging)

        assertFalse(File(staging, "stale.png").exists())
        assertEquals("pngA", File(staging, "com.a.png").readText())
    }

    @Test
    fun `path components in the package dir cannot escape staging`() {
        val zip = zipOfEntries(listOf(
            "r/themes/dark/icons/../1.png" to "x",
            "r/themes/dark/icons/..\\evil/1.png" to "x",
            "r/themes/dark/icons/./1.png" to "x"
        ))
        val staging = tmp.newFolder("staging")

        val count = PicpZipScanner.extractToStaging(zip, staging)

        assertEquals(0, count)
        assertEquals(listOf("index.txt"), staging.listFiles()!!.map { it.name })
        assertFalse(File(tmp.root, "evil.png").exists())
        assertFalse(File(tmp.root, "1.png").exists())
    }

    private fun zipOfBytes(bytes: ByteArray): File =
        PicpZipFixtures.write(tmp.newFile("payload.zip"), bytes)
}
