package io.github.deserthouse.opticon.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorSessionCore 状态机矩阵（RB-1，阶段 1 前置④）。
 * 把 X3/Y 批修复的保存结算语义与输入/预览代守卫成文为测试——
 * 此前这些契约只靠 VM 内注释与手工计数器承载（双审"状态机契约零测试"）。
 */
class EditorSessionCoreTest {

    private fun core() = EditorSessionCore()

    // ━━ 保存结算矩阵（C01/X3 语义成文） ━━

    @Test
    fun `save ok without newer draft and no leave request - Saved clean`() {
        val c = core()
        val saved = c.saveStarted()
        val outcome = c.resolveSaveCompletion(saved, ok = true, requestLeaveAfter = false)
        assertEquals(EditorSessionCore.SaveOutcome.Saved, outcome)
    }

    @Test
    fun `save ok with leave request and no newer draft - SavedExit`() {
        val c = core()
        val saved = c.saveStarted()
        val outcome = c.resolveSaveCompletion(saved, ok = true, requestLeaveAfter = true)
        assertEquals(EditorSessionCore.SaveOutcome.SavedExit, outcome)
    }

    @Test
    fun `newer draft during save - stays dirty even when leave requested (C01)`() {
        val c = core()
        val saved = c.saveStarted()
        c.touch() // 保存期间用户继续编辑
        val outcome = c.resolveSaveCompletion(saved, ok = true, requestLeaveAfter = true)
        assertEquals(EditorSessionCore.SaveOutcome.SavedStayDirty, outcome)
    }

    @Test
    fun `newer draft during save without leave - SavedStayDirty`() {
        val c = core()
        val saved = c.saveStarted()
        c.touch()
        val outcome = c.resolveSaveCompletion(saved, ok = true, requestLeaveAfter = false)
        assertEquals(EditorSessionCore.SaveOutcome.SavedStayDirty, outcome)
    }

    @Test
    fun `failed save - Failed and dirty kept`() {
        val c = core()
        val saved = c.saveStarted()
        val outcome = c.resolveSaveCompletion(saved, ok = false, requestLeaveAfter = true)
        assertEquals(EditorSessionCore.SaveOutcome.Failed, outcome)
    }

    @Test
    fun `after failed save a clean retry exits normally`() {
        val c = core()
        val saved = c.saveStarted()
        c.resolveSaveCompletion(saved, ok = false, requestLeaveAfter = true) // 失败（dirty 保持）
        // 用户重试：saveConfig 重新捕获快照（失败 touch 已成为新基线）
        val retried = c.saveStarted()
        val outcome = c.resolveSaveCompletion(retried, ok = true, requestLeaveAfter = true)
        assertEquals(EditorSessionCore.SaveOutcome.SavedExit, outcome)
    }

    // ━━ 输入身份代 ━━

    @Test
    fun `stale input result is rejected`() {
        val c = core()
        val gen1 = c.bumpInput()
        assertTrue(c.isCurrentInput(gen1))
        c.bumpInput() // 新输入到达
        assertFalse(c.isCurrentInput(gen1))
    }

    // ━━ 预览轮次 ━━

    @Test
    fun `latest preview round wins and older is rejected`() {
        val c = core()
        val r1 = c.nextPreviewRound()
        val r2 = c.nextPreviewRound()
        assertTrue(c.isLatestPreviewRound(r2))
        assertFalse(c.isLatestPreviewRound(r1))
    }

    // ━━ 会话释放（Q4） ━━

    @Test
    fun `abandon session allows retry of same package`() {
        val c = core()
        assertTrue(c.beginSession("com.a"))
        // 加载失败：释放登记
        c.abandonSession("com.a")
        // 重进同包名可重试（修复前 beginSession 返回 false 永久锁死）
        assertTrue(c.beginSession("com.a"))
    }

    @Test
    fun `abandon does not touch other sessions`() {
        val c = core()
        assertTrue(c.beginSession("com.a"))
        c.abandonSession("com.b") // 非当前会话：无操作
        assertFalse(c.beginSession("com.a")) // 仍是 com.a 的会话
        c.abandonSession("com.a")
        assertTrue(c.beginSession("com.b"))
    }

    // ━━ K1 同包吸收所需的 oldSha 语义在 PublishCore 层测试（本类不涉及） ━━
}
