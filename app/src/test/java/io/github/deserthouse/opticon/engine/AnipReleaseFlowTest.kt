package io.github.deserthouse.opticon.engine

import org.json.JSONObject
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
}
