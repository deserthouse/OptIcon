package io.github.deserthouse.opticon.util

import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.engine.AnipSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceMigration 单测（修复批 X-01 / X-02 / X-14）。
 *
 * 迁移只作用于缺格式字段的旧记录；带格式字段的原样保留。旧记录按证据推断，
 * 证据不足标「待确认」而不是猜。选中指针必须落在对应当的列表里。
 */
class SourceMigrationTest {

    private val default = SourceMigration.Pointers("ania_raw", "picp_github")

    private fun entry(id: String, url: String = "https://example.com/$id.zip", format: String? = null): String =
        JSONObject().apply {
            put("id", id)
            put("name", id)
            put("url", url)
            format?.let { put("format", it) }
        }.toString()

    private fun raw(vararg entries: String): String =
        JSONArray().apply { entries.forEach { put(JSONObject(it)) } }.toString()

    private fun formatsOf(json: String): Map<String, String> {
        val arr = JSONArray(json)
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .associate { it.getString("id") to it.getString("format") }
    }

    // ━━ 带格式字段的记录：不碰 ━━

    @Test
    fun `record with a format field is left alone even when the url suggests otherwise`() {
        val json = raw(entry("custom_1", url = AnipSync.BASE, format = "PICP_ZIPBALL"))

        val outcome = SourceMigration.migrate(json, default)

        assertEquals(json, outcome.rawJson)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a source the user just added keeps the format they chose`() {
        // X-01 的根因场景：刚添加的源（带格式字段）在第一次读取时被迁移按地址覆盖
        val json = raw(
            entry("custom_new", url = "https://example.com/legacy.json", format = "PICP_ZIPBALL"),
            entry("custom_other", url = AnipSync.BASE, format = "LEGACY_JSON")
        )

        val outcome = SourceMigration.migrate(json, default)

        assertEquals(json, outcome.rawJson)
    }

    // ━━ 缺格式字段的旧记录：按证据推断 ━━

    @Test
    fun `legacy record that was the selected app icon source becomes PICP`() {
        // X-02 的根因场景：0.19.5 用户添加并选中过的自定义 zipball 源
        val json = raw(entry("custom_1", url = "https://example.com/pack.zip"))

        val outcome = SourceMigration.migrate(
            json,
            SourceMigration.Pointers("ania_raw", "custom_1")
        )

        assertEquals(mapOf("custom_1" to "PICP_ZIPBALL"), formatsOf(outcome.rawJson!!))
        assertEquals("custom_1", outcome.pointers.activePicp) // 选中不变
        assertTrue(outcome.changed)
    }

    @Test
    fun `legacy record under the ANIP base becomes an ANIP bundle`() {
        val json = raw(entry("custom_1", url = AnipSync.BASE))

        val outcome = SourceMigration.migrate(json, default)

        assertEquals(mapOf("custom_1" to "ANIP_BUNDLE"), formatsOf(outcome.rawJson!!))
    }

    @Test
    fun `legacy record that was the selected notification source becomes legacy JSON`() {
        val json = raw(entry("custom_1", url = "https://example.com/legacy.json"))

        val outcome = SourceMigration.migrate(
            json,
            SourceMigration.Pointers("custom_1", "picp_github")
        )

        assertEquals(mapOf("custom_1" to "LEGACY_JSON"), formatsOf(outcome.rawJson!!))
        assertEquals("custom_1", outcome.pointers.activeAnia)
    }

    @Test
    fun `legacy record with no evidence is marked unspecified and pointers are left alone`() {
        val json = raw(entry("custom_1", url = "https://example.com/mystery"))

        val outcome = SourceMigration.migrate(json, default)

        assertEquals(mapOf("custom_1" to "UNSPECIFIED"), formatsOf(outcome.rawJson!!))
        assertEquals(default, outcome.pointers)
    }

    @Test
    fun `an unspecified record is not re-inferred later`() {
        // 第二次迁移（或换一台设备恢复的备份）：已经标了待确认的记录不会被指针改判
        val json = raw(entry("custom_1", url = "https://example.com/mystery", format = "UNSPECIFIED"))

        val outcome = SourceMigration.migrate(json, SourceMigration.Pointers("ania_raw", "custom_1"))

        assertEquals(json, outcome.rawJson)
        assertEquals("custom_1", outcome.pointers.activePicp) // 待确认的源可以留在应用图标源列表里
    }

    @Test
    fun `unknown stored format becomes unspecified instead of a guess`() {
        val json = raw(entry("custom_1", format = "SOMETHING_ELSE"))

        val outcome = SourceMigration.migrate(json, default)

        assertEquals(mapOf("custom_1" to "UNSPECIFIED"), formatsOf(outcome.rawJson!!))
    }

    @Test
    fun `corrupt persisted json is handed back untouched`() {
        val outcome = SourceMigration.migrate("{not an array", default)

        assertEquals("{not an array", outcome.rawJson)
        assertEquals(default, outcome.pointers)
        assertFalse(outcome.changed)
    }

    @Test
    fun `no custom sources leaves pointers alone`() {
        val outcome = SourceMigration.migrate(null, default)

        assertNull(outcome.rawJson)
        assertEquals(default, outcome.pointers)
        assertFalse(outcome.changed)
    }

    // ━━ 选中指针校正 ━━

    @Test
    fun `dangling pointers fall back to the builtins`() {
        val outcome = SourceMigration.migrate(
            raw(entry("custom_1", format = "LEGACY_JSON")),
            SourceMigration.Pointers("custom_gone", "custom_1")
        )

        assertEquals(SourceMigration.Pointers("ania_raw", "picp_github"), outcome.pointers)
    }

    @Test
    fun `a pointer at a source whose format left its list falls back to the builtin`() {
        val fixed = SourceMigration.reconcilePointers(
            SourceMigration.Pointers("custom_picp", "custom_legacy"),
            mapOf(
                "custom_picp" to SourceFormat.PICP_ZIPBALL,
                "custom_legacy" to SourceFormat.LEGACY_JSON
            )
        )

        assertEquals(SourceMigration.Pointers("ania_raw", "picp_github"), fixed)
    }

    @Test
    fun `valid pointers are kept`() {
        val current = SourceMigration.Pointers("custom_legacy", "custom_picp")
        val fixed = SourceMigration.reconcilePointers(
            current,
            mapOf(
                "custom_picp" to SourceFormat.PICP_ZIPBALL,
                "custom_legacy" to SourceFormat.LEGACY_JSON
            )
        )
        assertEquals(current, fixed)

        // 待确认的源在两个列表里都有效
        val undecided = SourceMigration.Pointers("custom_x", "custom_x")
        assertEquals(
            undecided,
            SourceMigration.reconcilePointers(undecided, mapOf("custom_x" to SourceFormat.UNSPECIFIED))
        )
    }
}
