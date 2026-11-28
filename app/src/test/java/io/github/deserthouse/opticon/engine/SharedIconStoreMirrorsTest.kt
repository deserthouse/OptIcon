package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SharedIconStore.mirrorsPresent]：规则库的镜像在共享目录里齐不齐（2026-11-28 盲审 BU-02）。
 * 纯文件判断——生产入口 [SharedIconStore.ruleMirrorsPresent] 只是把共享目录换成 Download/OptIcon。
 */
class SharedIconStoreMirrorsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirWith(name: String, vararg files: String): File =
        File(tmp.root, name).apply { mkdirs(); files.forEach { File(this, it).writeText("x") } }

    @Test
    fun `every rule icon needs its mirror`() {
        val rules = dirWith("fankes_cache", "com.a.png", "com.b.png", "meta.json", "source.json")
        assertTrue(SharedIconStore.mirrorsPresent(rules, dirWith("shared", "com.a.opticon", "com.b.opticon", "master_switch.opticon")))

        assertFalse("少一个就不算齐", SharedIconStore.mirrorsPresent(rules, dirWith("shared2", "com.a.opticon")))
        assertFalse("共享目录整个没了（被清掉）不算齐", SharedIconStore.mirrorsPresent(rules, File(tmp.root, "no-such-dir")))
        assertFalse("库目录列不出清单不算齐", SharedIconStore.mirrorsPresent(File(tmp.root, "no-rules"), dirWith("shared3")))
    }

    @Test
    fun `a package the user authored an icon for does not need a rules mirror`() {
        // 那些包共享目录里留的是用户自己的图，规则库同步不动它
        val rules = dirWith("fankes_cache", "com.a.png", "com.mine.png")

        assertTrue(SharedIconStore.mirrorsPresent(rules, dirWith("shared", "com.a.opticon"), skip = { it == "com.mine" }))
        assertFalse(SharedIconStore.mirrorsPresent(rules, dirWith("shared2", "com.a.opticon"), skip = { false }))
    }

    @Test
    fun `only files named like icons count as mirrors`() {
        val rules = dirWith("fankes_cache", "com.a.png")

        assertFalse("带 .png 后缀的旧名字不算（hook 读的是不带后缀的规范名）",
            SharedIconStore.mirrorsPresent(rules, dirWith("shared", "com.a.opticon.png")))
        assertFalse(SharedIconStore.mirrorsPresent(rules, dirWith("shared2", "com.a.opticon.bak")))
    }
}
