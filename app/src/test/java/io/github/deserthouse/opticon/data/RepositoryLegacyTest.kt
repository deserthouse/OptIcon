package io.github.deserthouse.opticon.data

import android.content.SharedPreferences
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AppIconConfigRepository 单测（F1-2）：
 * legacy method_ 键迁移（幂等）+ 单 Editor 事务 save（commit 返回值即成败）。
 * JVM 假体 SharedPreferences——接口实现不触 stub 方法体。
 */
class RepositoryLegacyTest {

    private class FakeEditor(private val store: FakePrefs) : SharedPreferences.Editor {
        val pending = HashMap<String, Any>()
        val removals = HashSet<String>()
        var commitCalls = 0
        var nextCommitResult = true

        override fun putString(k: String, v: String?): SharedPreferences.Editor {
            if (v == null) { removals.add(k); pending.remove(k) } else { pending[k] = v; removals.remove(k) }
            return this
        }
        override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor {
            pending[k] = v ?: emptySet<String>(); return this
        }
        override fun putInt(k: String, v: Int): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putLong(k: String, v: Long): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putFloat(k: String, v: Float): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { pending[k] = v; return this }
        override fun remove(k: String): SharedPreferences.Editor { removals.add(k); pending.remove(k); return this }
        override fun clear(): SharedPreferences.Editor { pending.clear(); removals.clear(); return this }
        override fun apply() {}
        override fun commit(): Boolean {
            store.commitCount++
            if (!store.nextCommitResult) return false
            store.data.putAll(pending)
            removals.forEach { store.data.remove(it) }
            removals.clear(); pending.clear()
            return true
        }
    }

    private class FakePrefs : SharedPreferences {
        val data = LinkedHashMap<String, Any>()
        var nextCommitResult = true
        var commitCount = 0

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(k: String, def: String?): String? = data[k] as? String ?: def
        override fun getStringSet(k: String, def: MutableSet<String>?): MutableSet<String>? = data[k] as? MutableSet<String> ?: def
        override fun getInt(k: String, def: Int): Int = data[k] as? Int ?: def
        override fun getLong(k: String, def: Long): Long = data[k] as? Long ?: def
        override fun getFloat(k: String, def: Float): Float = data[k] as? Float ?: def
        override fun getBoolean(k: String, def: Boolean): Boolean = data[k] as? Boolean ?: def
        override fun contains(k: String): Boolean = data.containsKey(k)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private lateinit var prefs: FakePrefs
    private var commitCount = 0

    @Before
    fun setup() {
        prefs = FakePrefs()
        PreferenceManager.initForTest(prefs)
        commitCount = 0
    }

    // ━━ legacy 迁移 ━━

    @Test
    fun `legacy REMOTE_SUB migrates to FANKES enabled`() {
        prefs.data["method_com.a"] = "REMOTE_SUB"
        prefs.data["method_enabled_com.a"] = true
        val c = AppIconConfigRepository.load("com.a")
        assertEquals(IconStrategy.FANKES, c.strategy)
        assertTrue(c.enabled)
        // 写回新键（幂等载体）
        assertEquals("FANKES", prefs.data["strategy_com.a"])
    }

    @Test
    fun `legacy SELF_FILTER migrates to ALGORITHM SELF`() {
        prefs.data["method_com.a"] = "SELF_FILTER"
        val c = AppIconConfigRepository.load("com.a")
        assertEquals(IconStrategy.ALGORITHM, c.strategy)
        assertEquals(AlgoSource.SELF, c.algoSource)
        assertTrue(c.enabled)
    }

    @Test
    fun `legacy ICON_PACK migrates to ASSET_IMPORT`() {
        prefs.data["method_com.a"] = "ICON_PACK"
        assertEquals(IconStrategy.ASSET_IMPORT, AppIconConfigRepository.load("com.a").strategy)
    }

    @Test
    fun `legacy AUTO migrates to FANKES disabled`() {
        prefs.data["method_com.a"] = "AUTO"
        val c = AppIconConfigRepository.load("com.a")
        assertEquals(IconStrategy.FANKES, c.strategy)
        assertFalse(c.enabled)
    }

    @Test
    fun `migration is idempotent - no rewrite after strategy key exists`() {
        prefs.data["method_com.a"] = "REMOTE_SUB"
        AppIconConfigRepository.load("com.a")
        assertEquals("FANKES", prefs.data["strategy_com.a"])
        // 二次 load：strategy 键已在 → 不再进入迁移分支
        AppIconConfigRepository.load("com.a")
        assertEquals("FANKES", prefs.data["strategy_com.a"])
    }

    @Test
    fun `fresh install without any keys writes nothing`() {
        AppIconConfigRepository.load("com.a")
        assertNull(prefs.data["strategy_com.a"])
    }

    // ━━ save 单事务 ━━

    @Test
    fun `save round trips full snapshot in one commit`() {
        val config = AppIconConfig(
            pkg = "com.a", enabled = true, strategy = IconStrategy.ALGORITHM,
            algoSource = AlgoSource.LOCAL_FILE, customIconPath = "content://x",
            params = io.github.deserthouse.opticon.engine.RedrawParams(1.5f, 4f, -3f, 200, 1f)
        )
        val commitsBefore = currentCommitCount()
        assertTrue(AppIconConfigRepository.save(config))
        assertEquals(commitsBefore + 1, currentCommitCount()) // 单 Editor 事务
        assertEquals(config, AppIconConfigRepository.load("com.a"))
    }

    @Test
    fun `save clears nullable fields by removing keys`() {
        AppIconConfigRepository.save(AppIconConfig(pkg = "com.a", enabled = true))
        // 空 config 的可空字段 → remove 语义
        assertFalse(prefs.data.containsKey("algo_source_com.a"))
        assertFalse(prefs.data.containsKey("icon_pack_com.a"))
    }

    @Test
    fun `save returns false when commit fails`() {
        prefs.nextCommitResult = false
        assertFalse(AppIconConfigRepository.save(AppIconConfig(pkg = "com.a", enabled = true)))
    }

    @Test
    fun `save rejects invalid config without touching prefs`() {
        val invalid = AppIconConfig(
            pkg = "com.a", enabled = true, strategy = IconStrategy.ALGORITHM, algoSource = null
        )
        assertFalse(invalid.validate().isEmpty())
        val commitsBefore = currentCommitCount()
        assertFalse(AppIconConfigRepository.save(invalid))
        assertEquals(commitsBefore, currentCommitCount()) // 未触碰偏好
    }

    // ━━ saveJson（恢复路径入口） ━━

    @Test
    fun `saveJson commits valid config json`() {
        val c = AppIconConfig(pkg = "com.a", enabled = true, strategy = IconStrategy.ALGORITHM, algoSource = AlgoSource.SELF)
        assertTrue(AppIconConfigRepository.saveJson(c.toJson()))
        assertEquals(c, AppIconConfigRepository.load("com.a"))
    }

    @Test
    fun `saveJson returns false on garbage`() {
        assertFalse(AppIconConfigRepository.saveJson("not json"))
    }

    private fun currentCommitCount(): Int = prefs.commitCount
}
