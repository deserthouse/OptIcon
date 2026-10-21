package io.github.deserthouse.opticon.ui

import io.github.deserthouse.opticon.ui.screen.isVisibilityBlockedHeuristic
import io.github.deserthouse.opticon.ui.screen.parseVersionStage
import io.github.deserthouse.opticon.ui.viewmodel.refreshWriteback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 批 Q 补课单测（2026-10-21 流程对齐轮）——WORKPLAN_Q 验收条件中指定为
 * "单测"但首轮未写的三组用例。逻辑经提取为纯函数后可测；VM 协程交错本身
 * 仍属逻辑级覆盖（完整竞态需 instrumentation，边界见补课记录）。
 */
class AppListLogicTest {

    // ━━ Q18：badge stage 解析四例 ━━

    @Test
    fun `stage with build suffix truncates to family`() {
        assertEquals("alpha", parseVersionStage("0.18.0-alpha.1"))
    }

    @Test
    fun `plain prerelease suffix parses`() {
        assertEquals("beta", parseVersionStage("0.18.0-beta"))
        assertEquals("rc", parseVersionStage("0.18.0-rc"))
    }

    @Test
    fun `stable version has no stage`() {
        assertEquals("", parseVersionStage("1.0.0"))
    }

    @Test
    fun `rc dot build suffix parses for rc channel continuation`() {
        assertEquals("rc", parseVersionStage("0.18.0-rc.2"))
    }

    // ━━ Q17：可见性阻断启发式四态 ━━

    @Test
    fun `zero to two installed counts are flagged as possibly blocked`() {
        // 0 = 完全阻断也可能（不能落到"无应用"文案）
        assertEquals(true, isVisibilityBlockedHeuristic(0))
        assertEquals(true, isVisibilityBlockedHeuristic(1))
        assertEquals(true, isVisibilityBlockedHeuristic(2))
    }

    @Test
    fun `three or more installed counts are normal`() {
        assertEquals(false, isVisibilityBlockedHeuristic(3))
        assertEquals(false, isVisibilityBlockedHeuristic(32))
    }

    // ━━ Q5：refresh/scan 竞态写回守卫 ━━

    @Test
    fun `late refresh after scan replaced the list is dropped`() {
        val snapshot = listOf("a", "b")
        val scanResult = listOf("a", "b", "c") // scan 完成换表
        val updatedFromStale = snapshot.map { it.uppercase() }
        assertNull(refreshWriteback(snapshot, scanResult, updatedFromStale))
    }

    @Test
    fun `fresh refresh applies when list identity unchanged`() {
        val snapshot = listOf("a", "b")
        val updated = listOf("A", "B")
        val applied = refreshWriteback(snapshot, snapshot, updated)
        assertSame(updated, applied)
    }
}
