package io.github.deserthouse.opticon.util

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM 假体 SharedPreferences——apply / commit 都真正写入内存表。
 * （RepositoryLegacyTest 里的那个假体 apply() 是空操作，写不进去。）
 */
internal class InMemoryPrefs : SharedPreferences {

    private val store = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, Any> = HashMap(store)
    override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (store[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String?, v: String?): SharedPreferences.Editor = apply { pending[key!!] = v }
        override fun putStringSet(key: String?, v: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key!!] = v?.toSet() }
        override fun putInt(key: String?, v: Int): SharedPreferences.Editor = apply { pending[key!!] = v }
        override fun putLong(key: String?, v: Long): SharedPreferences.Editor = apply { pending[key!!] = v }
        override fun putFloat(key: String?, v: Float): SharedPreferences.Editor = apply { pending[key!!] = v }
        override fun putBoolean(key: String?, v: Boolean): SharedPreferences.Editor = apply { pending[key!!] = v }
        override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFirst) store.clear()
            pending.forEach { (k, v) -> if (v == null) store.remove(k) else store[k] = v }
            pending.clear()
            clearFirst = false
        }
    }
}
