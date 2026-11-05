package io.github.deserthouse.opticon.network

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NetworkExecutor.fetchToFileBounded 单测（批 SYNC V4）。
 * JDK 内置 HttpServer 起本地端点，验证字节预算与协作取消三态。
 */
class NetworkExecutorBoundedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @Before
    fun setUp() {
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
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
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
        // 部分 dest 残留由调用方清理（PicpEngine 在 TOO_LARGE 分支 tmp.delete()）
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
}
