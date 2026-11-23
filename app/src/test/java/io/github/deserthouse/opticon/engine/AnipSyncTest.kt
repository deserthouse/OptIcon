package io.github.deserthouse.opticon.engine

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
import java.net.InetSocketAddress

/**
 * AnipSync 外壳的端到端单测（修复批）：真实 OkHttp + 本地 HTTP 端点 + 真实文件系统。
 * 构建、切换、清理整条链走一遍。
 */
class AnipSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var files: File
    private val rules get() = File(files, "fankes_cache")

    @Before
    fun setUp() {
        files = tmp.newFolder("files")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun serve(path: String, bytes: ByteArray) {
        server.createContext(path) { ex ->
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun seedOld(vararg pkgs: String) {
        rules.mkdirs()
        pkgs.forEach { File(rules, "$it.png").writeBytes("old-$it".toByteArray()) }
        File(rules, RuleLibrary.META_FILE).writeText("""{"apps":[]}""")
    }

    private fun assertNoResidue() {
        assertFalse(FileSnapshotSwitch.stagingOf(files, "fankes_cache").exists())
        assertFalse(FileSnapshotSwitch.prevOf(files, "fankes_cache").exists())
        assertFalse(File(files, "fankes_cache.zip.tmp").exists())
    }

    @Test
    fun `successful sync switches the library and reports revoked packages`() {
        seedOld("com.a", "com.b", "com.revoked")
        serve(
            "/bundle.zip",
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(
                        AnipZipFixtures.Declared("com.a"),
                        AnipZipFixtures.Declared("com.b"),
                        AnipZipFixtures.Declared("com.fresh")
                    ))
                )
            )
        )

        val outcome = AnipSync.syncDetailed(rules, url = baseUrl() + "/bundle.zip")

        assertEquals(3, outcome.count)
        assertEquals(listOf("com.revoked"), outcome.revokedPackages)
        assertEquals(
            setOf("com.a.png", "com.b.png", "com.fresh.png", "meta.json"),
            rules.listFiles()!!.map { it.name }.toSet()
        )
        assertNoResidue()
    }

    @Test
    fun `declared package with a missing resource keeps its old icon across the switch`() {
        seedOld("com.a", "com.b")
        serve(
            "/bundle.zip",
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(
                        AnipZipFixtures.Declared("com.a"),
                        AnipZipFixtures.Declared("com.b", png = null)
                    ))
                )
            )
        )

        val outcome = AnipSync.syncDetailed(rules, url = baseUrl() + "/bundle.zip")

        assertEquals(2, outcome.count)
        assertEquals(emptyList<String>(), outcome.revokedPackages)
        assertEquals("old-com.b", File(rules, "com.b.png").readText())
        assertNoResidue()
    }

    @Test
    fun `a bundle with an unparsable manifest fails and keeps the old library byte for byte`() {
        seedOld("com.a", "com.g")
        val before = rules.listFiles()!!.associate { it.name to it.readBytes().contentHashCode() }
        serve(
            "/bundle.zip",
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))),
                    AnipZipFixtures.Category("game", manifestRaw = "not json at all")
                )
            )
        )

        val outcome = AnipSync.syncDetailed(rules, url = baseUrl() + "/bundle.zip")

        assertEquals(0, outcome.count)
        assertEquals(before, rules.listFiles()!!.associate { it.name to it.readBytes().contentHashCode() })
        assertEquals("old-com.g", File(rules, "com.g.png").readText())
        assertNoResidue()
    }

    @Test
    fun `builtin base url maps to the official archive and custom urls pass through`() {
        assertEquals(AnipSync.ZIP_URL, AnipSync.archiveUrlFor(AnipSync.BASE))
        assertEquals("https://example.com/fork/archive.zip", AnipSync.archiveUrlFor("https://example.com/fork/archive.zip"))
    }

    @Test
    fun `custom archive with a different root syncs through its own url`() {
        serve(
            "/fork.zip",
            AnipZipFixtures.build(
                root = "my-fork-main/",
                categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.fork"))))
            )
        )

        val outcome = AnipSync.syncDetailed(rules, url = baseUrl() + "/fork.zip")

        assertEquals(1, outcome.count)
        assertTrue(File(rules, "com.fork.png").exists())
        assertNoResidue()
    }

    @Test
    fun `switch failure restores the old library`() {
        seedOld("com.a")
        serve(
            "/bundle.zip",
            AnipZipFixtures.build(
                categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.new"))))
            )
        )
        val refusing = object : FileSnapshotSwitch.FsOps {
            override fun rename(from: File, to: File): Boolean =
                if (from.name == "fankes_cache.staging") false else from.renameTo(to)

            override fun deleteTree(file: File): Boolean = file.deleteRecursively()
        }

        val outcome = AnipSync.syncDetailed(rules, url = baseUrl() + "/bundle.zip", ops = refusing)

        assertEquals(0, outcome.count)
        assertEquals("old-com.a", File(rules, "com.a.png").readText())
        assertFalse(File(rules, "com.new.png").exists())
        assertNoResidue()
    }
}
