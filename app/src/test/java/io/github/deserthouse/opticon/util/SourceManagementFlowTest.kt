package io.github.deserthouse.opticon.util

import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.engine.AnipSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自定义源管理的流程测试（修复批 X-01 / X-02 / X-14）：走 PreferenceManager 的真实入口，
 * 假配置存储真正落盘。对应设备验证 D1 / D2 / D3 的 JVM 版。
 *
 * 落盘键名是契约的一部分，这里按字面量书写：
 *   custom_sub_sources / active_ania_source / active_picp_source / custom_sources_formats_migrated
 */
class SourceManagementFlowTest {

    private lateinit var prefs: InMemoryPrefs

    @Before
    fun setUp() {
        prefs = InMemoryPrefs()
        PreferenceManager.initForTest(prefs)
    }

    /** 按升级前的落盘形态注入：自定义源没有格式字段，选中指针指向它。 */
    private fun seedBeforeUpgrade(vararg entries: JSONObject, activeAnia: String? = null, activePicp: String? = null) {
        prefs.edit().putString("custom_sub_sources", JSONArray(entries.toList()).toString()).apply()
        activeAnia?.let { prefs.edit().putString("active_ania_source", it).apply() }
        activePicp?.let { prefs.edit().putString("active_picp_source", it).apply() }
    }

    /** 0.19.6-beta 留下的形态：记录已带（可能被判错的）格式字段。 */
    private fun legacyEntry(id: String, url: String, format: String? = null): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", id)
            put("url", url)
            put("description", "")
            format?.let { put("format", it) }
        }

    private fun storedFormats(): Map<String, String> {
        val arr = JSONArray(prefs.getString("custom_sub_sources", null) ?: return emptyMap())
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .associate { it.getString("id") to it.getString("format") }
    }

    private fun customIds(sources: List<PreferenceManager.SubscriptionSource>) =
        sources.filter { !it.isBuiltin }.map { it.id }.toSet()

    private val picpUrl = "https://example.com/pack.zip"

    // ━━ X-01：添加首个自定义源 ━━

    @Test
    fun `no sources - adding a PICP source keeps its format and lists it under app icon sources`() {
        val added = PreferenceManager.addCustomSource("mine", picpUrl, "", SourceFormat.PICP_ZIPBALL)

        assertEquals(setOf(added.id), customIds(PreferenceManager.getPicpSources()))
        assertTrue(PreferenceManager.getAniaSources().none { it.id == added.id })
        assertEquals(SourceFormat.PICP_ZIPBALL, PreferenceManager.getSourceFormat(added.id))
        assertEquals(picpUrl, PreferenceManager.getSourceUrl(added.id))
        assertEquals("PICP_ZIPBALL", storedFormats()[added.id])
    }

    @Test
    fun `no sources - adding an ANIP source lists it under notification icon sources`() {
        val added = PreferenceManager.addCustomSource(
            "fork", "https://example.com/fork/archive.zip", "", SourceFormat.ANIP_BUNDLE
        )

        assertEquals(setOf(added.id), customIds(PreferenceManager.getAniaSources()))
        assertTrue(PreferenceManager.getPicpSources().none { it.id == added.id })
        assertEquals("ANIP_BUNDLE", storedFormats()[added.id])
    }

    @Test
    fun `migration marker is written on first read even without custom sources`() {
        assertNull(prefs.getString("custom_sources_formats_migrated", null))

        PreferenceManager.getPicpSources()

        assertNotNull(prefs.getString("custom_sources_formats_migrated", null))
        // 没有自定义源时不留下空列表
        assertNull(prefs.getString("custom_sub_sources", null))
    }

    @Test
    fun `two sources added within the same millisecond get distinct ids`() {
        val a = PreferenceManager.addCustomSource("a", picpUrl, "", SourceFormat.PICP_ZIPBALL)
        val b = PreferenceManager.addCustomSource("b", picpUrl, "", SourceFormat.PICP_ZIPBALL)

        assertNotEquals(a.id, b.id)
        assertEquals(setOf(a.id, b.id), customIds(PreferenceManager.getPicpSources()))
    }

    // ━━ X-02：升级前已有的自定义源 ━━

    @Test
    fun `upgrade with a selected custom zipball source keeps it listed and selected`() {
        // 0.19.5 形态：没有格式字段，被选为当前应用图标源
        seedBeforeUpgrade(legacyEntry("custom_1", picpUrl), activePicp = "custom_1")

        val picp = PreferenceManager.getPicpSources()

        assertEquals(setOf("custom_1"), customIds(picp))
        assertEquals("custom_1", PreferenceManager.getActivePicpSourceId())
        assertEquals(SourceFormat.PICP_ZIPBALL, PreferenceManager.getSourceFormat("custom_1"))
        assertTrue(PreferenceManager.getAniaSources().none { it.id == "custom_1" })
    }

    @Test
    fun `upgrade with an undecidable legacy source shows it in both lists and routing refuses`() {
        seedBeforeUpgrade(legacyEntry("custom_1", "https://example.com/mystery"))

        assertEquals(setOf("custom_1"), customIds(PreferenceManager.getPicpSources()))
        assertEquals(setOf("custom_1"), customIds(PreferenceManager.getAniaSources()))
        assertEquals(SourceFormat.UNSPECIFIED, PreferenceManager.getSourceFormat("custom_1"))
        assertEquals("picp_github", PreferenceManager.getActivePicpSourceId()) // 指针不动
    }

    @Test
    fun `records that already carry a format are not rewritten by the migration`() {
        seedBeforeUpgrade(legacyEntry("custom_1", AnipSync.BASE, format = "LEGACY_JSON"))

        PreferenceManager.getAniaSources()

        assertEquals(mapOf("custom_1" to "LEGACY_JSON"), storedFormats())
    }

    @Test
    fun `X-01 sequence - a formatted record read before the marker exists keeps its format`() {
        // 原始触发顺序：盘上已有一条带格式字段的记录（刚添加的源），迁移标记还没写，
        // 这时发生第一次读取。0.19.6-beta 在这一步把它按地址改判成了 Legacy JSON。
        seedBeforeUpgrade(legacyEntry("custom_new", picpUrl, format = "PICP_ZIPBALL"))
        assertNull(prefs.getString("custom_sources_formats_migrated", null))

        assertEquals(setOf("custom_new"), customIds(PreferenceManager.getPicpSources()))
        assertEquals("PICP_ZIPBALL", storedFormats()["custom_new"])
        assertTrue(PreferenceManager.getAniaSources().none { it.id == "custom_new" })
        assertNotNull(prefs.getString("custom_sources_formats_migrated", null))
    }

    // ━━ X-14：选中指针校正 ━━

    @Test
    fun `changing the selected source's format moves it to the other list and resets the pointer`() {
        val added = PreferenceManager.addCustomSource("mine", picpUrl, "", SourceFormat.PICP_ZIPBALL)
        PreferenceManager.setActivePicpSourceId(added.id)

        PreferenceManager.updateCustomSource(added.id, "mine", picpUrl, "", SourceFormat.LEGACY_JSON)

        assertEquals("picp_github", PreferenceManager.getActivePicpSourceId())
        assertTrue(PreferenceManager.getPicpSources().none { it.id == added.id })
        assertEquals(setOf(added.id), customIds(PreferenceManager.getAniaSources()))
    }

    @Test
    fun `removing the selected source resets the pointer`() {
        val added = PreferenceManager.addCustomSource("mine", picpUrl, "", SourceFormat.PICP_ZIPBALL)
        PreferenceManager.setActivePicpSourceId(added.id)

        PreferenceManager.removeCustomSource(added.id)

        assertEquals("picp_github", PreferenceManager.getActivePicpSourceId())
        assertTrue(PreferenceManager.getPicpSources().none { it.id == added.id })
    }

    @Test
    fun `startup reconcile repairs a pointer left dangling by 0_19_6`() {
        // 0.19.6-beta 上把选中源的格式改掉之后留下的状态：指针指向一个不在该列表里的源
        seedBeforeUpgrade(legacyEntry("custom_1", "https://example.com/legacy.json", format = "LEGACY_JSON"), activePicp = "custom_1")

        PreferenceManager.reconcileActivePointers()

        assertEquals("picp_github", PreferenceManager.getActivePicpSourceId())
        assertEquals("ania_raw", PreferenceManager.getActiveAniaSourceId()) // 没有被这次对账动过
    }
}
