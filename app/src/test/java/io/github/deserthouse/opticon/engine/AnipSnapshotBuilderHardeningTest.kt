package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AnipSnapshotBuilder 的加固（第二轮审查 N-08 与待验证风险 R-01）。
 *
 *  - 条目数据要与中央目录登记的长度和 CRC 相符才算「成功写出」；否则按「读不出」走沿用旧图；
 *  - 上一个版本里有、这次归档里整个类别都消失时，沿用旧图标，不当作撤销。
 */
class AnipSnapshotBuilderHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var work: File
    private val old get() = File(work, "old")
    private val staging get() = File(work, "staging")

    @Before
    fun setUp() {
        work = tmp.newFolder("anip")
    }

    private fun manifest(vararg pkgs: String): String =
        pkgs.joinToString(",", "{", "}") {
            "\"$it\": {\"label\": {\"en\": \"$it\"}, \"contributors\": \"x\"}"
        }

    private fun build(zip: File) = AnipSnapshotBuilder.build(zip, oldDir = old, staging = staging, preferZh = false)

    @Test
    fun `entry whose data does not match its declared checksum is not accepted`() {
        val payload = AnipZipFixtures.pngOf("com.a") + "payload-0123456789".toByteArray()
        val good = PicpZipFixtures.buildStored(
            mapOf(
                "r/icons/app/manifest.json" to manifest("com.a").toByteArray(),
                "r/icons/app/res/com.a.png" to payload
            )
        )
        // 阳性对照：不破坏时按正常写出处理
        val control = build(PicpZipFixtures.write(File(work, "good.zip"), good)) as AnipSnapshotBuilder.Result.Built
        assertEquals(1, control.stats.written)

        // 文件头完好、中间坏了一个字节：长度不变、CRC 对不上
        val at = PicpZipFixtures.indexOf(good, payload)
        assertTrue(at > 0)
        val damaged = good.copyOf()
        damaged[at + 24] = (damaged[at + 24].toInt() xor 0x40).toByte()
        old.mkdirs()
        File(old, "com.a.png").writeBytes("old-com.a".toByteArray())
        staging.deleteRecursively()

        val result = build(PicpZipFixtures.write(File(work, "bad.zip"), damaged))

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(AnipSnapshotBuilder.Stats(declared = 1, written = 0, carriedOver = 1, missing = 0), built.stats)
        assertEquals("old-com.a", File(staging, "com.a.png").readText()) // 沿用旧图，坏图不进新快照
    }

    @Test
    fun `a category whose manifest vanished from the archive is carried over, not revoked`() {
        // 上一个版本（本版写出的快照，meta 里带类别）：app 类一个包、game 类一个包
        old.mkdirs()
        File(old, "com.app.png").writeBytes("old-com.app".toByteArray())
        File(old, "com.game.png").writeBytes("old-com.game".toByteArray())
        File(old, RuleLibrary.META_FILE).writeText(
            """{"apps":[
                {"packageName":"com.app","appName":"App","contributorName":"c","isEnabled":true,"category":"app"},
                {"packageName":"com.game","appName":"Game","contributorName":"c","isEnabled":true,"category":"game"}
            ]}""".trimIndent()
        )
        // 新归档里只有 app 的清单（game 整类不见了：分不清是撤销、漏打包还是改了目录）
        val zip = File(work, "vanish.zip")
        zip.writeBytes(AnipZipFixtures.build(categories = listOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.app")))
        )))

        val result = build(zip)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(setOf("com.app", "com.game"), built.items.map { it.pkg }.toSet())
        assertEquals(1, built.stats.written)
        assertEquals(1, built.stats.carriedOver)
        assertEquals("old-com.game", File(staging, "com.game.png").readText())
        // 两个包都带着类别，下一次同步还能继续保护
        val meta = JSONObject(File(staging, RuleLibrary.META_FILE).readText()).getJSONArray("apps")
        assertEquals("app", meta.getJSONObject(0).getString("category"))
        assertEquals("game", meta.getJSONObject(1).getString("category"))
    }

    @Test
    fun `a package that is absent from a manifest that is still there is revoked as usual`() {
        // 对照：清单还在、只是里面少了这个包 → 这才是上游撤销
        old.mkdirs()
        File(old, "com.a.png").writeText("old-a")
        File(old, "com.b.png").writeText("old-b")
        File(old, RuleLibrary.META_FILE).writeText(
            """{"apps":[{"packageName":"com.a","category":"app"},{"packageName":"com.b","category":"app"}]}"""
        )
        val zip = File(work, "revoke.zip")
        zip.writeBytes(AnipZipFixtures.build(categories = listOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a")))
        )))

        val result = build(zip)

        val built = result as AnipSnapshotBuilder.Result.Built
        assertEquals(listOf("com.a"), built.items.map { it.pkg })
        assertFalse(File(staging, "com.b.png").exists())
        assertEquals(0, built.stats.carriedOver)
    }

    @Test
    fun `a snapshot written by this version records the category of every package`() {
        val zip = File(work, "fresh.zip")
        zip.writeBytes(AnipZipFixtures.build(categories = listOf(
            AnipZipFixtures.Category("app", listOf(AnipZipFixtures.Declared("com.a"))),
            AnipZipFixtures.Category("game", listOf(AnipZipFixtures.Declared("com.g")))
        )))

        val result = build(zip)

        assertTrue(result is AnipSnapshotBuilder.Result.Built)
        val meta = JSONObject(File(staging, RuleLibrary.META_FILE).readText()).getJSONArray("apps")
        assertEquals(
            mapOf("com.a" to "app", "com.g" to "game"),
            (0 until meta.length()).associate { meta.getJSONObject(it).getString("packageName") to meta.getJSONObject(it).getString("category") }
        )
    }
}
