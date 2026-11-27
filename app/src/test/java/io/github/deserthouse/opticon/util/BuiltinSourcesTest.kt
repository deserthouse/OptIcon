package io.github.deserthouse.opticon.util

import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.engine.AnipSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置源登记的地址（批 UP-U3 / U1）。
 *
 * 应用图标库（PICP）原先登记的是 GitHub API 的 zipball 接口——它计入匿名调用限额（每 IP 每小时 60 次），
 * 共用出口 IP 的用户会被误拒；换成普通的归档地址，内容相同、不限额。
 * 规则库（ANIP）登记的仍是清单基址，同步时换成整库归档地址。
 */
class BuiltinSourcesTest {

    @Test
    fun `the built-in app icon library address is a plain archive, not the api`() {
        val picp = PreferenceManager.BUILTIN_PICP_SOURCES.single()
        assertEquals("picp_github", picp.id)
        assertTrue(picp.isBuiltin)
        assertEquals(SourceFormat.PICP_ZIPBALL, picp.format)
        assertEquals("https://github.com/pzcn/Perfect-Icons-Completion-Project/archive/refs/heads/main.zip", picp.url)
        assertFalse("api 接口计入匿名调用限额", picp.url.contains("api.github.com"))
    }

    @Test
    fun `the built-in rule source address maps onto the branch archive`() {
        val ania = PreferenceManager.BUILTIN_ANIA_SOURCES.single()
        assertEquals("ania_raw", ania.id)
        assertTrue(ania.isBuiltin)
        assertEquals(SourceFormat.ANIP_BUNDLE, ania.format)
        assertEquals(AnipSync.BASE, ania.url)
        // 内置源走官方发布通道，用不上才退回整库——整库地址由内置地址换算出来，不另记一份
        assertEquals(AnipSync.ZIP_URL, AnipSync.archiveUrlFor(ania.url))
    }
}
