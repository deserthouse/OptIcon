package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 批 UP：内置规则源的同步流程走官方发布通道——先取清单、没变不下载、变了下载并核对、
 * 用不上就退回整库。真实的文件系统、真实的构建与切换，只把「取字节」换成假的（不联网）。
 *
 * 接线层面（SubscriptionManager → AnipSync）另见 EngineGateWiringTest。
 */
class AnipReleaseFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: File
    private val rules get() = File(files, "fankes_cache")

    /** 假的取字节通道：记下每个请求；按地址给内容。 */
    private class FakeAccess : AnipReleaseChannel.Access {
        val small = ArrayList<Pair<String, Long>>()
        val file = ArrayList<Pair<String, Long>>()
        var manifest: ByteArray? = null
        val served = HashMap<String, ByteArray>()

        override fun fetchSmall(url: String, maxBytes: Long): ByteArray? {
            small += url to maxBytes
            return manifest
        }

        override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
            file += url to maxBytes
            val bytes = served[url] ?: return false
            if (bytes.size > maxBytes) return false
            dest.writeBytes(bytes)
            return true
        }
    }

    private val access = FakeAccess()

    /** 两个包的官方资源包（没有 icons/ 这一层）。 */
    private val bundle: ByteArray = AnipZipFixtures.build(
        root = "",
        iconsDir = false,
        categories = listOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"), AnipZipFixtures.Declared("com.b")))
        )
    )

    /** 整库 zipball（有 icons/ 这一层）。 */
    private val archive: ByteArray = AnipZipFixtures.build(
        categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"), AnipZipFixtures.Declared("com.b"))))
    )

    private val bundleUrl = "https://github.com/${AnipReleaseChannel.REPO}/releases/latest/download/anip-bundle-abc1234.zip"

    @Before
    fun setUp() {
        files = tmp.newFolder("files")
        access.served[bundleUrl] = bundle
        access.served[AnipSync.ZIP_URL] = archive
        access.manifest = manifest("abc1234", bundle)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(tag: String, bytes: ByteArray, sha: String = sha256(bytes), size: Long = bytes.size.toLong()): ByteArray = """
        {"schemaVersion": 1, "tag": "$tag", "timestamp": 1795697528000,
         "assetName": "anip-bundle-$tag.zip", "downloadUrl": "anip-bundle-$tag.zip",
         "size": $size, "sha256": "$sha"}
    """.trimIndent().toByteArray()

    private fun sync() = AnipSync.syncDetailed(rules, url = AnipSync.ZIP_URL, access = access)

    private fun snapshotHashes(): Map<String, Int> =
        rules.listFiles()!!.associate { it.name to it.readBytes().contentHashCode() }

    private fun assertNoResidue() {
        assertFalse(FileSnapshotSwitch.stagingOf(files, "fankes_cache").exists())
        assertFalse(FileSnapshotSwitch.prevOf(files, "fankes_cache").exists())
        assertFalse(File(files, "fankes_cache.zip.tmp").exists())
    }

    private fun seedOld(vararg pkgs: String) {
        rules.mkdirs()
        pkgs.forEach { File(rules, "$it.png").writeBytes("old-$it".toByteArray()) }
    }

    @Test
    fun `a new release is downloaded through the channel, switched in and recorded`() {
        val outcome = sync()

        assertTrue(outcome.ok)
        assertEquals(2, outcome.count)
        assertFalse(outcome.unchanged)
        assertEquals(AnipSync.CHANNEL_RELEASE, outcome.channel)
        assertEquals("abc1234", outcome.tag)
        // 界面上显示的发布日期来自这里
        assertEquals(1795697528000L, outcome.versionTime)
        assertEquals(listOf(AnipReleaseChannel.MANIFEST_URL to AnipReleaseChannel.MAX_MANIFEST_BYTES), access.small)
        // 只取了官方包，字节上限就是清单里的大小；没有碰整库 zipball
        assertEquals(listOf(bundleUrl to bundle.size.toLong()), access.file)

        val source = RuleLibrary.readSource(rules)!!
        assertEquals(AnipSync.CHANNEL_RELEASE, source.channel)
        assertEquals("abc1234", source.tag)
        assertEquals(sha256(bundle), source.sha256)
        assertEquals(1795697528000L, source.timestamp)
        assertEquals(
            setOf("com.a.png", "com.b.png", "meta.json", RuleLibrary.SOURCE_FILE),
            rules.listFiles()!!.map { it.name }.toSet()
        )
        assertNoResidue()
    }

    @Test
    fun `the same release again is reported as unchanged and nothing is downloaded or rebuilt`() {
        sync()
        access.small.clear()
        access.file.clear()
        val before = snapshotHashes()

        val again = sync()

        assertTrue(again.ok)
        assertTrue(again.unchanged)
        assertEquals(2, again.count)
        assertEquals("abc1234", again.tag)
        assertEquals(1795697528000L, again.versionTime)
        assertEquals(AnipSync.CHANNEL_RELEASE, again.channel)
        assertEquals("只取了一次清单", 1, access.small.size)
        assertEquals("没有下载任何包", 0, access.file.size)
        assertEquals("快照一个字节都没动", before, snapshotHashes())
        assertNoResidue()
    }

    @Test
    fun `a newer release is downloaded and the revoked package is reported`() {
        sync()
        val next = AnipZipFixtures.build(
            root = "",
            iconsDir = false,
            categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"), AnipZipFixtures.Declared("com.c"))))
        )
        val nextUrl = "https://github.com/${AnipReleaseChannel.REPO}/releases/latest/download/anip-bundle-def5678.zip"
        access.served[nextUrl] = next
        access.manifest = manifest("def5678", next)

        val outcome = sync()

        assertTrue(outcome.ok)
        assertFalse(outcome.unchanged)
        assertEquals("def5678", outcome.tag)
        assertEquals(listOf("com.b"), outcome.revokedPackages)
        assertFalse(File(rules, "com.b.png").exists())
        assertTrue(File(rules, "com.c.png").exists())
    }

    @Test
    fun `a bundle that fails verification is dropped and the branch archive is used instead`() {
        // 清单里的 sha256 与实际包对不上（模拟传输损坏或清单被改）
        access.manifest = manifest("abc1234", bundle, sha = "0".repeat(64))

        val outcome = sync()

        assertTrue(outcome.ok)
        assertFalse(outcome.unchanged)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
        assertNull(outcome.tag)
        assertTrue("整库归档记的是取到它的时间（source.json 的 timestamp），没有上游版本可报", outcome.versionTime > 0L)
        assertEquals(listOf(bundleUrl to bundle.size.toLong(), AnipSync.ZIP_URL to AnipSnapshotBuilder.DEFAULT_LIMITS.archiveBytes), access.file)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, RuleLibrary.readSource(rules)!!.channel)
        assertEquals(sha256(archive), RuleLibrary.readSource(rules)!!.sha256)
        assertNoResidue()
    }

    @Test
    fun `a bundle that cannot be downloaded falls back to the branch archive`() {
        access.served.remove(bundleUrl)

        val outcome = sync()

        assertTrue(outcome.ok)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
        assertEquals(2, access.file.size)
        assertNoResidue()
    }

    @Test
    fun `when the manifest cannot be fetched the branch archive is used without further ado`() {
        access.manifest = null

        val outcome = sync()

        assertTrue(outcome.ok)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
        assertEquals(listOf(AnipSync.ZIP_URL to AnipSnapshotBuilder.DEFAULT_LIMITS.archiveBytes), access.file)
    }

    @Test
    fun `a manifest the app does not understand falls back instead of failing`() {
        access.manifest = """{"schemaVersion": 2, "tag": "abc1234"}""".toByteArray()

        val outcome = sync()

        assertTrue(outcome.ok)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
        assertEquals(2, outcome.count)
    }

    @Test
    fun `a custom archive url never touches the release channel`() {
        val custom = "https://example.com/fork/archive.zip"
        access.served[custom] = archive

        val outcome = AnipSync.syncDetailed(rules, url = custom, access = access)

        assertTrue(outcome.ok)
        assertEquals(0, access.small.size)
        assertEquals(listOf(custom to AnipSnapshotBuilder.DEFAULT_LIMITS.archiveBytes), access.file)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
    }

    @Test
    fun `the manifest address can be pointed elsewhere - the device suite does this with a local source`() {
        val local = "http://127.0.0.1:28765/anip-release.json"
        val localBundle = "http://127.0.0.1:28765/anip-bundle-abc1234.zip"
        access.served[localBundle] = bundle

        val outcome = AnipSync.syncDetailed(rules, url = AnipSync.ZIP_URL, access = access, manifestUrl = local)

        assertTrue(outcome.ok)
        assertEquals("abc1234", outcome.tag)
        assertEquals(listOf(local to AnipReleaseChannel.MAX_MANIFEST_BYTES), access.small)
        assertEquals(listOf(localBundle to bundle.size.toLong()), access.file)
        assertNoResidue()
    }

    @Test
    fun `a local write failure while downloading says storage, not network`() {
        // 磁盘满时下载落盘的失败（批 MT M3）：取字节通道抛 StorageWriteException，不退回整库、不报成下载失败
        seedOld("com.old")
        val before = snapshotHashes()
        access.served.clear()
        val writes = ArrayList<String>()
        val outcome = AnipSync.syncDetailed(
            rules, url = AnipSync.ZIP_URL,
            access = object : AnipReleaseChannel.Access by access {
                override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean {
                    writes += url
                    throw StorageWriteException("disk full")
                }
            }
        )

        assertFalse(outcome.ok)
        assertEquals(AnipSync.Failure.STORAGE, outcome.failure)
        assertEquals("官方包只试了一次，没有再去退回整库", listOf(bundleUrl), writes)
        assertEquals(before, snapshotHashes())
        assertNoResidue()
    }

    @Test
    fun `a local write failure while building the snapshot says storage and keeps the live library`() {
        // 渲染器交回的图写不进暂存目录：本地的问题，不是归档的问题
        sync()
        val before = snapshotHashes()
        val staging = FileSnapshotSwitch.stagingOf(files, "fankes_cache")
        access.served.remove(bundleUrl)
        val vector = AnipZipFixtures.build(
            root = "", iconsDir = false,
            categories = listOf(AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a"),
                AnipZipFixtures.Declared("com.v", ext = "svg", format = "svg", png = AnipZipFixtures.svgOf("com.v"))
            )))
        )
        val vectorUrl = "https://github.com/${AnipReleaseChannel.REPO}/releases/latest/download/anip-bundle-beef000.zip"
        access.served[vectorUrl] = vector
        access.manifest = manifest("beef000", vector)
        val outcome = AnipSync.syncDetailed(
            rules, url = AnipSync.ZIP_URL, access = access,
            // 轮到 SVG 条目时，把它自己的目标位置占成一个目录——随后往那儿写 PNG 必然失败
            rasterizer = AnipSnapshotBuilder.IconRasterizer { _, _ ->
                File(staging, "com.v.png").mkdirs()
                AnipZipFixtures.pngOf("rendered")
            }
        )

        assertFalse(outcome.ok)
        assertEquals(AnipSync.Failure.STORAGE, outcome.failure)
        assertEquals(before, snapshotHashes())
        assertNoResidue()
    }

    @Test
    fun `the production channel turns a storage outcome into a storage exception`() {
        // 生产环境的取字节通道把下载结果换成布尔值：存储失败在这一步变成异常，免得被抹平成「没下到」
        val dest = File(files, "anip-bundle.zip")
        assertTrue(AnipSync.accessResult(io.github.deserthouse.opticon.network.NetworkExecutor.DownloadOutcome.OK, dest))
        for (other in listOf(
            io.github.deserthouse.opticon.network.NetworkExecutor.DownloadOutcome.FAILED,
            io.github.deserthouse.opticon.network.NetworkExecutor.DownloadOutcome.CANCELLED,
            io.github.deserthouse.opticon.network.NetworkExecutor.DownloadOutcome.TOO_LARGE
        )) {
            assertFalse(AnipSync.accessResult(other, dest))
        }
        try {
            AnipSync.accessResult(io.github.deserthouse.opticon.network.NetworkExecutor.DownloadOutcome.STORAGE, dest)
            fail("a storage outcome must not be flattened into false")
        } catch (e: StorageWriteException) {
            assertEquals("cannot write anip-bundle.zip", e.message)
        }
    }

    @Test
    fun `a rule snapshot staging dir that cannot be created is a storage problem, not a rejected archive`() {
        val blocker = tmp.newFile("blocker")   // 同名位置是个文件：建不了目录
        val zip = tmp.newFile("bundle.zip")
        zip.writeBytes(bundle)

        try {
            AnipSnapshotBuilder.build(zip, oldDir = null, staging = File(blocker, "child"))
            fail("expected a storage failure")
        } catch (e: StorageWriteException) {
            assertEquals("cannot create staging dir", e.message)
        }
    }

    @Test
    fun `when both downloads fail the outcome says network and the live library is untouched`() {
        seedOld("com.a")
        val before = snapshotHashes()
        access.served.clear()

        val outcome = sync()

        assertFalse(outcome.ok)
        assertEquals(AnipSync.Failure.NETWORK, outcome.failure)
        assertEquals(before, snapshotHashes())
        assertNoResidue()
    }

    @Test
    fun `an archive the builder rejects reports invalid data and keeps the live library`() {
        seedOld("com.a")
        val before = snapshotHashes()
        access.manifest = null
        access.served[AnipSync.ZIP_URL] = "not a zip at all".toByteArray()

        val outcome = sync()

        assertFalse(outcome.ok)
        assertEquals(AnipSync.Failure.INVALID, outcome.failure)
        assertEquals(before, snapshotHashes())
        assertNoResidue()
    }

    @Test
    fun `unchanged is not claimed while the live snapshot is empty`() {
        // 来历文件说是 abc1234，目录里却一个图标都没有（比如被手工清空）：必须重新下载
        rules.mkdirs()
        RuleLibrary.writeSource(rules, RuleLibrary.Source(AnipSync.CHANNEL_RELEASE, "abc1234", 1L, "x"))

        val outcome = sync()

        assertTrue(outcome.ok)
        assertFalse(outcome.unchanged)
        assertEquals(2, outcome.count)
        assertEquals(1, access.file.size)
    }

    @Test
    fun `unchanged is not claimed when the live snapshot came from the branch archive`() {
        // 上次是兜底下来的整库：没有版本标签可比，官方通道再用一次
        sync()
        RuleLibrary.writeSource(rules, RuleLibrary.Source(AnipSync.CHANNEL_ARCHIVE, "abc1234", 1L, null))
        access.file.clear()

        val outcome = sync()

        assertTrue(outcome.ok)
        assertFalse(outcome.unchanged)
        assertEquals(listOf(bundleUrl to bundle.size.toLong()), access.file)
        assertEquals(AnipSync.CHANNEL_RELEASE, RuleLibrary.readSource(rules)!!.channel)
    }

    @Test
    fun `a source record that cannot be read is treated as no version record`() {
        sync()
        File(rules, RuleLibrary.SOURCE_FILE).writeText("{not json")
        access.file.clear()

        val outcome = sync()

        assertFalse(outcome.unchanged)
        assertEquals(1, access.file.size)
    }

    @Test
    fun `the source record round-trips`() {
        val source = RuleLibrary.Source(AnipSync.CHANNEL_RELEASE, "abc1234", 42L, "ff")
        assertEquals(source, RuleLibrary.Source.decode(source.encode()))
        val archiveSource = RuleLibrary.Source(AnipSync.CHANNEL_ARCHIVE, null, 7L, null)
        assertEquals(archiveSource, RuleLibrary.Source.decode(archiveSource.encode()))
        assertNull(RuleLibrary.Source.decode("{}"))
        assertNull(RuleLibrary.Source.decode("nope"))
        assertNull(RuleLibrary.Source.decode(""))
    }

    // ━━ 「已经是这个版本」要核实快照（2026-11-28 盲审 BU-05 / BU-06）━━

    @Test
    fun `a snapshot with an icon missing is not treated as up to date`() {
        // 缓存目录可能被系统或清理工具删掉一部分：少了一个就重新下载，不等上游发下一个版本
        sync()
        access.file.clear()
        File(rules, "com.a.png").delete()

        val outcome = sync()

        assertTrue(outcome.ok)
        assertFalse(outcome.unchanged)
        assertEquals(listOf(bundleUrl to bundle.size.toLong()), access.file)
        assertEquals(
            setOf("com.a.png", "com.b.png", "meta.json", RuleLibrary.SOURCE_FILE),
            rules.listFiles()!!.map { it.name }.toSet()
        )
        assertNoResidue()
    }

    @Test
    fun `a snapshot overwritten in place by the legacy rules source is not treated as up to date`() {
        // 切到旧版规则源同步（就地写入：覆盖图标和 meta、留下来源标记），再切回内置源
        sync()
        access.file.clear()
        File(rules, "com.a.png").writeBytes("legacy-a".toByteArray())
        File(rules, "com.legacy.only.png").writeBytes("legacy-only".toByteArray())
        File(rules, "meta.json").writeText("""{"apps":[]}""")
        File(rules, IconLibEngine.LEGACY_ORIGIN_FILE).writeText("legacy")

        val outcome = sync()

        assertFalse(outcome.unchanged)
        assertEquals(listOf(bundleUrl to bundle.size.toLong()), access.file)
        assertFalse("整目录切换之后旧版独有的图不在了", File(rules, "com.legacy.only.png").exists())
        assertArrayEquals(AnipZipFixtures.pngOf("com.a"), File(rules, "com.a.png").readBytes())
        assertFalse(File(rules, IconLibEngine.LEGACY_ORIGIN_FILE).exists())
    }

    @Test
    fun `the legacy origin marker alone rules out up to date even when the icon count still matches`() {
        // 旧版规则覆盖了一个包的图、没有增减条数：只看条数分辨不出来，靠来源标记
        sync()
        access.file.clear()
        File(rules, "com.a.png").writeBytes("legacy-a".toByteArray())
        File(rules, IconLibEngine.LEGACY_ORIGIN_FILE).writeText("legacy")

        val outcome = sync()

        assertFalse(outcome.unchanged)
        assertArrayEquals(AnipZipFixtures.pngOf("com.a"), File(rules, "com.a.png").readBytes())
    }

    @Test
    fun `a source record without an icon count is not enough to claim up to date`() {
        // 批 UP 早期的记录没记条数：核实不了就不说「已是最新」；重新下载一次之后记录就齐了
        rules.mkdirs()
        File(rules, "com.a.png").writeBytes("x".toByteArray())
        File(rules, RuleLibrary.SOURCE_FILE).writeText("""{"channel":"release","tag":"abc1234","timestamp":1,"sha256":null}""")

        val outcome = sync()

        assertFalse(outcome.unchanged)
        assertEquals(2, RuleLibrary.readSource(rules)!!.icons)
    }

    @Test
    fun `the source record carries the icon count of the snapshot`() {
        sync()

        val source = RuleLibrary.readSource(rules)!!
        assertEquals(2, source.icons)
        assertEquals(source, RuleLibrary.Source.decode(source.encode()))
        assertEquals("旧格式的记录（没有 icons）读出来是 0 = 没记", 0, RuleLibrary.Source.decode("""{"channel":"release","tag":"abc1234","timestamp":1}""")!!.icons)
    }

    @Test
    fun `the fallback archive address can be pointed elsewhere - the device suite does this with a local source`() {
        // 核对不上时退回的整库：默认是上游 zipball；调试包把它指到本机测试源，已知答案不跟着真实上游变
        val localArchive = "http://127.0.0.1:28765/archive.zip"
        access.served[localArchive] = archive
        access.manifest = manifest("abc1234", bundle, sha = "0".repeat(64))

        val outcome = AnipSync.syncDetailed(rules, url = AnipSync.ZIP_URL, access = access, fallbackArchiveUrl = localArchive)

        assertTrue(outcome.ok)
        assertEquals(AnipSync.CHANNEL_ARCHIVE, outcome.channel)
        assertEquals(listOf(bundleUrl to bundle.size.toLong(), localArchive to AnipSnapshotBuilder.DEFAULT_LIMITS.archiveBytes), access.file)
        assertEquals(sha256(archive), RuleLibrary.readSource(rules)!!.sha256)
        assertNoResidue()
    }
}
