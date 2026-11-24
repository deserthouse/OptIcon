package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.FakeFilesContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧版（Legacy JSON）规则的「要不要重新落库」判定（独立复审 D-03）。
 *
 * 内容没变不等于不用落库：期间规则库（ANIP）同步过的话，库目录已被整个换成它的快照，
 * 旧版图标不在盘上。此时若按「内容没变」直接报已是最新，旧版独有的包的镜像在上一步被当作
 * 撤销删掉之后就再也回不来。
 */
class LegacyNeedsApplyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = """[{"packageName":"com.a","iconBitmap":"AA=="}]"""

    private fun ctx() = FakeFilesContext(tmp.newFolder("files"))

    /** 设备上留着的旧版规则文件（SubscriptionManager 的私有文件名）。 */
    private fun saved(c: android.content.Context) {
        File(c.filesDir, "icon_rules.json").writeText(json)
    }

    /** 库目录里的内容来自旧版规则（旧版落库时写下的来源标记）。 */
    private fun libraryIsLegacy(c: android.content.Context) {
        val dir = File(c.filesDir, IconLibEngine.CACHE_DIR_ACCESSIBLE).apply { mkdirs() }
        File(dir, IconLibEngine.LEGACY_ORIGIN_FILE).writeText("legacy")
    }

    /** 库目录已被规则库快照换掉：有 meta.json，没有来源标记。 */
    private fun libraryIsAnipSnapshot(c: android.content.Context) {
        val dir = File(c.filesDir, IconLibEngine.CACHE_DIR_ACCESSIBLE).apply { mkdirs() }
        File(dir, "com.a.png").writeText("png")
        File(dir, "meta.json").writeText("""{"apps":[]}""")
    }

    @Test
    fun `nothing saved yet`() {
        assertTrue(SubscriptionManager.needsApply(ctx(), json))
    }

    @Test
    fun `same content and the library still holds this legacy content`() {
        val c = ctx()
        saved(c)
        libraryIsLegacy(c)
        assertFalse("nothing to do: this is the ordinary up-to-date case", SubscriptionManager.needsApply(c, json))
    }

    @Test
    fun `same content but a rules-library snapshot replaced the directory in between`() {
        val c = ctx()
        saved(c)
        libraryIsAnipSnapshot(c)
        assertTrue("must re-apply: the legacy icons are no longer on disk", SubscriptionManager.needsApply(c, json))
    }

    @Test
    fun `different content`() {
        val c = ctx()
        saved(c)
        libraryIsLegacy(c)
        assertTrue(SubscriptionManager.needsApply(c, json.replace("com.a", "com.b")))
    }
}
