package io.github.deserthouse.opticon.network

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * NetworkExecutor.fetchToFileBounded 单测（批 SYNC V4）。
 * JDK 内置 HttpServer 起本地端点，验证字节预算与协作取消三态。
 *
 * 修复批（X-19）追加：可中断的取消信号——源停住不发数据时，取消不必等读超时。
 */
class NetworkExecutorBoundedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var baseUrl = ""

    /** /stall 发完响应头和一小段数据后停住，直到测试放行。 */
    private lateinit var stallSent: CountDownLatch
    private lateinit var stallRelease: CountDownLatch

    @Before
    fun setUp() {
        stallSent = CountDownLatch(1)
        stallRelease = CountDownLatch(1)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 正常载荷：16KB 可读字节
        server.createContext("/ok") { ex ->
            val body = ByteArray(16 * 1024) { (it % 251).toByte() }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        // 无 Content-Length 的 chunked 载荷（服务器不报总长）
        server.createContext("/chunked") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(ByteArray(16 * 1024)) }
        }
        // 声称有 10MB，只发 1KB 就停住：两个数据块之间没有轮询点
        server.createContext("/stall") { ex ->
            try {
                ex.sendResponseHeaders(200, 10L * 1024 * 1024)
                ex.responseBody.write(ByteArray(1024))
                ex.responseBody.flush()
                stallSent.countDown()
                stallRelease.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) {
                // 客户端掐断连接
            } finally {
                ex.close()
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        stallRelease.countDown()
        server.stop(0)
    }

    @Test
    fun `ok download writes full body`() {
        val dest = File(tmp.root, "ok.bin")
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", dest)
        assertEquals(NetworkExecutor.DownloadOutcome.OK, outcome)
        assertEquals(16L * 1024, dest.length())
    }

    @Test
    fun `byte budget breach aborts with TOO_LARGE`() {
        val dest = File(tmp.root, "big.bin")
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", dest, maxBytes = 4 * 1024)
        assertEquals(NetworkExecutor.DownloadOutcome.TOO_LARGE, outcome)
        // 部分 dest 残留由调用方清理（编排层在 finally 里统一删）
        assertTrue(dest.length() <= 4L * 1024)
    }

    @Test
    fun `no more than the byte budget ever reaches the disk`() {
        // 预算 12KB、载荷 16KB：第一块落盘，下一块在写入之前就被拦下
        val dest = File(tmp.root, "capped.bin")
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", dest, maxBytes = 12 * 1024)

        assertEquals(NetworkExecutor.DownloadOutcome.TOO_LARGE, outcome)
        assertTrue("something should have been written before the breach", dest.length() > 0)
        assertTrue("on-disk bytes must not exceed the budget", dest.length() <= 12L * 1024)
        assertTrue("the full body must not have landed", dest.length() < 16L * 1024)
    }

    @Test
    fun `cancel flag between chunks aborts with CANCELLED`() {
        val dest = File(tmp.root, "cancel.bin")
        val cancelled = AtomicBoolean(false)
        val polls = AtomicLong(0)
        // 首个 chunk 后置位取消
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", dest, shouldCancel = {
            polls.incrementAndGet() > 1 || cancelled.getAndSet(true)
        })
        assertEquals(NetworkExecutor.DownloadOutcome.CANCELLED, outcome)
        assertFalse(dest.length() == 16L * 1024)
    }

    @Test
    fun `chunked response without content-length still completes ok`() {
        // U01 相关注记：无 Content-Length 不妨碍完成后 OK（下载侧不显示假百分比）
        val dest = File(tmp.root, "chunked.bin")
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/chunked", dest)
        assertEquals(NetworkExecutor.DownloadOutcome.OK, outcome)
        assertEquals(16L * 1024, dest.length())
    }

    // ━━ 修复批 X-19：取消直达网络调用 ━━

    @Test
    fun `cancelling a stalled source returns cancelled within two seconds`() {
        val dest = File(tmp.root, "stall.bin")
        val token = NetworkExecutor.CancelToken()
        val outcome = AtomicReference<NetworkExecutor.DownloadOutcome>()
        val done = CountDownLatch(1)
        Thread {
            outcome.set(NetworkExecutor.fetchToFileCancellable("$baseUrl/stall", dest, token))
            done.countDown()
        }.apply { isDaemon = true }.start()

        // 等服务端确实停住，并且客户端已把拿到的那一块落盘
        // （= 已进入数据区、正卡在 read 上，而不是还没开始）
        assertTrue("server never stalled", stallSent.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (dest.length() < 1024L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("client should have the first chunk on disk", 1024L, dest.length())

        token.cancel()

        assertTrue("cancel did not return in time", done.await(2, TimeUnit.SECONDS))
        assertEquals(NetworkExecutor.DownloadOutcome.CANCELLED, outcome.get())
    }

    @Test
    fun `a token cancelled before the call starts never downloads`() {
        val dest = File(tmp.root, "precancel.bin")
        val token = NetworkExecutor.CancelToken()
        token.cancel()

        val outcome = NetworkExecutor.fetchToFileCancellable("$baseUrl/ok", dest, token)

        assertEquals(NetworkExecutor.DownloadOutcome.CANCELLED, outcome)
        assertFalse(dest.length() == 16L * 1024)
    }

    @Test
    fun `an ordinary network failure is not misreported as cancel`() {
        val dest = File(tmp.root, "refused.bin")
        val token = NetworkExecutor.CancelToken()

        // 端口 1 上没有监听：连接被拒——属于网络故障，不是取消
        val outcome = NetworkExecutor.fetchToFileCancellable("http://127.0.0.1:1/x", dest, token)

        assertEquals(NetworkExecutor.DownloadOutcome.FAILED, outcome)
        assertFalse(token.isCancelled)
    }
}
