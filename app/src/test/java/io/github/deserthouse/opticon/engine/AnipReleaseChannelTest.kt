package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 批 UP：上游官方发布通道的清单校验、下载地址解析、包核对（纯 JVM，不联网）。
 * 校验规则参照上游 SDK 的 ResourceReleaseClient；下载地址更严（必须落在清单所在目录）。
 */
class AnipReleaseChannelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 上游 2026-11-26 那份清单的原文（数值照抄），测试里按需改字段。 */
    private val real = """
        {
          "schemaVersion": 1,
          "tag": "e98c906",
          "timestamp": 1795697528000,
          "assetName": "anip-bundle-e98c906.zip",
          "downloadUrl": "anip-bundle-e98c906.zip",
          "size": 1795929,
          "sha256": "094387de082008e6f4ab1899602151db804491422519d02a5a704cc0e473988a"
        }
    """.trimIndent()

    private fun withField(name: String, rawValue: String): String =
        real.replace(Regex("\"$name\"\\s*:\\s*(\"[^\"]*\"|[-0-9]+)"), "\"$name\": $rawValue")

    private fun unavailable(text: String, vararg mustMention: String): AnipReleaseChannel.Fetched.Unavailable {
        val result = AnipReleaseChannel.parse(text)
        check(result is AnipReleaseChannel.Fetched.Unavailable) { "expected Unavailable, got $result" }
        for (word in mustMention) assertTrue("reason should mention '$word': ${result.reason}", result.reason.contains(word))
        return result
    }

    private class FakeAccess : AnipReleaseChannel.Access {
        val small = ArrayList<Pair<String, Long>>()
        var answer: ByteArray? = null
        override fun fetchSmall(url: String, maxBytes: Long): ByteArray? {
            small += url to maxBytes
            return answer
        }

        override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean = false
    }

    @Test
    fun `the real upstream manifest is accepted and resolves to the latest-release asset`() {
        val ok = AnipReleaseChannel.parse(real) as AnipReleaseChannel.Fetched.Ok
        val r = ok.release
        assertEquals("e98c906", r.tag)
        assertEquals(1795697528000L, r.timestamp)
        assertEquals(1795929L, r.size)
        assertEquals("094387de082008e6f4ab1899602151db804491422519d02a5a704cc0e473988a", r.sha256)
        assertEquals(
            "https://github.com/BetterAndroid/android-notification-icon-project/releases/latest/download/anip-bundle-e98c906.zip",
            r.downloadUrl
        )
    }

    @Test
    fun `a manifest schema the app does not know is refused by name`() {
        unavailable(withField("schemaVersion", "2"), "schema")
    }

    @Test
    fun `a tag that is not seven hex characters is refused`() {
        unavailable(withField("tag", "\"v1.2.3\""), "tag")
    }

    @Test
    fun `an asset name that does not match the tag is refused`() {
        unavailable(withField("assetName", "\"anip-bundle-0000000.zip\""), "asset")
    }

    @Test
    fun `a timestamp that is not positive is refused`() {
        unavailable(withField("timestamp", "0"), "timestamp")
    }

    @Test
    fun `a size outside the allowed range is refused`() {
        unavailable(withField("size", "0"), "size")
        unavailable(withField("size", "-5"), "size")
        unavailable(withField("size", Long.MAX_VALUE.toString() + "0"), "size")
    }

    @Test
    fun `a sha256 that is not 64 hex characters is refused`() {
        unavailable(withField("sha256", "\"abc\""), "sha256")
        unavailable(withField("sha256", "\"" + "z".repeat(64) + "\""), "sha256")
    }

    @Test
    fun `a download url outside the release directory is refused`() {
        unavailable(withField("downloadUrl", "\"https://example.com/evil.zip\""), "download")
        unavailable(withField("downloadUrl", "\"https://github.com/BetterAndroid/android-notification-icon-project/releases/download/e98c906/anip-bundle-e98c906.zip\""), "download")
        unavailable(withField("downloadUrl", "\"../anip-bundle-e98c906.zip\""), "download")
        unavailable(withField("downloadUrl", "\"anip-bundle-e98c906.zip?x=1\""), "download")
    }

    @Test
    fun `an absolute download url inside the release directory is accepted`() {
        val ok = AnipReleaseChannel.parse(
            withField("downloadUrl", "\"https://github.com/BetterAndroid/android-notification-icon-project/releases/latest/download/anip-bundle-e98c906.zip\"")
        )
        assertTrue(ok is AnipReleaseChannel.Fetched.Ok)
    }

    @Test
    fun `text that is not JSON, or lacks a field, is refused without throwing`() {
        unavailable("not json at all", "JSON")
        unavailable("{}", "schema")
        unavailable(real.replace("\"tag\": \"e98c906\",", ""), "tag")
        unavailable(real.replace("\"sha256\"", "\"digest\""), "sha256")
        unavailable(real.replace("\"downloadUrl\"", "\"url\""), "downloadUrl")
    }

    @Test
    fun `fetching the manifest asks for the fixed url with the size cap and never throws`() {
        val access = FakeAccess()
        access.answer = real.toByteArray()
        val ok = AnipReleaseChannel.fetchLatest(access)
        assertTrue(ok is AnipReleaseChannel.Fetched.Ok)
        assertEquals(listOf(AnipReleaseChannel.MANIFEST_URL to AnipReleaseChannel.MAX_MANIFEST_BYTES), access.small)

        access.answer = null
        unavailableAccess(AnipReleaseChannel.fetchLatest(access), "manifest request failed")

        val throwing = object : AnipReleaseChannel.Access {
            override fun fetchSmall(url: String, maxBytes: Long): ByteArray? = throw IllegalStateException("boom")
            override fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean = false
        }
        unavailableAccess(AnipReleaseChannel.fetchLatest(throwing), "IllegalStateException")
    }

    private fun unavailableAccess(f: AnipReleaseChannel.Fetched, word: String) {
        assertTrue("expected Unavailable, got $f", f is AnipReleaseChannel.Fetched.Unavailable)
        assertTrue((f as AnipReleaseChannel.Fetched.Unavailable).reason, f.reason.contains(word))
    }

    @Test
    fun `verify accepts a file only when its size and sha256 both match`() {
        val bytes = AnipZipFixtures.build(
            categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))))
        )
        val good = tmp.newFile("good.zip").apply { writeBytes(bytes) }
        val release = AnipReleaseChannel.Release("abc1234", 1L, bytes.size.toLong(), sha256(bytes), "x")

        assertTrue(AnipReleaseChannel.verify(good, release))

        val sameSizeDifferentBytes = tmp.newFile("flip.zip").apply { writeBytes(bytes.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
        assertFalse("same size, different content", AnipReleaseChannel.verify(sameSizeDifferentBytes, release))

        val short = tmp.newFile("short.zip").apply { writeBytes(bytes.copyOf(bytes.size - 1)) }
        assertFalse("truncated", AnipReleaseChannel.verify(short, release))

        assertFalse("missing", AnipReleaseChannel.verify(File(tmp.root, "nope.zip"), release))

        assertFalse("size in the manifest is wrong", AnipReleaseChannel.verify(good, release.copy(size = release.size + 1)))
        assertFalse("sha256 in the manifest is wrong", AnipReleaseChannel.verify(good, release.copy(sha256 = "0".repeat(64))))
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `resolve keeps downloads inside the manifest directory`() {
        val base = "https://github.com/o/r/releases/latest/download/"
        assertEquals(base + "anip-bundle-abc.zip", AnipReleaseChannel.resolve(base + "anip-release.json", "anip-bundle-abc.zip"))
        assertNull(AnipReleaseChannel.resolve(base + "anip-release.json", ""))
        assertNull(AnipReleaseChannel.resolve(base + "anip-release.json", "http://github.com/o/r/releases/latest/download/x.zip"))
        assertEquals(base + "x.zip", AnipReleaseChannel.resolve(base + "anip-release.json", "https://github.com/o/r/releases/latest/download/x.zip"))
    }
}
