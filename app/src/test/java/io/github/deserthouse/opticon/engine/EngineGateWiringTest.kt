package io.github.deserthouse.opticon.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
 *  - 规则库同步的连通性探测换成可控制的 lambda。
 */
class EngineGateWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val deadUrl = "http://127.0.0.1:1/never-reachable.zip"

    @Before
    fun setUp() {
        PreferenceManager.initForTest(InMemoryPrefs())
        SubscriptionManager.connectivityProbe = { true }
        // android.jar 的桩里 getString 永远返回 null：文案经接缝给占位值
        SubscriptionManager.strings = { _, id, _ -> "#$id" }
        PicpEngine.strings = { _, id, _ -> "#$id" }
    }

    @After
    fun gateMustBeFree() {
        assertFalse("a test left the sync gate held", SyncGate.isHeld)
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
        SubscriptionManager.connectivityProbe = {
            probeEntered.countDown()
            releaseProbe.await(20, TimeUnit.SECONDS)
            false
        }
        val first = CompletableDeferred<SubscriptionManager.SyncResult>()
        thread { SubscriptionManager.sync(ctx, onResult = { first.complete(it) }) }
        assertTrue("first sync never reached the probe", probeEntered.await(10, TimeUnit.SECONDS))

        val second = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, onResult = { second.complete(it) })
        val secondResult = second.awaitWithin(5_000) // 被拒的发起必须立刻答复
        assertTrue("second launch should be busy", secondResult.busy)

        releaseProbe.countDown()
        val firstResult = await(first)
        assertFalse(firstResult.busy)
        assertFalse(firstResult.success) // 探测返回 false → 如实报「网络不可用」
        assertFalse(SyncGate.isHeld)

        // 门已经放掉：再发起一次不会被拒
        SubscriptionManager.connectivityProbe = { false }
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
        var probes = 0
        SubscriptionManager.connectivityProbe = { probes++; true }

        val result = CompletableDeferred<SubscriptionManager.SyncResult>()
        SubscriptionManager.sync(ctx, onResult = { result.complete(it) })

        val r = result.awaitWithin(5_000)
        assertFalse("being refused is an ordinary failure, not busy", r.busy)
        assertFalse(r.success)
        assertEquals("#${R.string.sync_format_unspecified}", r.errorMessage)
        assertEquals("no network use before the format is chosen", 0, probes)
    }

    @Test
    fun `rule sync - an Error escaping the body still releases the gate`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        SubscriptionManager.connectivityProbe = { throw AssertionError("simulated Error in sync body") }

        try {
            SubscriptionManager.sync(ctx, onProgress = null, onResult = { fail("no result expected") })
            fail("the Error should propagate")
        } catch (_: AssertionError) {
            // expected
        }
        assertFalse(SyncGate.isHeld)
    }
}
