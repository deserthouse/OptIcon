package io.github.deserthouse.opticon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AssetRepository 核心逻辑测试（RB-2）：内容寻址导入幂等 + 孤儿判定（引用/宽限/legacy）。
 * File 目录参数化（JVM 纯净）；Context 包装层由设备轮覆盖。
 */
class AssetRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder()

    // ━━ 导入幂等 ━━

    @Test
    fun `import is idempotent for identical bytes`() {
        val d = dir()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val r1 = AssetRepository.importInto(d, bytes)
        val r2 = AssetRepository.importInto(d, bytes)
        assertNotNull(r1)
        assertEquals(r1!!.path, r2!!.path) // 同内容同路径
        assertEquals(1, d.listFiles { f -> f.name.endsWith(".png") }!!.size) // 不产生副本
        assertEquals(bytes.size.toLong(), File(r1.path).length())
    }

    @Test
    fun `different bytes never overwrite existing asset`() {
        val d = dir()
        val a = AssetRepository.importInto(d, byteArrayOf(1, 1))!!
        val b = AssetRepository.importInto(d, byteArrayOf(2, 2))!!
        assertTrue(a.path != b.path)
        assertEquals(2, d.listFiles { f -> f.name.endsWith(".png") }!!.size)
    }

    // ━━ 孤儿判定 ━━

    @Test
    fun `referenced assets are never orphans`() {
        val d = dir()
        val keep = AssetRepository.importInto(d, byteArrayOf(9, 9, 9))!!
        setOld(File(keep.path))
        val drop = AssetRepository.importInto(d, byteArrayOf(8, 8, 8))!!
        setOld(File(drop.path))
        val orphans = AssetRepository.collectOrphans(d, referenced = setOf(keep.path))
        assertEquals(listOf(File(drop.path)), orphans) // 引用者保留，未引用者回收
    }

    @Test
    fun `fresh files within grace period are kept`() {
        val d = dir()
        val fresh = AssetRepository.importInto(d, byteArrayOf(7, 7))!! // mtime=now
        val orphans = AssetRepository.collectOrphans(d, referenced = emptySet())
        assertTrue(orphans.isEmpty()) // 宽限期内不删（活跃草稿保护）
        assertNotNull(fresh)
    }

    @Test
    fun `legacy pkg upload files follow the same rule`() {
        val d = dir()
        val legacyKept = File(d, "com.a.upload.png").apply { writeBytes(byteArrayOf(4)); setLastModified(0) }
        val legacyDrop = File(d, "com.b.upload.png").apply { writeBytes(byteArrayOf(5)); setLastModified(0) }
        val orphans = AssetRepository.collectOrphans(d, referenced = setOf(legacyKept.absolutePath))
        assertEquals(listOf(legacyDrop), orphans)
    }

    private fun setOld(f: File) {
        f.setLastModified(System.currentTimeMillis() - 48 * 3600_000L)
    }
}
