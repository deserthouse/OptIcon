package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 规则库同步之后收尾的四步与顺序（批 MT M2）。审查员 D 把「不删被撤销包的镜像」「不写版本戳」
 * 「旧规则文件总是导入」三处一起改坏，全套测试照样全绿——这段接线此前只在 ViewModel 里，测试进不去。
 */
class RuleSyncFinisherTest {

    private val calls = ArrayList<String>()
    private val warns = ArrayList<String>()
    private val infos = ArrayList<String>()
    private var failMirror = false
    private var failRevokeLock = false
    private var undeletable = setOf<String>()
    private var revokedWriteOk = true
    private var stampOk = true
    private var watchChecks = false
    // 共享目录里镜像齐不齐（核实镜像的那一组用例用）
    private var sharedHasAll = true
    private var mirrorFixes = true
    private var presenceCheckThrows = false

    @Before
    fun reset() {
        calls.clear(); warns.clear(); infos.clear()
        failMirror = false; failRevokeLock = false; undeletable = emptySet()
        revokedWriteOk = true; stampOk = true; watchChecks = false
        sharedHasAll = true; mirrorFixes = true; presenceCheckThrows = false
    }

    private val customized = setOf("com.custom")

    private fun finisher() = RuleSyncFinisher(
        importLegacyRules = { calls += "import" },
        withPipeline = { label, block ->
            calls += "lock:$label"
            if (failRevokeLock && label == "ania-sync-revoke") throw IllegalStateException("lock broke")
            block()
        },
        mirrorAll = {
            if (failMirror) throw IllegalStateException("mirror broke")
            calls += "mirror"
        },
        isCustomized = { pkg ->
            if (watchChecks) calls += "check:$pkg"
            pkg in customized
        },
        deleteMirror = { pkg ->
            calls += "delete:$pkg"
            if (pkg in undeletable) throw IllegalStateException("nope")
            true
        },
        writeRevokedList = { revoked ->
            calls += "revoked:${revoked.joinToString(",")}"
            revokedWriteOk
        },
        writeStamp = { calls += "stamp"; stampOk },
        warn = warns::add,
        info = infos::add,
    )

    @Test
    fun `the four steps run in order and the stamp is written last`() {
        assertTrue(finisher().finish(listOf("com.a", "com.custom")))

        assertEquals(
            listOf(
                "import",
                "lock:ania-sync-mirror", "mirror",
                "lock:ania-sync-revoke", "delete:com.a",
                "revoked:com.a,com.custom",
                "stamp"
            ),
            calls
        )
        assertEquals(listOf("rule sync follow-up: revoked=2, mirrors removed=1, kept (customized)=1"), infos)
        assertTrue(warns.isEmpty())
    }

    @Test
    fun `a package the user customized keeps its mirror but is still on the revoked list`() {
        // 清单照样写：hook 本地缓存里那份与「是否自定义」无关，一律要删
        finisher().finish(listOf("com.custom", "com.plain"))

        assertEquals(listOf("import", "lock:ania-sync-mirror", "mirror", "lock:ania-sync-revoke", "delete:com.plain", "revoked:com.custom,com.plain", "stamp"), calls)
    }

    @Test
    fun `nothing revoked means no deletions, and the list and stamp are still written`() {
        assertTrue(finisher().finish(emptyList()))

        assertFalse(calls.any { it.startsWith("delete:") })
        assertEquals("revoked:", calls[4])
        assertEquals("stamp", calls[5])
        assertEquals(listOf("rule sync follow-up: revoked=0, mirrors removed=0, kept (customized)=0"), infos)
    }

    @Test
    fun `a failed mirroring step is reported but does not stop the rest`() {
        failMirror = true

        assertFalse(finisher().finish(listOf("com.a")))

        assertEquals(listOf("import", "lock:ania-sync-mirror", "lock:ania-sync-revoke", "delete:com.a", "revoked:com.a", "stamp"), calls)
        assertEquals(1, warns.size)
        assertTrue(warns.single().startsWith("rule sync follow-up: mirroring failed"))
        assertEquals("rule sync follow-up: revoked=1, mirrors removed=1, kept (customized)=0 (incomplete)", infos.single())
    }

    @Test
    fun `a broken revoke step is reported as incomplete and the stamp is still written`() {
        failRevokeLock = true

        assertFalse(finisher().finish(listOf("com.a")))

        assertTrue(warns.any { it.startsWith("rule sync follow-up: revoke cleanup failed") })
        assertTrue("规则库确实换了，版本戳照写", "stamp" in calls)
    }

    @Test
    fun `one undeletable mirror is logged and does not make the follow-up incomplete`() {
        undeletable = setOf("com.a")

        assertTrue(finisher().finish(listOf("com.a", "com.b")))

        assertEquals(listOf("revoke cleanup failed for com.a: nope"), warns)
        assertEquals("rule sync follow-up: revoked=2, mirrors removed=1, kept (customized)=0", infos.single())
    }

    @Test
    fun `a failed stamp write is only logged`() {
        stampOk = false

        assertTrue("版本戳写不成不是「发布没做完」", finisher().finish(listOf("com.a")))

        assertEquals(listOf("rules stamp write failed — hook icon cache may stay stale"), warns)
    }

    @Test
    fun `a failed revoked list write is only logged`() {
        revokedWriteOk = false

        assertTrue(finisher().finish(listOf("com.a")))

        assertEquals(listOf("revoked list write failed — hook-local cache may keep revoked icons"), warns)
    }

    @Test
    fun `the customized check runs inside the pipeline lock`() {
        // 判定与删除之间若插进一次「为同一个包保存自定义」，删掉的会是用户刚保存的图标
        watchChecks = true

        finisher().finish(listOf("com.a"))

        assertTrue(calls.indexOf("check:com.a") > calls.indexOf("lock:ania-sync-revoke"))
    }

    // ━━ 以结果为准：镜像之后核实共享目录；上游没变化时先核实再决定补不补发（2026-11-28 盲审 BU-02）━━

    /** 会核实镜像的收尾。上面那台不核实（默认当作齐全），只为测四步顺序。 */
    private fun checkingFinisher() = RuleSyncFinisher(
        importLegacyRules = { calls += "import" },
        withPipeline = { label, block ->
            calls += "lock:$label"
            block()
        },
        mirrorAll = {
            calls += "mirror"
            if (failMirror) throw IllegalStateException("mirror broke")
            if (mirrorFixes) sharedHasAll = true
        },
        mirrorsPresent = {
            calls += "present"
            if (presenceCheckThrows) throw IllegalStateException("cannot list")
            sharedHasAll
        },
        isCustomized = { false },
        deleteMirror = { true },
        writeRevokedList = { true },
        writeStamp = { calls += "stamp"; stampOk },
        warn = warns::add,
        info = infos::add,
    )

    @Test
    fun `mirroring that leaves icons missing is reported as incomplete even though nothing threw`() {
        // 单个文件写不进去时 mirrorAll 不抛异常：只看「镜像这一步跑没跑完」会把没发出去报成已同步
        sharedHasAll = false
        mirrorFixes = false

        assertFalse(checkingFinisher().finish(listOf("com.a")))

        assertEquals(listOf("import", "lock:ania-sync-mirror", "mirror", "present", "lock:ania-sync-revoke", "stamp"), calls)
        assertTrue(warns.any { it.contains("missing from the shared dir") })
        assertTrue("规则库确实换了，版本戳照写", "stamp" in calls)
    }

    @Test
    fun `when upstream has nothing new and the mirrors are complete nothing is republished`() {
        assertTrue(checkingFinisher().repairIfNeeded())

        assertEquals(listOf("present"), calls)
        assertEquals(listOf("rule sync follow-up: snapshot unchanged, mirrors complete — nothing to republish"), infos)
        assertTrue(warns.isEmpty())
    }

    @Test
    fun `when upstream has nothing new but mirrors are missing they are republished and the stamp is written`() {
        // 共享目录被清掉、上一次收尾中途被杀、镜像时写失败：「请再同步一次」要真的能把图标补回来
        sharedHasAll = false
        mirrorFixes = true

        assertTrue(checkingFinisher().repairIfNeeded())

        assertEquals(listOf("present", "lock:ania-sync-mirror", "mirror", "present", "stamp"), calls)
        assertFalse("这一轮没有撤销，不动撤销清单", calls.any { it.startsWith("lock:ania-sync-revoke") })
        assertTrue(warns.isEmpty())
    }

    @Test
    fun `mirrors that are still missing after republishing are reported as not done`() {
        sharedHasAll = false
        mirrorFixes = false

        assertFalse(checkingFinisher().repairIfNeeded())

        assertTrue("补不齐也写版本戳，让 hook 重新取一次图", "stamp" in calls)
        assertTrue(warns.any { it.contains("still incomplete") })
    }

    @Test
    fun `a republish that throws is reported as not done`() {
        sharedHasAll = false
        failMirror = true

        assertFalse(checkingFinisher().repairIfNeeded())

        assertFalse("镜像没跑成就不写版本戳", "stamp" in calls)
        assertTrue(warns.any { it.contains("republishing failed") })
    }

    @Test
    fun `a shared dir that cannot be inspected is not assumed to be complete`() {
        presenceCheckThrows = true

        assertFalse(checkingFinisher().repairIfNeeded())

        assertTrue("核实不了也要试着补发一次", "mirror" in calls)
        assertTrue(warns.any { it.contains("could not verify") })
    }
}
