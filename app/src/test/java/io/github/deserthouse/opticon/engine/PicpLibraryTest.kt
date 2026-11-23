package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PicpLibrary 读路径单测（修复批 F4 / X-06 / X-08）。
 *
 * 验收：在切换的每一步之间被杀后冷启，索引集合恒等于目录里的图标集合，
 * 且至少有完整的一版可用（旧版或新版）。中间态按原语的步骤逐个构造。
 */
class PicpLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: File
    private val icons get() = File(cache, PicpLibrary.ICONS_DIR)
    private val staging get() = FileSnapshotSwitch.stagingOf(cache, PicpLibrary.ICONS_DIR)
    private val prev get() = FileSnapshotSwitch.prevOf(cache, PicpLibrary.ICONS_DIR)

    @Before
    fun setUp() {
        cache = tmp.newFolder("picp")
    }

    private fun write(dir: File, files: Map<String, String>) {
        dir.mkdirs()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
    }

    private fun pngNames(dir: File) =
        dir.list()!!.filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.toSet()

    // ━━ 切换的每一步之间被杀，然后冷启 ━━

    @Test
    fun `cold start after a kill between any two switch steps yields one complete version`() {
        val old = mapOf("a.png" to "old-a", "b.png" to "old-b", "index.txt" to "a\nb")
        val new = mapOf("a.png" to "new-a", "c.png" to "new-c", "index.txt" to "a\nc")

        for (step in 0..4) {
            cache = tmp.newFolder("kill-after-step-$step")
            write(icons, old)      // 旧版在线
            write(staging, new)    // 新版本在暂存目录构建完毕
            when (step) {
                0 -> Unit          // 切换之前
                1 -> Unit          // a 之后：与切换前相同
                2 -> icons.renameTo(prev)                 // b 之后：旧版已让位，新版本未上位
                3 -> {                                   // c 之后：已提交，旧版还没清
                    icons.renameTo(prev)
                    staging.renameTo(icons)
                }
                4 -> {                                   // d 之后：全部完成
                    icons.renameTo(prev)
                    staging.renameTo(icons)
                    prev.deleteRecursively()
                }
            }

            val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

            val expected = if (step <= 2) setOf("a", "b") else setOf("a", "c")
            val why = "killed after step $step"
            assertEquals(why, expected, pngNames(icons))            // 至少有完整的一版可用
            assertEquals(why, expected, loaded.packages)            // 索引集合 = 目录里的图标集合
            assertEquals(
                why,
                if (step <= 2) "old-a" else "new-a",
                File(icons, "a.png").readText()
            )
            // 收敛之后不留中间态
            assertFalse(why, prev.exists())
            assertFalse(why, staging.exists())
            assertEquals(why, 1, cache.listFiles()!!.count { it.name.startsWith("icons") })
        }
    }

    // ━━ 索引来源 ━━

    @Test
    fun `inner index is the source of truth`() {
        write(icons, mapOf("a.png" to "x", "b.png" to "x", "index.txt" to "a\nb"))

        val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

        assertEquals(PicpLibrary.IndexSource.INNER, loaded.source)
        assertEquals(setOf("a", "b"), loaded.packages)
    }

    @Test
    fun `legacy root index is read when the inner index is absent`() {
        // 0.19.6-beta 的布局：索引在 cacheDir 根部
        write(icons, mapOf("a.png" to "x", "b.png" to "x"))
        File(cache, PicpLibrary.LEGACY_ROOT_INDEX).writeText("a\nb")

        val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

        assertEquals(PicpLibrary.IndexSource.LEGACY_ROOT, loaded.source)
        assertEquals(setOf("a", "b"), loaded.packages)
    }

    @Test
    fun `untrusted legacy root index falls back to the directory listing`() {
        // 0.19.6-beta 的根索引换位做到一半被杀：根部索引与图标目录是否对应无从分辨
        write(icons, mapOf("a.png" to "x"))
        File(cache, PicpLibrary.LEGACY_ROOT_INDEX).writeText("ghost")
        File(cache, PicpLibrary.LEGACY_ROOT_INDEX + ".tmp").writeText("a")

        val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

        assertEquals(PicpLibrary.IndexSource.DIRECTORY_LISTING, loaded.source)
        assertEquals(setOf("a"), loaded.packages)
        assertFalse(File(cache, PicpLibrary.LEGACY_ROOT_INDEX).exists())
        assertFalse(File(cache, PicpLibrary.LEGACY_ROOT_INDEX + ".tmp").exists())
    }

    @Test
    fun `missing index falls back to the directory listing`() {
        write(icons, mapOf("a.png" to "x", "c.png" to "x"))

        val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

        assertEquals(PicpLibrary.IndexSource.DIRECTORY_LISTING, loaded.source)
        assertEquals(setOf("a", "c"), loaded.packages)
    }

    @Test
    fun `no library yields none`() {
        val loaded = PicpLibrary.readPackages(cache, clearStaging = true)

        assertEquals(PicpLibrary.IndexSource.NONE, loaded.source)
        assertEquals(emptySet<String>(), loaded.packages)
    }

    // ━━ 读路径的清理边界 ━━

    @Test
    fun `read path clears staging and leftover archives when idle`() {
        write(icons, mapOf("a.png" to "x", "index.txt" to "a"))
        write(staging, mapOf("half.png" to "x"))
        File(cache, PicpLibrary.ARCHIVE).writeText("stale archive")
        File(cache, PicpLibrary.ARCHIVE_TMP).writeText("partial archive")

        PicpLibrary.readPackages(cache, clearStaging = true)

        assertFalse(staging.exists())
        assertFalse(File(cache, PicpLibrary.ARCHIVE).exists())
        assertFalse(File(cache, PicpLibrary.ARCHIVE_TMP).exists())
        assertEquals(setOf("a"), pngNames(icons))
    }

    @Test
    fun `read path leaves staging and archives alone while a sync may be building them`() {
        write(icons, mapOf("a.png" to "x", "index.txt" to "a"))
        write(staging, mapOf("half.png" to "x", "index.txt" to "a\nc"))
        File(cache, PicpLibrary.ARCHIVE_TMP).writeText("downloading")

        val loaded = PicpLibrary.readPackages(cache, clearStaging = false)

        assertEquals(setOf("a"), loaded.packages) // 读到的仍是在线的旧版
        assertTrue(File(staging, "half.png").exists())
        assertEquals("downloading", File(cache, PicpLibrary.ARCHIVE_TMP).readText())
    }
}
