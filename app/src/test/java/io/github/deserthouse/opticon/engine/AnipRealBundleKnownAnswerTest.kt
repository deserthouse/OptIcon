package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实上游包的已知答案（批 UP）。平时不跑：包不入库，要跑得指明它的位置——
 *
 *   bash scripts/anip_known_answer.sh <anip-bundle-<提交>.zip>
 *
 * 2026-11-27 核查时上游清单 924 条，OptIcon 只进 814 条（别名 100 条与 SVG 10 条被记为缺失）。
 * 这份测试钉住的数字是：声明 924、条目 924（位图 814 + SVG 10 + 别名 100）、缺失 0、未解析 0。
 * 上游再更新时数字会变：把新包跑一遍、逐项核对差异之后再改这里的期望值，不要直接照抄新输出。
 */
class AnipRealBundleKnownAnswerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `every entry declared by the real upstream bundle lands in the snapshot`() {
        val bundle = File(System.getProperty("opticon.anip.bundle") ?: error("没有设置 -Dopticon.anip.bundle"))
        assertTrue("找不到文件：$bundle", bundle.isFile)
        val staging = File(tmp.root, "staging")
        val renders = AtomicInteger()
        val rasterizer = AnipSnapshotBuilder.IconRasterizer { _, max ->
            renders.incrementAndGet()
            AnipZipFixtures.pngOf("rendered-$max")
        }

        val result = AnipSnapshotBuilder.build(
            bundle, oldDir = null, staging = staging, preferZh = true, rasterizer = rasterizer
        )
        check(result is AnipSnapshotBuilder.Result.Built) { "expected Built, got $result" }

        assertEquals(AnipSnapshotBuilder.Stats(declared = 924, written = 814, carriedOver = 0, missing = 0, rendered = 10, aliased = 100, unresolved = 0), result.stats)
        assertEquals(924, result.items.size)
        assertEquals(924, staging.listFiles()!!.count { it.name.endsWith(".png") })
        assertEquals("每个 SVG 只渲染一次", 10, renders.get())

        fun bytes(pkg: String) = File(staging, "$pkg.png").readBytes()
        val byPkg = result.items.associateBy { it.pkg }

        // 别名：抖音极速版用的是抖音的图，名字是自己的
        assertArrayEquals(bytes("com.ss.android.ugc.aweme"), bytes("com.ss.android.ugc.aweme.lite"))
        assertEquals("抖音极速版", byPkg.getValue("com.ss.android.ugc.aweme.lite").appName)
        // SVG：理想汽车（2026-11-26 上游新增，此前进不到 OptIcon）
        assertArrayEquals(AnipZipFixtures.pngOf("rendered-150"), bytes("com.chehejia.oc.m01"))
        assertEquals("理想汽车", byPkg.getValue("com.chehejia.oc.m01").appName)
        // 位图：原样落盘
        assertTrue(bytes("com.tencent.mm").size > 12)

        val apps = JSONObject(File(staging, RuleLibrary.META_FILE).readText()).getJSONArray("apps")
        assertEquals(924, apps.length())
    }
}
