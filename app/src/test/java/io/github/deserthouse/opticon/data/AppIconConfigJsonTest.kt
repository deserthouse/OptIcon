package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AppIconConfig JSON 序列化单测（F1-2）。
 * 快照 JSON 是 PublishTransaction 意图记录与恢复提交的载体——转义与往返
 * 必须精确，否则恢复路径会把错误配置提交进偏好。
 */
class AppIconConfigJsonTest {

    private fun fullConfig() = AppIconConfig(
        pkg = "com.example.app",
        enabled = true,
        strategy = IconStrategy.ALGORITHM,
        assetSubStrategy = AssetSubStrategy.ADAPTIVE_DECOMPOSE,
        algoSource = AlgoSource.LOCAL_FILE,
        iconPackPkg = "com.donnno.arcticons.light",
        packDrawable = "icon_pack_entry",
        customIconPath = "content://docs/abc",
        materialIconName = "settings",
        emojiText = "🐺",
        params = RedrawParams(1.25f, 3.5f, -2f, 137, 0.5f)
    )

    @Test
    fun `full config round trips exactly`() {
        val c = fullConfig()
        assertEquals(c, AppIconConfig.fromJson(c.toJson()))
    }

    @Test
    fun `minimal config round trips with defaults`() {
        val c = AppIconConfig(pkg = "com.a", enabled = false, strategy = IconStrategy.FANKES)
        val back = AppIconConfig.fromJson(c.toJson())!!
        assertEquals(c, back)
        // 可空字段在 JSON 里为空串，读回应是 null 而非 ""
        assertNull(back.customIconPath)
        assertNull(back.algoSource)
    }

    @Test
    fun `special characters in path survive escaping`() {
        val tricky = """content://x/"quoted"/back\slash/狼🌸"""
        val c = fullConfig().copy(customIconPath = tricky)
        val back = AppIconConfig.fromJson(c.toJson())!!
        assertEquals(tricky, back.customIconPath)
    }

    @Test
    fun `json text with newlines and quotes round trips`() {
        val tricky = "line1\nline2\ttabbed \"q\" \\ end"
        val c = fullConfig().copy(emojiText = tricky)
        assertEquals(tricky, AppIconConfig.fromJson(c.toJson())!!.emojiText)
    }

    @Test
    fun `unknown strategy falls back to FANKES`() {
        val json = fullConfig().copy(strategy = IconStrategy.FANKES).toJson()
            .replace("\"strategy\":\"FANKES\"", "\"strategy\":\"NOT_A_STRATEGY\"")
        assertEquals(IconStrategy.FANKES, AppIconConfig.fromJson(json)!!.strategy)
    }

    @Test
    fun `missing pkg yields null not garbage`() {
        assertNull(AppIconConfig.fromJson("""{"sv":"1","enabled":"true"}"""))
        assertNull(AppIconConfig.fromJson("not json"))
    }

    @Test
    fun `schema version round trips`() {
        val c = fullConfig().copy(schemaVersion = 2)
        assertEquals(2, AppIconConfig.fromJson(c.toJson())!!.schemaVersion)
    }

    @Test
    fun `params survive to json and back`() {
        val back = AppIconConfig.fromJson(fullConfig().toJson())!!
        assertEquals(1.25f, back.params.scale, 1e-6f)
        assertEquals(3.5f, back.params.offsetX, 1e-6f)
        assertEquals(-2f, back.params.offsetY, 1e-6f)
        assertEquals(137, back.params.threshold)
        assertEquals(0.5f, back.params.radius, 1e-6f)
    }

    @Test
    fun `fromJson result validates when source was valid`() {
        val c = fullConfig()
        assertNotNull(AppIconConfig.fromJson(c.toJson())!!.validate().takeIf { it.isEmpty() })
    }
}
