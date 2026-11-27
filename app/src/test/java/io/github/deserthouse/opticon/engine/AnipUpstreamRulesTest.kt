package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批 UP（上游对齐）：AnipSnapshotBuilder 对上游三种写法的处理——别名（target）、SVG、
 * 官方资源包的目录结构——以及上限。参照上游 SDK 的语义：别名在同一个类别的清单里继承、拒绝成环。
 * 有一处与上游 SDK 不同是有意的（见 AnipSnapshotBuilder 的说明）：解析不了的别名只放过它自己，
 * 不让整份快照失败。
 *
 * 真实上游包的已知答案（924 条）不在这里：见 AnipRealBundleKnownAnswerTest。
 */
class AnipUpstreamRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val svg = AnipZipFixtures.svgOf("glyph")
    private val rendered = AnipZipFixtures.pngOf("rendered")
    private val rasterizer = AnipSnapshotBuilder.IconRasterizer { _, _ -> rendered }

    private var n = 0
    private fun next(name: String): File = File(tmp.root, "$name${n++}")

    private fun build(
        bytes: ByteArray,
        old: File? = null,
        rasterizer: AnipSnapshotBuilder.IconRasterizer = AnipSnapshotBuilder.NO_RASTERIZER,
        limits: AnipSnapshotBuilder.Limits = AnipSnapshotBuilder.DEFAULT_LIMITS
    ): Pair<AnipSnapshotBuilder.Result.Built, File> {
        val zip = next("bundle").apply { writeBytes(bytes) }
        val staging = next("staging")
        val result = AnipSnapshotBuilder.build(
            zip, oldDir = old, staging = staging, preferZh = true, rasterizer = rasterizer, limits = limits
        )
        check(result is AnipSnapshotBuilder.Result.Built) { "expected Built, got $result" }
        return result to staging
    }

    private fun buildRejected(
        bytes: ByteArray,
        limits: AnipSnapshotBuilder.Limits = AnipSnapshotBuilder.DEFAULT_LIMITS,
        rasterizer: AnipSnapshotBuilder.IconRasterizer = AnipSnapshotBuilder.NO_RASTERIZER
    ): Pair<AnipSnapshotBuilder.Result.Rejected, File> {
        val zip = next("bundle").apply { writeBytes(bytes) }
        val staging = next("staging")
        val result = AnipSnapshotBuilder.build(
            zip, oldDir = null, staging = staging, preferZh = true, rasterizer = rasterizer, limits = limits
        )
        check(result is AnipSnapshotBuilder.Result.Rejected) { "expected Rejected, got $result" }
        return result to staging
    }

    private fun png(staging: File, pkg: String): ByteArray = File(staging, "$pkg.png").readBytes()

    private fun appName(staging: File, pkg: String): String {
        val apps = JSONObject(File(staging, RuleLibrary.META_FILE).readText()).getJSONArray("apps")
        for (i in 0 until apps.length()) {
            val o = apps.getJSONObject(i)
            if (o.getString("packageName") == pkg) return o.getString("appName")
        }
        fail("no meta entry for $pkg")
        throw AssertionError()
    }

    private fun oldDirWith(vararg pkgs: String): File =
        next("old").apply { mkdirs(); pkgs.forEach { File(this, "$it.png").writeBytes(AnipZipFixtures.pngOf("old-$it")) } }

    private fun stats(vararg parts: Int) = AnipSnapshotBuilder.Stats(
        declared = parts[0], written = parts[1], carriedOver = parts[2], missing = parts[3],
        rendered = parts.getOrElse(4) { 0 }, aliased = parts.getOrElse(5) { 0 }, unresolved = parts.getOrElse(6) { 0 }
    )

    private val base = AnipZipFixtures.Declared("com.a.base", zh = "抖音", en = "Douyin", contributors = "fankes")

    // ── 别名 ──

    @Test
    fun `an alias entry gets its own copy of the target's image and keeps its own label`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(base, AnipZipFixtures.Declared("com.a.lite", zh = "抖音极速版", en = "Douyin Lite", target = "com.a.base"))
                    )
                )
            )
        )
        assertEquals(listOf("com.a.base", "com.a.lite"), built.items.map { it.pkg })
        assertArrayEquals(png(staging, "com.a.base"), png(staging, "com.a.lite"))
        assertEquals("抖音极速版", appName(staging, "com.a.lite"))
        assertEquals(stats(2, 1, 0, 0, 0, 1, 0), built.stats)
    }

    @Test
    fun `an alias that omits label and contributors inherits them from the target`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(base, AnipZipFixtures.Declared("com.a.lite", target = "com.a.base", contributors = null))
                    )
                )
            )
        )
        val lite = built.items.single { it.pkg == "com.a.lite" }
        assertEquals("抖音", lite.appName)
        assertEquals("fankes", lite.contributor)
        assertEquals("抖音", appName(staging, "com.a.lite"))
    }

    @Test
    fun `an alias with a label of its own does not inherit the target's contributors when it has none`() {
        // 反向核对：上面一条之所以继承，是因为别名自己省略了这两项，不是因为目标覆盖了别名
        val (built, _) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(base, AnipZipFixtures.Declared("com.a.lite", zh = "极速版", target = "com.a.base", contributors = "someone"))
                    )
                )
            )
        )
        val lite = built.items.single { it.pkg == "com.a.lite" }
        assertEquals("极速版", lite.appName)
        assertEquals("someone", lite.contributor)
    }

    @Test
    fun `a chain of aliases resolves to the image of the base package`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(
                            base,
                            AnipZipFixtures.Declared("com.a.mid", zh = "中转", target = "com.a.base"),
                            AnipZipFixtures.Declared("com.a.leaf", zh = "末端", target = "com.a.mid")
                        )
                    )
                )
            )
        )
        assertArrayEquals(png(staging, "com.a.base"), png(staging, "com.a.mid"))
        assertArrayEquals(png(staging, "com.a.base"), png(staging, "com.a.leaf"))
        assertEquals("末端", appName(staging, "com.a.leaf"))
        assertEquals(stats(3, 1, 0, 0, 0, 2, 0), built.stats)
    }

    @Test
    fun `cyclic aliases are left out and counted as unresolved without failing the snapshot`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(
                            AnipZipFixtures.Declared("com.a.yin", target = "com.a.yang"),
                            AnipZipFixtures.Declared("com.a.yang", target = "com.a.yin"),
                            AnipZipFixtures.Declared("com.a.self", target = "com.a.self"),
                            AnipZipFixtures.Declared("com.a.ok")
                        )
                    )
                )
            )
        )
        assertEquals(listOf("com.a.ok"), built.items.map { it.pkg })
        assertEquals(stats(4, 1, 0, 3, 0, 0, 3), built.stats)
        assertFalse(File(staging, "com.a.yin.png").exists())
    }

    @Test
    fun `an alias whose target is missing from the manifest is carried over from the old version or left out`() {
        val bytes = AnipZipFixtures.build(
            categories = listOf(
                AnipZipFixtures.Category(
                    "app",
                    listOf(base, AnipZipFixtures.Declared("com.a.ghost", target = "com.a.nobody"))
                )
            )
        )
        val (fresh, staging) = build(bytes)
        assertEquals(listOf("com.a.base"), fresh.items.map { it.pkg })
        assertEquals(stats(2, 1, 0, 1, 0, 0, 1), fresh.stats)

        val (upgraded, staging2) = build(bytes, old = oldDirWith("com.a.ghost"))
        assertArrayEquals(AnipZipFixtures.pngOf("old-com.a.ghost"), png(staging2, "com.a.ghost"))
        assertEquals(stats(2, 1, 1, 0, 0, 0, 1), upgraded.stats)
    }

    @Test
    fun `an alias whose target has no readable image falls back like the target`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(
                            AnipZipFixtures.Declared("com.a.base", zh = "底", png = null),
                            AnipZipFixtures.Declared("com.a.lite", zh = "别名", target = "com.a.base")
                        )
                    )
                )
            ),
            old = oldDirWith("com.a.lite")
        )
        assertArrayEquals(AnipZipFixtures.pngOf("old-com.a.lite"), png(staging, "com.a.lite"))
        assertEquals("别名", appName(staging, "com.a.lite"))
        assertEquals(stats(2, 0, 1, 1, 0, 0, 0), built.stats)
    }

    @Test
    fun `an alias is only resolved inside its own category`() {
        val (built, _) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(base)),
                    AnipZipFixtures.Category("game", listOf(AnipZipFixtures.Declared("com.a.lite", target = "com.a.base")))
                )
            )
        )
        assertEquals(listOf("com.a.base"), built.items.map { it.pkg })
        assertEquals(1, built.stats.unresolved)
    }

    @Test
    fun `a target that is not a valid package name is unresolved`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(AnipZipFixtures.Declared("com.a.lite", target = "../../com.a.base"), base)
                    )
                )
            )
        )
        assertEquals(listOf("com.a.base"), built.items.map { it.pkg })
        assertEquals(1, built.stats.unresolved)
        assertEquals(1, staging.listFiles()!!.count { it.name.endsWith(".png") })
    }

    // ── SVG ──

    @Test
    fun `an svg entry is rendered to png through the rasterizer`() {
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(AnipZipFixtures.Declared("com.a.svg", zh = "矢量", format = "svg", ext = "svg", png = svg))
                    )
                )
            ),
            rasterizer = rasterizer
        )
        assertArrayEquals(rendered, png(staging, "com.a.svg"))
        assertEquals("矢量", appName(staging, "com.a.svg"))
        assertEquals(stats(1, 0, 0, 0, 1, 0, 0), built.stats)
    }

    @Test
    fun `an svg entry without a rasterizer is left out, or carried over from the old version`() {
        val bytes = AnipZipFixtures.build(
            categories = listOf(
                AnipZipFixtures.Category(
                    "app",
                    listOf(AnipZipFixtures.Declared("com.a.svg", format = "svg", ext = "svg", png = svg))
                )
            )
        )
        // 没有渲染器、旧版本里也没有：这一条取不到图。整份快照只有它，所以是空的 → 被拒绝，不留下目录
        val (rejected, staging) = buildRejected(bytes)
        assertTrue(rejected.reason, rejected.reason.contains("snapshot empty"))
        assertTrue(rejected.reason, rejected.reason.contains("missing 1"))
        assertFalse(staging.exists())

        // 旧版本里有：沿用，不算撤销
        val (upgraded, staging2) = build(bytes, old = oldDirWith("com.a.svg"))
        assertArrayEquals(AnipZipFixtures.pngOf("old-com.a.svg"), png(staging2, "com.a.svg"))
        assertEquals(stats(1, 0, 1, 0), upgraded.stats)
    }

    @Test
    fun `a rasterizer that returns something that is not an image is treated as unreadable`() {
        val (built, _) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(base, AnipZipFixtures.Declared("com.a.svg", format = "svg", ext = "svg", png = svg))
                    )
                )
            ),
            rasterizer = AnipSnapshotBuilder.IconRasterizer { _, _ -> "not an image".toByteArray() }
        )
        assertEquals(listOf("com.a.base"), built.items.map { it.pkg })
        assertEquals(stats(2, 1, 0, 1), built.stats)
    }

    @Test
    fun `an entry without a format field whose only resource is svg is rendered`() {
        val (_, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a.svg", ext = "svg", png = svg)))
                )
            ),
            rasterizer = rasterizer
        )
        assertArrayEquals(rendered, png(staging, "com.a.svg"))
    }

    @Test
    fun `an image is read and rendered once for a target shared by many aliases`() {
        val calls = AtomicInteger()
        val counting = AnipSnapshotBuilder.IconRasterizer { _, max ->
            calls.incrementAndGet()
            assertTrue("rasterizer must be told the dimension limit", max == 150)
            rendered
        }
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(
                            AnipZipFixtures.Declared("com.a.glyph", zh = "字形", format = "svg", ext = "svg", png = svg),
                            AnipZipFixtures.Declared("com.a.one", target = "com.a.glyph"),
                            AnipZipFixtures.Declared("com.a.two", target = "com.a.glyph"),
                            AnipZipFixtures.Declared("com.a.three", target = "com.a.glyph")
                        )
                    )
                )
            ),
            rasterizer = counting
        )
        assertEquals(1, calls.get())
        for (pkg in listOf("com.a.glyph", "com.a.one", "com.a.two", "com.a.three")) assertArrayEquals(rendered, png(staging, pkg))
        assertEquals(stats(4, 0, 0, 0, 1, 3, 0), built.stats)
        assertEquals("字形", appName(staging, "com.a.three"))
    }

    // ── 目录结构 ──

    @Test
    fun `the official bundle layout parses exactly like the whole-repo layout`() {
        val categories = listOf(
            AnipZipFixtures.Category(
                "app",
                listOf(
                    base,
                    AnipZipFixtures.Declared("com.a.lite", zh = "极速版", target = "com.a.base"),
                    AnipZipFixtures.Declared("com.a.svg", format = "svg", ext = "svg", png = svg)
                )
            ),
            AnipZipFixtures.Category("game", listOf(AnipZipFixtures.Declared("com.g.one", zh = "游戏")))
        )
        val (repo, repoStaging) = build(AnipZipFixtures.build(categories = categories), rasterizer = rasterizer)
        val (bundle, bundleStaging) = build(AnipZipFixtures.build(root = "", categories = categories, iconsDir = false), rasterizer = rasterizer)
        assertEquals(repo.items, bundle.items)
        assertEquals(repo.stats, bundle.stats)
        assertEquals(
            repoStaging.listFiles()!!.sortedBy { it.name }.associate { it.name to it.readBytes().size },
            bundleStaging.listFiles()!!.sortedBy { it.name }.associate { it.name to it.readBytes().size }
        )
        assertEquals(stats(4, 2, 0, 0, 1, 1, 0), bundle.stats)
    }

    @Test
    fun `the official bundle layout under a wrapper directory is detected`() {
        val (built, staging) = build(
            AnipZipFixtures.build(root = "anip-bundle-e98c906/", categories = listOf(AnipZipFixtures.Category("app", listOf(base))), iconsDir = false)
        )
        assertEquals(listOf("com.a.base"), built.items.map { it.pkg })
        assertArrayEquals(AnipZipFixtures.pngOf("com.a.base"), png(staging, "com.a.base"))
    }

    @Test
    fun `an archive without any category manifest is rejected`() {
        val (rejected, staging) = buildRejected(AnipZipFixtures.build(categories = emptyList()))
        assertTrue(rejected.reason, rejected.reason.contains("no ANIP category manifest"))
        assertFalse("拒绝后不留半成品", staging.exists())
    }

    // ── 上限 ──

    @Test
    fun `an icon entry above the resource limit is left out and the rest of the snapshot is built`() {
        val big = AnipZipFixtures.pngOf("big") + ByteArray(200) { 7 }
        val (built, staging) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category("app", listOf(base, AnipZipFixtures.Declared("com.a.big", png = big)))
                )
            ),
            limits = AnipSnapshotBuilder.Limits(resourceBytes = 64)
        )
        assertEquals(listOf("com.a.base"), built.items.map { it.pkg })
        assertEquals(stats(2, 1, 0, 1), built.stats)
        assertFalse(File(staging, "com.a.big.png").exists())
    }

    @Test
    fun `a manifest above its limit rejects the snapshot`() {
        val (rejected, staging) = buildRejected(
            AnipZipFixtures.build(categories = listOf(AnipZipFixtures.Category("app", listOf(base)))),
            limits = AnipSnapshotBuilder.Limits(manifestBytes = 16)
        )
        assertTrue(rejected.reason, rejected.reason.contains("too large"))
        assertFalse(staging.exists())
    }

    @Test
    fun `an archive whose uncompressed total exceeds the limit is rejected`() {
        val (rejected, staging) = buildRejected(
            AnipZipFixtures.build(categories = listOf(AnipZipFixtures.Category("app", listOf(base)))),
            limits = AnipSnapshotBuilder.Limits(totalUncompressedBytes = 40)
        )
        assertTrue(rejected.reason, rejected.reason.contains("exceeds limit"))
        assertFalse(staging.exists())
    }

    @Test
    fun `an archive file above the archive limit is rejected before it is opened`() {
        val (rejected, staging) = buildRejected(
            AnipZipFixtures.build(categories = listOf(AnipZipFixtures.Category("app", listOf(base)))),
            limits = AnipSnapshotBuilder.Limits(archiveBytes = 16)
        )
        assertTrue(rejected.reason, rejected.reason.contains("archive too large"))
        assertFalse(staging.exists())
    }

    // ── 统计 ──

    @Test
    fun `stats split a mixed snapshot and still add up to the declared count`() {
        val (built, _) = build(
            AnipZipFixtures.build(
                categories = listOf(
                    AnipZipFixtures.Category(
                        "app",
                        listOf(
                            base,
                            AnipZipFixtures.Declared("com.a.svg", format = "svg", ext = "svg", png = svg),
                            AnipZipFixtures.Declared("com.a.lite", target = "com.a.base"),
                            AnipZipFixtures.Declared("com.a.ghost", target = "com.a.nobody"),
                            AnipZipFixtures.Declared("com.a.broken", png = null)
                        )
                    )
                )
            ),
            rasterizer = rasterizer
        )
        assertEquals(stats(5, 1, 0, 2, 1, 1, 1), built.stats)
        val s = built.stats
        assertEquals(s.declared, s.written + s.rendered + s.aliased + s.carriedOver + s.missing)
        assertTrue(s.unresolved <= s.carriedOver + s.missing)
    }
}
