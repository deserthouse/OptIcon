package io.github.deserthouse.opticon.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内置规则源清单地址的设备验证入口（批 UP）：只有调试包读它，正式包恒为 null（BuildConfig.DEBUG 为 false）。
 * 单测跑在调试变体上，所以这里能测到取值与校验；「正式包不读」由变体本身保证，没有别的开关。
 */
class AnipManifestOverrideTest {

    @After
    fun tearDown() {
        PreferenceManager.initForTest(InMemoryPrefs())
    }

    private fun override(stored: String?): String? {
        val prefs = InMemoryPrefs()
        if (stored != null) prefs.edit().putString("e2e_anip_manifest_url", stored).commit()
        PreferenceManager.initForTest(prefs)
        return PreferenceManager.anipManifestOverride()
    }

    @Test
    fun `not set means the upstream address`() {
        assertNull(override(null))
        assertNull(override(""))
        assertNull(override("   "))
    }

    @Test
    fun `https and loopback addresses are accepted`() {
        assertEquals("https://example.com/r/anip-release.json", override("https://example.com/r/anip-release.json"))
        assertEquals("http://127.0.0.1:28765/anip-release.json", override(" http://127.0.0.1:28765/anip-release.json "))
        assertEquals("http://10.0.2.2:28765/anip-release.json", override("http://10.0.2.2:28765/anip-release.json"))
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(override("http://example.com/r/anip-release.json"))
        assertNull(override("http://192.168.1.2:28765/anip-release.json"))
        assertNull(override("ftp://example.com/r/anip-release.json"))
        assertNull(override("file:///data/local/tmp/anip-release.json"))
    }
}
