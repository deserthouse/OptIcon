package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.FakeFilesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 图标库同步之后的预取（批 MT M4）：这条线程上没有调用方兜底，查询或回调抛出异常时线程会带着异常结束、
 * onDone 永远不来——界面停在「同步中」。这里钉死：任何一个环节出异常，onDone 照样送达，异常不逃出线程。
 */
class PicpPrefetchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var uncaught: Throwable? = null

    @Before
    fun captureUncaught() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        uncaught = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e }
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("onDone was never delivered", latch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun `a throwing progress callback does not stop onDone`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val done = CountDownLatch(1)
        var delivered = -1

        PicpEngine.prefetchForInstalled(
            ctx, listOf("com.a", "com.b"),
            onProgress = { _, _ -> throw IllegalStateException("progress callback broke") },
            onDone = { delivered = it; done.countDown() }
        )

        await(done)
        assertEquals(0, delivered)
        assertNull(uncaught)
    }

    @Test
    fun `a throwing done callback does not escape the thread`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val called = CountDownLatch(1)

        PicpEngine.prefetchForInstalled(
            ctx, listOf("com.a"),
            onDone = { called.countDown(); throw IllegalStateException("done callback broke") }
        )

        await(called)
        // 回调之后的收尾在同一轮里完成；给它一点时间把可能逃出的异常交到默认处理器
        Thread.sleep(300)
        assertNull(uncaught)
    }

    @Test
    fun `a failing lookup still delivers onDone`() {
        val ctx = FakeFilesContext(tmp.newFolder("files"))
        val done = CountDownLatch(1)
        var delivered = -1
        // 遍历本身抛异常：模拟查询图标索引时的意外
        val broken = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = throw IllegalStateException("lookup broke")
        }

        PicpEngine.prefetchForInstalled(ctx, broken, onDone = { delivered = it; done.countDown() })

        await(done)
        assertEquals(0, delivered)
        assertNull(uncaught)
    }
}
