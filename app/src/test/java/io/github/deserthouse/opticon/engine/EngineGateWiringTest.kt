package io.github.deserthouse.opticon.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.deserthouse.opticon.FakeFilesContext
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.util.InMemoryPrefs
import io.github.deserthouse.opticon.util.PreferenceManager
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.sun.net.httpserver.HttpServer

/**
 * 引擎级互斥的接线测试（修复批 X-22；第二轮审查指出此前只测了 SyncGate 这个原子布尔包装，
 * 两个引擎入口的占用/拒绝/释放没有任何测试执行到——对应的三个变异全部漏网）。
 *
 * 跑的是未改动的 PicpEngine.syncIndex 与 SubscriptionManager.sync：真实工作线程、真实的锁，
 * 只把 Context 换成只给目录和文案的假体。不发任何真实网络请求：
 *  - 应用图标库同步指向本机一个没人监听的端口（连接被拒，很快失败）；
 *  - 规则库同步取字节的通道换成不联网的实现（批 UP 之前这里是「连通性探测」的接缝）。
 */
class EngineGateWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val deadUrl = "http://127.0.0.1:1/never-reachable.zip"

    /** 不联网的取字节通道：清单取不到、什么都下不了。 */
    private val offline = object : AnipReleaseChannel.Access {
        override fun fetchSmall(url: String, maxBytes: Long): ByteArray? = null
        override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean = false
    }

    @Before
    fun setUp() {
        PreferenceManager.initForTest(InMemoryPrefs())
        SubscriptionManager.releaseAccess = offline
        // android.jar 的桩里 getString 永远返回 null：文案经接缝给占位值
        SubscriptionManager.strings = { _, id, _ -> "#$id" }
        PicpEngine.strings = { _, id, _ -> "#$id" }
    }

    @After
    fun gateMustBeFree() {
        assertFalse("a test left the sync gate held", SyncGate.isHeld)
        SubscriptionManager.anipManifestOverride = { PreferenceManager.anipManifestOverride() }
        SubscriptionManager.svgRasterizer = SvgRasterizer
    }

    private fun <T> CompletableDeferred<T>.awaitWithin(ms: Long): T =
        runBlocking { withTimeout(ms) { await() } }

    private fun <T> await(d: CompletableDeferred<T>, ms: Long = 30_000): T = d.awaitWithin(ms)

    // ━━ 应用图标库同步 ━━

    @Test
    fun `icon sync - a second launch while the first is running is refused and the first is unaffected`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val first = CompletableDeferred<PicpEngine.SyncEnd>()
        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { first.complete(it) })

        // 第一次还在跑（死端口：两次尝试 + 间隔）。第二次发起在调用线程上立即得到答复
        val second = CompletableDeferred<PicpEngine.SyncEnd>()
        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { second.complete(it) })
        val secondEnd = second.awaitWithin(5_000) // 被拒的发起必须立刻答复
        assertTrue("second launch should be Busy, was $secondEnd", secondEnd is PicpEngine.SyncEnd.Busy)

        // 第一次不受影响：如实报失败（下载失败），不是「已有同步在进行」
        val firstEnd = await(first)
        assertTrue("first launch should finish on its own, was $firstEnd", firstEnd is PicpEngine.SyncEnd.Failed)
        assertFalse(SyncGate.isHeld)
    }

    @Test
    fun `icon sync - the gate is reusable after a finished sync`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val first = CompletableDeferred<PicpEngine.SyncEnd>()
        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { first.complete(it) })
        await(first)

        val next = CompletableDeferred<PicpEngine.SyncEnd>()
        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { next.complete(it) })
        assertFalse("a finished sync must release the gate", await(next) is PicpEngine.SyncEnd.Busy)
    }

    @Test
    fun `icon sync - an Error escaping the sync thread still releases the gate and reports`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        // 结果翻译那一步抛 Error（编排层只接 Exception）——此前线程体没有 finally，
        // 这种情况下锁永远不放、回调不发（第二轮审查 N-03，探针实测）
        var fired = false
        PicpEngine.strings = { _, id, _ ->
            if (id == R.string.picp_fail_download && !fired) {
                fired = true
                throw AssertionError("simulated Error inside the sync thread")
            }
            "#$id"
        }
        val end = CompletableDeferred<PicpEngine.SyncEnd>()

        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { end.complete(it) })

        val result = await(end)
        assertTrue("the callback must still fire with a failure, was $result", result is PicpEngine.SyncEnd.Failed)
        assertFalse(SyncGate.isHeld)
        // 之后的同步不再被判「已有同步在进行」
        PicpEngine.strings = { _, id, _ -> "#$id" }
        // 之后的同步不再被判「已有同步在进行」
        val next = CompletableDeferred<PicpEngine.SyncEnd>()
        PicpEngine.syncIndex(ctx, deadUrl, onProgress = null, onResult = { next.complete(it) })
        assertFalse(await(next) is PicpEngine.SyncEnd.Busy)
    }

    // ━━ 规则库同步 ━━

    @Test
    fun `rule sync - a second launch while the first holds the gate is refused, then the gate is released`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        // 把同步卡在「取上游发布清单」这一步（同步体的第一处联网动作）
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? {
                probeEntered.countDown()
                releaseProbe.await(20, TimeUnit.SECONDS)
                return null
            }

            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean = false
        }
        val first = CompletableDeferred<SubscriptionManager.SyncResult>()
        thread { SubscriptionManager.sync(ctx, onResult = { first.complete(it) }) }
        assertTrue("first sync never reached the network", probeEntered.await(10, TimeUnit.SECONDS))

        val second = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, onResult = { second.complete(it) })
        val secondResult = second.awaitWithin(5_000) // 被拒的发起必须立刻答复
        assertTrue("second launch should be busy", secondResult.busy)

        releaseProbe.countDown()
        val firstResult = await(first)
        assertFalse(firstResult.busy)
        assertFalse(firstResult.success) // 清单取不到、整库也下不了 → 如实报「下载失败」，不是「数据无效」
        assertEquals("#${R.string.sync_download_failed}", firstResult.errorMessage)
        assertFalse(SyncGate.isHeld)

        // 门已经放掉：再发起一次不会被拒
        SubscriptionManager.releaseAccess = offline
        val third = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, onResult = { third.complete(it) })
        assertFalse("the gate must be reusable after a finished sync", await(third).busy)
    }

    // ━━ 取消（修复批 X-19，引擎外壳一侧） ━━

    @Test
    fun `icon sync - cancel interrupts the download of a stalled source`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        // 停住的源：声称 10MB，只发 1KB 就不再发——两个数据块之间没有轮询点，只能靠掐断连接
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stall") { ex ->
            try {
                ex.sendResponseHeaders(200, 10L * 1024 * 1024)
                ex.responseBody.write(ByteArray(1024))
                ex.responseBody.flush()
                stalled.countDown()
                release.await(15, TimeUnit.SECONDS)
            } catch (_: IOException) {
                // 客户端掐断连接
            } finally {
                ex.close()
            }
        }
        server.start()
        try {
            val end = CompletableDeferred<PicpEngine.SyncEnd>()
            PicpEngine.syncIndex(ctx, "http://127.0.0.1:${server.address.port}/stall", onProgress = null, onResult = { end.complete(it) })
            assertTrue("server never stalled", stalled.await(5, TimeUnit.SECONDS))
            // 等客户端把拿到的那一块落盘 = 已经进入数据区、正卡在读取上
            val partial = File(File(ctx.filesDir, "picp"), PicpLibrary.ARCHIVE_TMP)
            val deadline = System.currentTimeMillis() + 5_000
            while (partial.length() < 1024L && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals("client should have the first chunk on disk", 1024L, partial.length())

            val askedAt = System.currentTimeMillis()
            PicpEngine.syncCancel()   // 走的是界面上那个按钮调用的同一个入口

            val result = end.awaitWithin(5_000)
            assertTrue("expected Cancelled, was $result", result is PicpEngine.SyncEnd.Cancelled)
            assertTrue("cancel must not wait for the read timeout", System.currentTimeMillis() - askedAt < 3_000)
            assertFalse(SyncGate.isHeld)
            assertFalse("the partial archive must be cleaned up before the callback", partial.exists())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    // ━━ 待确认格式的源（修复批 X-02） ━━

    @Test
    fun `rule sync - a source whose format is not chosen is refused before any network use`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val source = PreferenceManager.addCustomSource("mystery", deadUrl, "")   // 默认格式：待确认
        PreferenceManager.setActiveAniaSourceId(source.id)
        var requests = 0
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? { requests++; return null }
            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean { requests++; return false }
        }

        val result = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, onResult = { result.complete(it) })

        val r = result.awaitWithin(5_000)
        assertFalse("being refused is an ordinary failure, not busy", r.busy)
        assertFalse(r.success)
        assertEquals("#${R.string.sync_format_unspecified}", r.errorMessage)
        assertEquals("no network use before the format is chosen", 0, requests)
    }

    @Test
    fun `rule sync - an exception in the body is reported as a failure instead of escaping to the caller`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        // 内置源地址：清单取不到之后会去下整库。让下载抛异常，模拟同步体里的意外（磁盘写失败之类）。
        // （取清单那一步自己兜异常、当作官方通道用不了，所以不能拿它来模拟。）
        // 此前这里不兜：异常一路逃到调用方的协程里，应用直接崩溃。
        var delivered = 0
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? = null
            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean =
                throw IllegalStateException("simulated failure inside the sync body")
        }

        val result = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, sourceUrl = AnipSync.BASE, onResult = { delivered++; result.complete(it) })

        val r = result.awaitWithin(5_000)
        assertFalse("an unexpected failure is an ordinary failure, not busy", r.busy)
        assertFalse(r.success)
        assertEquals("#${R.string.sync_failed}", r.errorMessage)
        assertEquals("the result is delivered exactly once", 1, delivered)
        assertFalse(SyncGate.isHeld)
    }

    @Test
    fun `rule sync - an Error escaping the body still releases the gate`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? =
                throw AssertionError("simulated Error in sync body")
            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean = false
        }

        try {
            SubscriptionManager.sync(ctx, onProgress = null, onResult = { fail("no result expected") })
            fail("the Error should propagate")
        } catch (_: AssertionError) {
            // expected
        }
        assertFalse(SyncGate.isHeld)
    }

    // ━━ 官方发布通道（批 UP） ━━

    @Test
    fun `rule sync - when upstream has nothing new the result says so and the follow-up is skipped`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val bundle = AnipZipFixtures.build(
            root = "", iconsDir = false,
            categories = listOf(
                AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"), AnipZipFixtures.Declared("com.b")))
            )
        )
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
        var downloads = 0
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? =
                """{"schemaVersion":1,"tag":"abc1234","timestamp":1,"assetName":"anip-bundle-abc1234.zip","downloadUrl":"anip-bundle-abc1234.zip","size":${bundle.size},"sha256":"$sha"}""".toByteArray()

            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
                downloads++
                dest.writeBytes(bundle)
                return true
            }
        }

        val first = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, sourceUrl = AnipSync.BASE, onResult = { first.complete(it) })
        val f = first.awaitWithin(15_000)
        assertTrue(f.success)
        assertTrue("the first sync brings a snapshot in", f.wasChanged)
        assertEquals(2, f.iconCount)
        assertEquals("abc1234", f.tag)
        assertEquals("the release time reaches the caller for the status line", 1L, f.versionTime)

        val second = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, sourceUrl = AnipSync.BASE, onResult = { second.complete(it) })
        val s = second.awaitWithin(15_000)
        assertTrue(s.success)
        assertFalse("upstream has nothing new: the follow-up must be skipped", s.wasChanged)
        assertEquals(2, s.iconCount)
        assertEquals("abc1234", s.tag)
        assertEquals(emptyList<String>(), s.revokedPackages)
        assertEquals("the bundle is downloaded only once", 1, downloads)
        assertFalse(SyncGate.isHeld)
    }

    @Test
    fun `rule sync - the manifest address follows the debug override`() {
        // 设备验证套件靠它把内置源指到本机测试源（仅调试包）；清单从哪里取，包就按哪里解析
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val bundle = AnipZipFixtures.build(
            root = "", iconsDir = false,
            categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))))
        )
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
        val local = "http://127.0.0.1:28765/anip-release.json"
        val manifests = ArrayList<String>()
        val bundles = ArrayList<String>()
        SubscriptionManager.anipManifestOverride = { local }
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? {
                manifests += url
                return """{"schemaVersion":1,"tag":"abc1234","timestamp":1,"assetName":"anip-bundle-abc1234.zip","downloadUrl":"anip-bundle-abc1234.zip","size":${bundle.size},"sha256":"$sha"}""".toByteArray()
            }

            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
                bundles += url
                dest.writeBytes(bundle)
                return true
            }
        }

        val done = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, sourceUrl = AnipSync.BASE, onResult = { done.complete(it) })
        val r = done.awaitWithin(15_000)

        assertTrue(r.success)
        assertEquals(1, r.iconCount)
        assertEquals("abc1234", r.tag)
        assertEquals(listOf(local), manifests)
        assertEquals(listOf("http://127.0.0.1:28765/anip-bundle-abc1234.zip"), bundles)
    }

    @Test
    fun `rule sync - svg entries are rendered by the configured rasterizer`() {
        // 批 UP：订阅管理器要把渲染器传进规则库同步。传丢了的话 SVG 条目全部按「读不出」处理，
        // 设备上只看得到「少了几个图标」，看不出是哪一环断了
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val rendered = AnipZipFixtures.pngOf("rendered")
        val bundle = AnipZipFixtures.build(
            root = "", iconsDir = false,
            categories = listOf(AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.bitmap"),
                AnipZipFixtures.Declared("com.vector", ext = "svg", format = "svg", png = AnipZipFixtures.svgOf("com.vector"))
            )))
        )
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
        SubscriptionManager.releaseAccess = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? =
                """{"schemaVersion":1,"tag":"abc1234","timestamp":1,"assetName":"anip-bundle-abc1234.zip","downloadUrl":"anip-bundle-abc1234.zip","size":${bundle.size},"sha256":"$sha"}""".toByteArray()

            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
                dest.writeBytes(bundle)
                return true
            }
        }
        val handed = ArrayList<Pair<String, Int>>()
        SubscriptionManager.svgRasterizer = AnipSnapshotBuilder.IconRasterizer { svg, max ->
            handed += String(svg, Charsets.UTF_8) to max
            rendered
        }

        val done = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, sourceUrl = AnipSync.BASE, onResult = { done.complete(it) })
        val r = done.awaitWithin(15_000)

        assertTrue(r.success)
        assertEquals(2, r.iconCount)
        assertEquals(
            listOf("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"72\" height=\"72\"><title>com.vector</title></svg>" to 150),
            handed
        )
        val dir = File(ctx.filesDir, IconLibEngine.CACHE_DIR_ACCESSIBLE)
        assertArrayEquals(rendered, File(dir, "com.vector.png").readBytes())
        assertTrue(File(dir, "com.bitmap.png").isFile)
    }
}
