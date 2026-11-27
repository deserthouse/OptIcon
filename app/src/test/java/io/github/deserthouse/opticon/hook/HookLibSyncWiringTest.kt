package io.github.deserthouse.opticon.hook

import io.github.deserthouse.opticon.FakeFilesContext
import io.github.deserthouse.opticon.engine.AnipReleaseChannel
import io.github.deserthouse.opticon.engine.AnipSnapshotBuilder
import io.github.deserthouse.opticon.engine.AnipZipFixtures
import io.github.deserthouse.opticon.engine.SyncGate
import io.github.deserthouse.opticon.engine.SvgRasterizer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * hook 侧自同步的接线（批 UP）：[HookLibSync.syncNow] 把取字节的通道和 SVG 渲染器真的传到了规则库同步里。
 * 这两样传丢了，设备上的表现是「SVG 条目全按读不出处理」或「清单去了别处」，从外面看不出来是哪一环断了。
 * 真实的文件系统、真实的构建与切换，只把网络与渲染换成假的。
 */
class HookLibSyncWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假的取字节通道：清单 + 按地址给的包。 */
    private class FakeAccess(private val bundle: ByteArray, private val tag: String) : AnipReleaseChannel.Access {
        val small = ArrayList<String>()
        val file = ArrayList<String>()

        override fun fetchSmall(url: String, maxBytes: Long): ByteArray? {
            small += url
            val sha = MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
            return """{"schemaVersion":1,"tag":"$tag","timestamp":1,"assetName":"anip-bundle-$tag.zip","downloadUrl":"anip-bundle-$tag.zip","size":${bundle.size},"sha256":"$sha"}""".toByteArray()
        }

        override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
            file += url
            dest.writeBytes(bundle)
            return true
        }
    }

    /** 渲染器交回的东西要像个图片文件（构建器会看文件头），所以用夹具里的最小 PNG 而不是随便几个字节。 */
    private val rendered = AnipZipFixtures.pngOf("rendered")

    /** 官方资源包（没有 icons/ 这一层）：一个位图包 + 一个 SVG 包。 */
    private fun bundle(pkg: String = "com.vector") = AnipZipFixtures.build(
        root = "", iconsDir = false,
        categories = listOf(AnipZipFixtures.Category("app", listOf(
            AnipZipFixtures.Declared("com.bitmap"),
            AnipZipFixtures.Declared(pkg, ext = "svg", format = "svg", png = AnipZipFixtures.svgOf(pkg))
        )))
    )

    @Before
    fun setUp() {
        HookLibSync.forgetDirForTest()
    }

    @After
    fun resetSeams() {
        HookLibSync.releaseAccess = null
        HookLibSync.svgRasterizer = SvgRasterizer
        assertFalse("a test left the sync gate held", SyncGate.isHeld)
    }

    @Test
    fun `hook self-sync renders svg entries with the configured rasterizer`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        HookLibSync.releaseAccess = FakeAccess(bundle(), "abc1234")
        val handed = ArrayList<Pair<String, Int>>()
        HookLibSync.svgRasterizer = AnipSnapshotBuilder.IconRasterizer { svg, max ->
            handed += String(svg, Charsets.UTF_8) to max
            rendered
        }

        val outcome = HookLibSync.syncNow(ctx)

        assertTrue(outcome!!.ok)
        assertEquals(2, outcome.count)
        assertEquals("abc1234", outcome.tag)
        assertEquals(
            listOf("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"72\" height=\"72\"><title>com.vector</title></svg>" to 150),
            handed
        )
        val dir = HookLibSync.dir(ctx)
        assertEquals(File(ctx.cacheDir, "opticon_hook_icons"), dir)
        assertArrayEquals(rendered, File(dir, "com.vector.png").readBytes())
        assertTrue(File(dir, "com.bitmap.png").isFile)
    }

    @Test
    fun `hook self-sync says unchanged the second time and downloads nothing`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val access = FakeAccess(bundle(), "abc1234")
        HookLibSync.releaseAccess = access

        val first = HookLibSync.syncNow(ctx)!!
        val again = HookLibSync.syncNow(ctx)!!

        assertTrue(first.ok)
        assertFalse(first.unchanged)
        assertTrue(again.ok)
        assertTrue(again.unchanged)
        assertEquals("abc1234", again.tag)
        assertEquals("每次同步取一次清单", 2, access.small.size)
        assertEquals(AnipReleaseChannel.MANIFEST_URL, access.small.first())
        assertEquals("包只下载了一次", 1, access.file.size)
    }
}
