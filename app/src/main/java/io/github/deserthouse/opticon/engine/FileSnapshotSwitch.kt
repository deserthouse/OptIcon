package io.github.deserthouse.opticon.engine

import java.io.File

/**
 * FileSnapshotSwitch — 目录级原子版本切换原语（批 SYNC V1/V2 共用核心）。
 *
 * 契约：同步流程先在 staging 目录构建出完整新版本，验证通过后用本类整体切换；
 * 任何时刻旧版目录要么完整保留、要么已被完整替换，绝不出现半新半旧混合态：
 *
 *   staging 构建 → 调用方验证 → [switchIn]（a 清残留 → b 旧版让位 → c staging 上位 → d 清理）
 *   任一步 IO 失败 → [rollback] 回到切换前快照
 *   步骤间进程被杀 → 下次加载前 [recoverIfInterrupted] 收敛
 *
 * Windows JVM 纪律：File.renameTo 不能覆盖已存在目标（批 F 教训①）——
 * 本类所有 rename 都保证目标不存在时才执行。
 * 纯文件系统操作，不依赖 Android Context——JVM 单测直接覆盖。
 */
internal object FileSnapshotSwitch {

    /** staging / 让位目录的命名规则：`{name}.staging` / `{name}.prev` */
    fun stagingOf(parent: File, name: String) = File(parent, "$name.staging")
    fun prevOf(parent: File, name: String) = File(parent, "$name.prev")

    /** 切换中途失败：调用方应立即 [rollback] 并上报失败，不做重试。 */
    class SwitchException(step: String, cause: Throwable? = null) :
        Exception("snapshot switch failed at: $step", cause)

    /**
     * 把构建完成的 staging 目录切换为正式版 [name]（两者同为 [parent] 子目录）。
     * staging 内容的验证是调用方的职责——本函数不做内容检查。
     *
     * [afterPromote]：live 换新之后、prev 清理之前的配套落位步骤（如把新索引
     * 从快照内搬到根部）。它的失败会触发整体回滚到旧快照——因此**提交点在
     * afterPromote 成功之后**，prev 在那之前绝不删除（否则配套步骤失败时旧版
     * 已不可恢复；单测 FileSnapshotSwitchTest 抓出过此设计缺陷）。
     * 正常返回 = 切换已提交（live=新内容，staging/prev 均已清理）。
     */
    fun switchIn(parent: File, name: String, afterPromote: (() -> Unit)? = null) {
        val dir = File(parent, name)
        val prev = prevOf(parent, name)
        val staging = stagingOf(parent, name)

        // a. 清掉历史残留的 prev（上次切换成功但清理中断时可能留下）
        if (prev.exists()) prev.deleteRecursively()

        // b. 旧版让位（首装无旧版则跳过）
        if (dir.exists()) {
            if (!dir.renameTo(prev)) throw SwitchException("live→prev")
        }

        // c. staging 上位；失败则先回滚 b 再抛
        if (!staging.renameTo(dir)) {
            if (prev.exists() && !dir.exists()) prev.renameTo(dir)
            throw SwitchException("staging→live")
        }

        // 配套步骤（可选）：失败 = 回滚到旧快照后把异常抛给调用方
        try {
            afterPromote?.invoke()
        } catch (e: Exception) {
            rollback(parent, name)
            throw e
        }

        // d. 提交点已过，prev 清理尽力而为（失败不影响成功语义，
        //    recoverIfInterrupted 会在下次收敛）
        prev.deleteRecursively()
    }

    /**
     * 回滚到切换前快照。任意状态调用都安全：无切换痕迹时是 no-op。
     * （live 缺失且 prev 在 = b/c 之间中断；live 与 prev 并存 = 调用方
     * 决定放弃新版本时强制回到旧版。）
     */
    fun rollback(parent: File, name: String) {
        val dir = File(parent, name)
        val prev = prevOf(parent, name)
        stagingOf(parent, name).deleteRecursively()
        if (prev.exists()) {
            if (dir.exists()) dir.deleteRecursively()
            prev.renameTo(dir)
        }
    }

    /**
     * 崩溃恢复：切换步骤之间进程被杀后，目录可能停在中间态。加载前调用：
     *  - live 缺失 + prev 在 → 回滚（b→c 窗口被杀，回到旧版）
     *  - live 与 prev 并存 → 切换已提交、仅 d 未完成 → 丢弃 prev 保留新版
     */
    fun recoverIfInterrupted(parent: File, name: String) {
        val dir = File(parent, name)
        val prev = prevOf(parent, name)
        if (!dir.exists() && prev.exists()) {
            prev.renameTo(dir)
        } else if (dir.exists() && prev.exists()) {
            prev.deleteRecursively()
        }
    }
}
