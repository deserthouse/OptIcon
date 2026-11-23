package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AnipSnapshotBuilder 单测（修复批 X-03 / X-15 / X-17）。
 *
 * 钉死的契约：读取失败不等于上游撤销。
 *  - 清单解析不了 → 整体失败，不构建；
 *  - 声明了但读不出的包 → 沿用旧版本里的图标；旧版本也没有 → 不进新快照；
 *  - 正常的撤销仍然生效（新版本里没有的包就是不在）。
 */
class AnipSnapshotBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var work: File
    private val old get() = File(work, "old")
    private val staging get() = File(work, "staging")

    @Before
    fun setUp() {
        work = tmp.newFolder("anip")
    }

    private fun archiveOf(vararg categories: AnipZipFixtures.Category, root: String = AnipZipFixtures.OFFICIAL_ROOT): File =
        File(work, "bundle.zip").apply { writeBytes(AnipZipFixtures.build(root, categories.toList())) }

    private fun seedOld(vararg pkgs: String) {
        old.mkdirs()
        pkgs.forEach { File(old, "$it.png").writeBytes("old-$it".toByteArray()) }
        File(old, RuleLibrary.META_FILE).writeText("""{"apps":[]}""")
    }

    /** 目录快照：相对路径 → 内容（按值比较）。 */
    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }
            .associate { it.name to String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun expectRejected(block: () -> AnipSnapshotBuilder.Result, reasonContains: String) {
        val result = block()
        if (result !is AnipSnapshotBuilder.Result.Rejected) fail("expected a rejection, got $result")
        val rejected = result as AnipSnapshotBuilder.Result.Rejected
        assertTrue(
            "reason should mention '$reasonContains' but was '${rejected.reason}'",
            rejected.reason.contains(reasonContains)
        )
        assertFalse("a rejection must not leave a half-built staging dir", staging.exists())
    }

    // ━━ 正常构建 ━━

    @Test
    fun `complete archive builds a snapshot with meta`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a", zh = "甲", en = "A"),
                AnipZipFixtures.Declared("com.b", zh = "乙", en = "B")
            )),
            AnipZipFixtures.Category("game", listOf(AnipZipFixtures.Declared("com.g", zh = "丙", en = "G")))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging, preferZh = false)

        val built = result as AnipSnapshotBuilder.Result.Built
        // 清单是 JSON 对象，键的遍历顺序不确定——断言一律与顺序无关
        assertEquals(setOf("com.a", "com.b", "com.g"), built.items.map { it.pkg }.toSet())
        assertEquals(AnipSnapshotBuilder.Stats(declared = 3, written = 3, carriedOver = 0, missing = 0), built.stats)
        assertEquals(mapOf("com.a" to "A", "com.b" to "B", "com.g" to "G"), built.items.associate { it.pkg to it.appName })
        assertEquals("app", built.items.first { it.pkg == "com.a" }.category)
        assertEquals("game", built.items.first { it.pkg == "com.g" }.category)
        assertEquals(
            String(AnipZipFixtures.pngOf("com.a"), Charsets.ISO_8859_1),
            snapshot(staging)["com.a.png"]
        )
        val apps = JSONObject(File(staging, RuleLibrary.META_FILE).readText()).getJSONArray("apps")
        val metaNames = (0 until apps.length()).map { apps.getJSONObject(it).getString("packageName") }
        assertEquals(setOf("com.a", "com.b", "com.g"), metaNames.toSet())
        assertEquals(setOf("com.a.png", "com.b.png", "com.g.png", "meta.json"), snapshot(staging).keys)
    }

    @Test
    fun `package absent from the new bundle is simply not in the snapshot`() {
        // 上游撤销 B：新清单里没有 B。这是「真撤销」，必须生效（批 SYNC V2 的行为不回归）。
        seedOld("com.a", "com.b")
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a")))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging, preferZh = false)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(listOf("com.a"), built.items.map { it.pkg })
        assertFalse(File(staging, "com.b.png").exists())
        // 旧目录不被构建器触碰
        assertTrue(File(old, "com.b.png").exists())
    }

    // ━━ 清单解析不了：整体失败 ━━

    @Test
    fun `unparsable manifest rejects the whole build`() {
        // 之前：game 类别被静默跳过，目录整体换入 → 该类别的规则全部消失，同步却显示成功
        seedOld("com.a", "com.g")
        val before = snapshot(old)
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))),
            AnipZipFixtures.Category("game", manifestRaw = "{\"com.g\": {\"label\": ")
        )

        expectRejected(
            { AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging) },
            reasonContains = "manifest game unparsable"
        )
        assertEquals(before, snapshot(old))
    }

    @Test
    fun `truncated archive is rejected`() {
        val bytes = AnipZipFixtures.build(
            categories = listOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))))
        )
        val zip = File(work, "cut.zip").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }

        expectRejected(
            { AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging) },
            reasonContains = "not a complete zip"
        )
    }

    @Test
    fun `archive without any ANIP manifest is rejected`() {
        val zip = File(work, "other.zip").apply {
            writeBytes(PicpZipFixtures.build(pkgs = listOf("com.a"))) // 一份 PICP 归档
        }

        expectRejected(
            { AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging) },
            reasonContains = "no ANIP category manifest"
        )
    }

    @Test
    fun `snapshot where nothing could be built is rejected`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a", png = null),
                AnipZipFixtures.Declared("com.b", png = null)
            ))
        )

        expectRejected(
            { AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging) },
            reasonContains = "snapshot empty"
        )
    }

    // ━━ 声明了但读不出：沿用旧版本，不当作撤销 ━━

    @Test
    fun `declared package whose resource is missing is carried over from the old version`() {
        seedOld("com.a", "com.b")
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a"),                      // 正常
                AnipZipFixtures.Declared("com.b", en = "B", png = null) // 清单有、资源没有
            ))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging, preferZh = false)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(setOf("com.a", "com.b"), built.items.map { it.pkg }.toSet())
        assertEquals("old-com.b", File(staging, "com.b.png").readText()) // 沿用的是旧图，不是空文件
        assertEquals(AnipSnapshotBuilder.Stats(declared = 2, written = 1, carriedOver = 1, missing = 0), built.stats)
        assertEquals("B", built.items.first { it.pkg == "com.b" }.appName) // 元数据仍取自新清单
    }

    @Test
    fun `declared package with a corrupt resource is carried over`() {
        seedOld("com.a", "com.b")
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a"),
                AnipZipFixtures.Declared("com.b", png = "this is not an image".toByteArray())
            ))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals("old-com.b", File(staging, "com.b.png").readText())
        assertEquals(1, built.stats.carriedOver)
    }

    @Test
    fun `malformed manifest entry is carried over rather than dropping the package`() {
        // 上游把这一个条目写坏了：只影响这一个包，不至于让所有人的更新都停摆
        seedOld("com.a", "com.b")
        val zip = archiveOf(
            AnipZipFixtures.Category(
                "app",
                declared = listOf(AnipZipFixtures.Declared("com.a")),
                rawEntries = mapOf("com.b" to "\"oops\"")
            )
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(setOf("com.a", "com.b"), built.items.map { it.pkg }.toSet())
        assertEquals("", built.items.first { it.pkg == "com.b" }.appName)
        assertEquals(1, built.stats.carriedOver)
    }

    @Test
    fun `declared package missing everywhere is counted and left out`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a"),
                AnipZipFixtures.Declared("com.ghost", png = null)
            ))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(listOf("com.a"), built.items.map { it.pkg })
        assertEquals(AnipSnapshotBuilder.Stats(declared = 2, written = 1, carriedOver = 0, missing = 1), built.stats)
    }

    // ━━ 归档形态 ━━

    @Test
    fun `a category without a manifest just means the category is absent`() {
        val zip = archiveOf(AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))))

        val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging)

        assertEquals(1, (result as AnipSnapshotBuilder.Result.Built).items.size)
    }

    @Test
    fun `custom archive roots are detected`() {
        // 修复批 X-17：根目录名写死，自定义地址的归档解析不出任何东西
        for (root in listOf("my-fork-main/", "deep/nested/dir/", "")) {
            val zip = archiveOf(
                AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))),
                root = root
            )

            val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging)

            assertEquals("root='$root'", listOf("com.a"), (result as AnipSnapshotBuilder.Result.Built).items.map { it.pkg })
            staging.deleteRecursively()
        }
    }

    @Test
    fun `first category wins for a package declared in two categories`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.x", en = "from-app"))),
            AnipZipFixtures.Category("game", listOf(AnipZipFixtures.Declared("com.x", en = "from-game")))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging, preferZh = false)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(1, built.items.size)
        assertEquals("from-app", built.items[0].appName)
        assertEquals(1, built.stats.declared)
    }

    @Test
    fun `package keys that are not valid package names are skipped`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("../evil"),
                AnipZipFixtures.Declared("com..bad"),
                AnipZipFixtures.Declared("com.ok")
            ))
        )

        val result = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(listOf("com.ok"), built.items.map { it.pkg })
        assertEquals(1, built.stats.declared)
        assertFalse(File(work, "evil.png").exists())
    }

    @Test
    fun `labels follow the preferred language`() {
        val zip = archiveOf(
            AnipZipFixtures.Category("app", listOf(
                AnipZipFixtures.Declared("com.a", zh = "甲", en = "A"),
                AnipZipFixtures.Declared("com.b", zh = "", en = "B-only"),
                AnipZipFixtures.Declared("com.c", zh = "丙-only", en = "")
            ))
        )

        val zh = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging, preferZh = true)
        staging.deleteRecursively()
        val en = AnipSnapshotBuilder.build(zip, oldDir = null, staging = staging, preferZh = false)

        val zhNames = (zh as AnipSnapshotBuilder.Result.Built).items.associate { it.pkg to it.appName }
        val enNames = (en as AnipSnapshotBuilder.Result.Built).items.associate { it.pkg to it.appName }
        assertEquals(mapOf("com.a" to "甲", "com.b" to "B-only", "com.c" to "丙-only"), zhNames)
        assertEquals(mapOf("com.a" to "A", "com.b" to "B-only", "com.c" to "丙-only"), enNames)
    }

    @Test
    fun `labels fall back to another language's text, never to a language code`() {
        // 修复批之前：只有其它语言名称的条目，应用名显示成语言键名（如 "ja"）；
        // 完全没有名称的条目显示成 "en"。
        assertEquals("日本語", AnipSnapshotBuilder.labelOf(JSONObject("""{"ja":"日本語"}"""), preferZh = false))
        assertEquals("", AnipSnapshotBuilder.labelOf(JSONObject("""{"zh-CN":"","en":""}"""), preferZh = false))
        assertEquals("", AnipSnapshotBuilder.labelOf(JSONObject(), preferZh = true))
    }
}
