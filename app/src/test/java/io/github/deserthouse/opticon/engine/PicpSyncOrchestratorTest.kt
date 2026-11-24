package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PicpSyncOrchestrator 单测（修复批：把上一轮审查用的探针场景转成回归测试）。
 *
 * 修复批之前这段流程内联在引擎里、零测试。这里钉死的契约：
 *  - 除成功外的任何结果都不触碰现有图标库（逐字节比对）；
 *  - 返回之后盘上没有暂存目录、没有归档（清理先于回调，X-08）；
 *  - 截断的归档判无效（X-16）；取消在中途任何阶段都如实报告为「已取消」。
 */
class PicpSyncOrchestratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: File
    private val icons get() = File(cache, "icons")

    @Before
    fun setUp() {
        cache = tmp.newFolder("picp")
    }

    /** 一个已在线的旧版本：带目录内索引。 */
    private fun seedOld(vararg pkgs: String) {
        icons.mkdirs()
        pkgs.forEach { File(icons, "$it.png").writeBytes("old-$it".toByteArray()) }
        File(icons, "index.txt").writeText(pkgs.joinToString("\n"))
    }

    /**
     * 目录下所有文件的快照（相对路径 → 内容），用于「逐字节不变」断言。
     * 内容以 ISO-8859-1 解码成字符串——逐字节保真，且按值比较
     * （ByteArray 的 equals 是引用比较，直接放进 Map 永远比不相等）。
     */
    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }
            .associate {
                it.relativeTo(dir).path.replace('\\', '/') to
                    String(it.readBytes(), Charsets.ISO_8859_1)
            }

    private fun assertNoResidue() {
        assertFalse("staging left behind", FileSnapshotSwitch.stagingOf(cache, "icons").exists())
        assertFalse("prev left behind", FileSnapshotSwitch.prevOf(cache, "icons").exists())
        assertFalse("archive left behind", File(cache, PicpLibrary.ARCHIVE).exists())
        assertFalse("partial archive left behind", File(cache, PicpLibrary.ARCHIVE_TMP).exists())
    }

    private class Step(val payload: ByteArray?, val result: PicpSyncOrchestrator.FetchResult)

    /** 脚本化下载器：按调用次序给出各次的载荷与结果；载荷非空就原样写进目标文件。 */
    private class ScriptedDownloader(private val script: List<Step>) : PicpSyncOrchestrator.Downloader {
        var calls = 0

        override fun download(
            dest: File,
            onProgress: (downloaded: Long, total: Long) -> Unit
        ): PicpSyncOrchestrator.FetchResult {
            val step = script[calls]
            calls++
            step.payload?.let {
                dest.writeBytes(it)
                onProgress(it.size.toLong(), it.size.toLong())
            }
            return step.result
        }
    }

    private fun delivering(vararg payloads: ByteArray) = ScriptedDownloader(
        payloads.map { Step(it, PicpSyncOrchestrator.FetchResult.OK) }
    )

    private val goodZip = PicpZipFixtures.build(pkgs = listOf("com.a", "com.c"))

    private fun expectOutcome(expected: PicpSyncOrchestrator.Outcome, actual: PicpSyncOrchestrator.Outcome) {
        assertEquals(expected, actual)
    }

    // ━━ 成功路径 ━━

    @Test
    fun `normal update switches the library and leaves no residue`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = delivering(goodZip)
        val stages = mutableListOf<PicpSyncOrchestrator.Stage>()

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, onStage = { stages += it })

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertEquals(1, dl.calls)
        // 阳性对照：快照比对照样能分辨「变了」——下面各条「快照不变」的断言才有意义
        assertNotEquals(before, snapshot(icons))
        // 新版本整体上线：com.b 不在新版本里 → 物理消失；索引随目录走
        assertEquals(
            setOf("com.a.png", "com.c.png", "index.txt"),
            icons.listFiles()!!.map { it.name }.toSet()
        )
        assertEquals("png-of-com.c", File(icons, "com.c.png").readText())
        assertEquals(setOf("com.a", "com.c"), PicpLibrary.readPackages(cache, clearStaging = false).packages)
        assertNoResidue()
        // 进度节点按顺序上报
        val kinds = stages.map { it::class }
        assertEquals(
            listOf(
                PicpSyncOrchestrator.Stage.Downloading::class,
                PicpSyncOrchestrator.Stage.Bytes::class,
                PicpSyncOrchestrator.Stage.Extracting::class
            ),
            kinds
        )
    }

    @Test
    fun `first install succeeds with no live dir`() {
        val outcome = PicpSyncOrchestrator.run(cache, delivering(goodZip), cancelled = { false })

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertTrue(File(icons, "com.a.png").exists())
        assertNoResidue()
    }

    @Test
    fun `invalid archive on first install leaves nothing behind`() {
        val outcome = PicpSyncOrchestrator.run(
            cache,
            delivering("<html>Service Unavailable</html>".toByteArray(), "still not a zip".toByteArray()),
            cancelled = { false },
            pauseBetweenAttempts = {}
        )

        assertTrue(outcome is PicpSyncOrchestrator.Outcome.Invalid)
        assertFalse("no library must be created", icons.exists())
        assertNoResidue()
    }

    // ━━ 坏载荷：如实失败，旧库逐字节不变 ━━

    @Test
    fun `empty archive keeps the old library byte for byte`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val readmeOnly = PicpZipFixtures.build(pkgs = emptyList(), extra = mapOf("repo-main/README.md" to "x".toByteArray()))
        val dl = delivering(readmeOnly)

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false })

        expectOutcome(PicpSyncOrchestrator.Outcome.Empty, outcome)
        assertEquals("empty is deterministic — no retry", 1, dl.calls)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `plain text body is invalid and keeps the old library`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = delivering("<html>Service Unavailable</html>".toByteArray(), "<html>again</html>".toByteArray())

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        // 详情会原样进状态行（"PICP archive invalid: %1$s"）：不能再带一层 "invalid archive:" 前缀
        assertEquals("truncated or not a zip archive", (outcome as PicpSyncOrchestrator.Outcome.Invalid).detail)
        assertEquals(2, dl.calls)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `truncated archive is invalid and keeps the old library`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val cut = PicpZipFixtures.build(pkgs = listOf("com.a", "com.c")).let { it.copyOf(PicpZipFixtures.centralDirectoryOffset(it)) }
        val dl = delivering(cut, cut)

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        assertEquals("truncated or not a zip archive", (outcome as PicpSyncOrchestrator.Outcome.Invalid).detail)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `download failure keeps the old library and is reported as such`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = ScriptedDownloader(
            List(2) { Step(null, PicpSyncOrchestrator.FetchResult.FAILED) }
        )

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        expectOutcome(PicpSyncOrchestrator.Outcome.DownloadFailed, outcome)
        assertEquals(2, dl.calls)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `source over the byte budget is reported and not retried`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = ScriptedDownloader(listOf(Step(null, PicpSyncOrchestrator.FetchResult.TOO_LARGE)))

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        expectOutcome(PicpSyncOrchestrator.Outcome.TooLarge, outcome)
        assertEquals(1, dl.calls)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    // ━━ 切换失败 ━━

    @Test
    fun `switch failure restores the old library`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val refusing = object : FileSnapshotSwitch.FsOps {
            override fun rename(from: File, to: File): Boolean =
                if (from.name == "icons.staging") false else from.renameTo(to)

            override fun deleteTree(file: File): Boolean = file.deleteRecursively()
        }

        val outcome = PicpSyncOrchestrator.run(
            cache, delivering(goodZip), cancelled = { false },
            switch = { FileSnapshotSwitch.switchIn(it, "icons", refusing) }
        )

        expectOutcome(PicpSyncOrchestrator.Outcome.SwitchFailed, outcome)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    // ━━ 取消 ━━

    @Test
    fun `cancel during download is reported as cancelled`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = ScriptedDownloader(listOf(Step(null, PicpSyncOrchestrator.FetchResult.CANCELLED)))

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false })

        expectOutcome(PicpSyncOrchestrator.Outcome.Cancelled, outcome)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `cancel during extraction is reported as cancelled and extracts nothing live`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val polls = intArrayOf(0)
        // 轮询点：循环入口一次，之后归档每读一块一次；第 5 次起取消 = 第二个图标解到一半
        val outcome = PicpSyncOrchestrator.run(
            cache, delivering(goodZip), cancelled = { polls[0]++ >= 4 }
        )

        expectOutcome(PicpSyncOrchestrator.Outcome.Cancelled, outcome)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    @Test
    fun `cancel before anything starts never downloads`() {
        val dl = delivering(goodZip)

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { true })

        expectOutcome(PicpSyncOrchestrator.Outcome.Cancelled, outcome)
        assertEquals(0, dl.calls)
    }

    @Test
    fun `cancel between attempts skips the second download`() {
        seedOld("com.a", "com.b")
        val before = snapshot(icons)
        val dl = ScriptedDownloader(
            listOf(Step(null, PicpSyncOrchestrator.FetchResult.FAILED), Step(goodZip, PicpSyncOrchestrator.FetchResult.OK))
        )
        val stages = mutableListOf<PicpSyncOrchestrator.Stage>()

        val outcome = PicpSyncOrchestrator.run(
            cache, dl,
            cancelled = { stages.any { it is PicpSyncOrchestrator.Stage.Retrying } },
            onStage = { stages += it },
            pauseBetweenAttempts = {}
        )

        expectOutcome(PicpSyncOrchestrator.Outcome.Cancelled, outcome)
        assertEquals(1, dl.calls)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }

    // ━━ 重试 ━━

    @Test
    fun `invalid archive then a good one succeeds on retry`() {
        seedOld("com.a", "com.b")
        val dl = delivering("<html>Service Unavailable</html>".toByteArray(), goodZip)
        val pauses = mutableListOf<Long>()

        val outcome = PicpSyncOrchestrator.run(
            cache, dl, cancelled = { false },
            pauseBetweenAttempts = { pauses += it }
        )

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertEquals(2, dl.calls)
        assertEquals(listOf(PicpSyncOrchestrator.RETRY_DELAY_MS), pauses)
        assertNoResidue()
    }

    @Test
    fun `download failure then success succeeds on retry`() {
        val dl = ScriptedDownloader(
            listOf(Step(null, PicpSyncOrchestrator.FetchResult.FAILED), Step(goodZip, PicpSyncOrchestrator.FetchResult.OK))
        )

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertNoResidue()
    }

    // ━━ 遗留状态 ━━

    @Test
    fun `leftover state from a killed sync is converged at entry`() {
        // 上次同步在「旧版已让位、新版本未上位」时被杀：prev 里是旧版，
        // 没建完的暂存目录和下载好的归档都还在
        val prev = FileSnapshotSwitch.prevOf(cache, "icons")
        prev.mkdirs()
        File(prev, "com.a.png").writeBytes("old-com.a".toByteArray())
        File(prev, "index.txt").writeText("com.a")
        FileSnapshotSwitch.stagingOf(cache, "icons").let { staging ->
            staging.mkdirs()
            File(staging, "half.png").writeBytes("half".toByteArray())
        }
        File(cache, PicpLibrary.ARCHIVE).writeBytes("stale archive".toByteArray())

        val outcome = PicpSyncOrchestrator.run(cache, delivering(goodZip), cancelled = { false })

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertEquals(
            setOf("com.a.png", "com.c.png", "index.txt"),
            icons.listFiles()!!.map { it.name }.toSet()
        )
        assertNoResidue()
    }

    @Test
    fun `legacy root index is discarded after a successful switch`() {
        // 0.19.6-beta 的布局：图标目录里没有索引，索引在根部
        icons.mkdirs()
        File(icons, "com.a.png").writeBytes("old-com.a".toByteArray())
        File(cache, PicpLibrary.LEGACY_ROOT_INDEX).writeText("com.a")
        assertEquals(
            PicpLibrary.IndexSource.LEGACY_ROOT,
            PicpLibrary.readPackages(cache, clearStaging = false).source
        )

        val outcome = PicpSyncOrchestrator.run(cache, delivering(goodZip), cancelled = { false })

        expectOutcome(PicpSyncOrchestrator.Outcome.Success(2), outcome)
        assertFalse(File(cache, PicpLibrary.LEGACY_ROOT_INDEX).exists())
        val loaded = PicpLibrary.readPackages(cache, clearStaging = false)
        assertEquals(PicpLibrary.IndexSource.INNER, loaded.source)
        assertEquals(setOf("com.a", "com.c"), loaded.packages)
    }

    // ━━ 下载器不守约 ━━

    @Test
    fun `downloader reporting ok without producing an archive is a download failure`() {
        seedOld("com.a")
        val before = snapshot(icons)
        val dl = ScriptedDownloader(
            List(2) { Step(null, PicpSyncOrchestrator.FetchResult.OK) }
        )

        val outcome = PicpSyncOrchestrator.run(cache, dl, cancelled = { false }, pauseBetweenAttempts = {})

        expectOutcome(PicpSyncOrchestrator.Outcome.DownloadFailed, outcome)
        assertEquals(before, snapshot(icons))
        assertNoResidue()
    }
}
