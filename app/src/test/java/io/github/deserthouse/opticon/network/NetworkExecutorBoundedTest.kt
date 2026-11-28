package io.github.deserthouse.opticon.network

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a destination that cannot be opened is a storage failure, not a download failure`() {
        // 数据到了、本地建不了文件：磁盘满时下载落盘的失败就是这一种（批 MT M3），不该提示用户查网络
        val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", File(tmp.root, "no-such-dir/out.zip"))

        assertEquals(NetworkExecutor.DownloadOutcome.STORAGE, outcome)
    }

    @Test
    fun `a write that fails midway is a storage failure, not a network failure`() {
        // 磁盘满的典型形态：文件建得起来，写了一半写不进。注入一个一写就抛的输出流
        NetworkExecutor.openOutput = { _ ->
            object : java.io.OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
            }
        }
        try {
            val outcome = NetworkExecutor.fetchToFileBounded("$baseUrl/ok", File(tmp.root, "full.bin"))
            assertEquals(NetworkExecutor.DownloadOutcome.STORAGE, outcome)
        } finally {
            NetworkExecutor.openOutput = { it.outputStream() }
        }
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

    // ━━ fetchBytesBounded：小文件的字节上限（上游发布清单的 64KB）。2026-11-28 盲审：此前零引用 ━━

    /** 在测试服务器上挂一个返回 [bytes] 的端点；[declared] 是响应头里声称的长度（-1 = 不报）。 */
    private fun serve(path: String, bytes: ByteArray, declared: Long = bytes.size.toLong()) {
        server.createContext(path) { ex ->
            ex.sendResponseHeaders(200, declared)
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `a small file exactly at the limit is returned whole`() {
        val body = pattern(64 * 1024)
        serve("/exact", body)

        assertArrayEquals(body, NetworkExecutor.fetchBytesBounded("$baseUrl/exact", 64L * 1024))
    }

    @Test
    fun `a small file one byte over the limit is refused`() {
        serve("/over", pattern(64 * 1024 + 1))

        assertNull(NetworkExecutor.fetchBytesBounded("$baseUrl/over", 64L * 1024))
    }

    @Test
    fun `a small file without a content length is still capped`() {
        // 不报总长的响应（chunked）：预检帮不上忙，只能靠边读边数
        serve("/nolen", pattern(70_000), declared = 0)
        assertNull(NetworkExecutor.fetchBytesBounded("$baseUrl/nolen", 64L * 1024))

        serve("/nolen-ok", pattern(1000), declared = 0)
        assertEquals(1000, NetworkExecutor.fetchBytesBounded("$baseUrl/nolen-ok", 64L * 1024)!!.size)
    }

    @Test
    fun `a non-2xx answer is refused`() {
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }

        assertNull(NetworkExecutor.fetchBytesBounded("$baseUrl/missing", 64L * 1024))
    }

    @Test
    fun `a declared size above the limit is refused without reading the body`() {
        // 预检的意义所在：对端声称 10MB、只发 1KB 就停住。不读正文，才不会被它拖到读超时
        val got = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        Thread {
            got.set(NetworkExecutor.fetchBytesBounded("$baseUrl/stall", 64L * 1024))
            done.countDown()
        }.apply { isDaemon = true }.start()

        assertTrue("没有及时返回：响应头已经说明超限，却还在读正文", done.await(2, TimeUnit.SECONDS))
        assertNull(got.get())
    }
}
