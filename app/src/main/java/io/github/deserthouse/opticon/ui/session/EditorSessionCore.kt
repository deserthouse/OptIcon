package io.github.deserthouse.opticon.ui.session

import java.util.concurrent.atomic.AtomicLong

/**
 * EditorSessionCore — 编辑会话状态机的纯 JVM 内核（RB-1，阶段 1 前置④）。
 *
 * 把散落在 AppDetailViewModel 的三个手工计数器（draftRevision /
 * algoClassGeneration / previewGeneration）收敛为单一可测组件。
 * 无 Android 依赖；VM 持有实例并把 StateFlow 映射到本核返回值。
 *
 * 语义（X3/Y 批修复的成文化，勿改——改动须同步 PublishCoreInvariantsTest 之外的
 * EditorSessionCoreTest 矩阵）：
 * - [touch]：任何草稿编辑；revision 单调递增。
 * - [saveStarted]：保存任务捕获当前快照版本。
 * - [resolveSaveCompletion]：完成时按「是否有新草稿」分派结局——
 *   新草稿在场：不清 dirty、不自动离开（仅 Saved）；否则按请求离开。
 * - [bumpInput]/[isCurrentInput]：输入身份代——换图/换源时递增，
 *   过期的分类/预览结果不得回填。
 * - [nextPreviewRound]/[isLatestPreviewRound]：预览轮次——新预览启动递增，
 *   完成守卫仅最新轮可回填状态。
 */
class EditorSessionCore {

    /**
     * RB-3a：会话幂等守卫——同 pkg 已激活时返回 false（Activity 重建的
     * LaunchedEffect 重跑不得回灌持久值覆盖草稿，C07 语义内建）。
     * 换 pkg 返回 true 并重置会话身份。
     */
    fun beginSession(pkg: String): Boolean {
        if (pkg == activePkg) return false
        activePkg = pkg
        return true
    }

    /** RB-3b：预览结果的内部分态建模（UI 分态在 RB-4a 落屏）。 */
    sealed interface PreviewState {
        data class Current(val sourceTag: String) : PreviewState
        data class Updating(val lastSourceTag: String?) : PreviewState
        data object Missing : PreviewState
        data class Rejected(val reason: String) : PreviewState
    }

    private var activePkg: String? = null

    sealed interface SaveOutcome {
        /** 保存成功且请求离开：无新草稿，可以导航 */
        data object SavedExit : SaveOutcome
        /** 保存成功但保存期间有新编辑：留在页面，提示仍有未保存更改 */
        data object SavedStayDirty : SaveOutcome
        /** 保存成功（未请求离开） */
        data object Saved : SaveOutcome
        /** 保存失败：保持 dirty 可重试 */
        data object Failed : SaveOutcome
    }

    private val revision = AtomicLong(0)
    private val inputVersion = AtomicLong(0)
    private val previewRound = AtomicLong(0)

    /** 草稿编辑：dirty 标记伴随调用 */
    fun touch(): Long = revision.incrementAndGet()

    fun revisionNow(): Long = revision.get()

    /** 保存任务开始：捕获快照版本 */
    fun saveStarted(): Long = revision.get()

    /**
     * 保存完成结算。ok=false 恒为 [SaveOutcome.Failed]（dirty 保持）；
     * ok 时若有比 savedRev 新的编辑 → [SaveOutcome.SavedStayDirty]
     * （即使请求离开也不导航——离开会丢新草稿，C01）。
     */
    fun resolveSaveCompletion(savedRev: Long, ok: Boolean, requestLeaveAfter: Boolean): SaveOutcome {
        if (!ok) {
            touch()
            return SaveOutcome.Failed
        }
        val newerDraft = revision.get() != savedRev
        return when {
            !ok -> SaveOutcome.Failed
            requestLeaveAfter && newerDraft -> SaveOutcome.SavedStayDirty
            requestLeaveAfter -> SaveOutcome.SavedExit
            newerDraft -> SaveOutcome.SavedStayDirty
            else -> SaveOutcome.Saved
        }
    }

    // ── 输入身份代（换图/换源递增；过期分类/预览不得回填） ──

    fun bumpInput(): Long = inputVersion.incrementAndGet()

    fun inputNow(): Long = inputVersion.get()

    fun isCurrentInput(v: Long): Boolean = inputVersion.get() == v

    // ── 预览轮次（每次预览启动递增；完成守卫仅最新轮生效） ──

    fun nextPreviewRound(): Long = previewRound.incrementAndGet()

    fun isLatestPreviewRound(r: Long): Boolean = previewRound.get() == r

}
