package io.github.deserthouse.opticon.engine

import java.io.File

/**
 * FileSnapshotSwitch — 目录级原子版本切换原语（批 SYNC V1/V2 共用核心）。
 *
 * 契约：同步流程先在 staging 目录构建出完整新版本，验证通过后用本类整体切换；
 * 任何时刻 live 目录要么完整保留、要么已被完整替换，绝不出现半新半旧混合态：
 *
 *   staging 构建 → 调用方验证 → [switchIn]
 *     a 收敛上次遗留的中间态 → b 旧版让位 → c staging 上位（提交点）→ d 清理旧版
 *   b 或 c 失败 → 返回前旧版已放回原位（或停在读取路径认得的中间态），抛 [SwitchException]
 *   步骤间进程被杀 → 下次读取前 [recoverIfInterrupted] 收敛
 *
 * 提交点只有一个：c 的改名。c 之后不再有任何可能失败的配套步骤，所以
 * 「live 与 prev 并存」恒等于「新版本已提交」，恢复逻辑据此取舍。修复批（X-06）
 * 之前这里有一个「配套步骤」参数（应用图标库用它做根索引换位），提交点在配套
 * 步骤之后，而恢复逻辑把「新旧并存」一律当作已提交——两处定义不一致，配套步骤
 * 中途被杀后冷启会丢掉旧版、新索引又没落位，索引全丢。配套步骤连同参数一并
 * 移除，索引改为随目录走（见 PicpLibrary）。
 *
 * 调用方不再自己回滚（X-23）：旧的 rollback 函数「只要 prev 在就删掉 live，再把
 * prev 放回来」，在让位失败（live 完好、prev 只是陈旧残留）的场合会先删掉完好的
 * live。现在失败恢复完全在 [switchIn] 内部完成，任何路径都不删除 live。
 *
 * Windows JVM 纪律：File.renameTo 不能覆盖已存在目标（批 F 教训①）——
 * 本类所有 rename 都保证目标不存在时才执行。
 * 纯文件系统操作，不依赖 Android Context——JVM 单测直接覆盖。
 */
internal object FileSnapshotSwitch {

    /** staging / 让位目录的命名规则：`{name}.staging` / `{name}.prev` */
    fun stagingOf(parent: File, name: String) = File(parent, "$name.staging")
    fun prevOf(parent: File, name: String) = File(parent, "$name.prev")

    /** 切换中途失败：旧版已放回原状，调用方上报失败即可，不做重试、不必回滚。 */
    class SwitchException(step: String, cause: Throwable? = null) :
        Exception("snapshot switch failed at: $step", cause)

    /** 原语用到的文件系统动作。生产恒为 [FsOps.Default]；单测注入失败，覆盖
     *  每一步出错之后目录停在什么状态。 */
    interface FsOps {
        fun rename(from: File, to: File): Boolean
        fun deleteTree(file: File): Boolean

        object Default : FsOps {
            override fun rename(from: File, to: File): Boolean = from.renameTo(to)
            override fun deleteTree(file: File): Boolean = file.deleteRecursively()
        }
    }

    /** 同一目录的切换与恢复互斥：读路径的恢复最多等一次临界区，不会撞见 b/c 之间的空档。 */
    private val switchLock = Any()

    /**
     * 把构建完成的 staging 目录切换为正式版 [name]（两者同为 [parent] 子目录）。
     * staging 内容的验证是调用方的职责——本函数不做内容检查。
     * 正常返回 = 切换已提交（live = 新内容）。
     * 无论成败，返回之后 staging 都不存在；失败时旧版已放回原位，
     * 或停在「live 缺失 + prev 在」这个 [recoverIfInterrupted] 认得的中间态。
     */
    fun switchIn(parent: File, name: String, ops: FsOps = FsOps.Default) {
        synchronized(switchLock) {
            val dir = File(parent, name)
            val prev = prevOf(parent, name)
            val staging = stagingOf(parent, name)

            // a. 收敛上次遗留的中间态。不能直接删 prev：live 缺失时 prev 里
            //    就是唯一完好的旧版。放不回去就拒绝切换——prev 原样保留，
            //    读取路径下次接着收。（不碰 staging——调用方刚把它建好。）
            if (!converge(dir, prev, ops)) {
                ops.deleteTree(staging)
                throw SwitchException("prev→live")
            }

            // b. 旧版让位（首装没有旧版则跳过）。失败时 live 原样未动。
            if (dir.exists() && !ops.rename(dir, prev)) {
                ops.deleteTree(staging)
                throw SwitchException("live→prev")
            }

            // c. staging 上位 = 提交点。
            if (!ops.rename(staging, dir)) {
                // 上位失败：把旧版放回去。连放回去也失败时，停在「live 缺失 + prev 在」
                // ——这是 recoverIfInterrupted 认得的中间态，读取路径会接着收。
                // 两种情况都清掉没能上位的 staging。
                val restoreDeferred = prev.exists() && !dir.exists() && !ops.rename(prev, dir)
                ops.deleteTree(staging)
                if (restoreDeferred) throw SwitchException("staging→live (restore deferred)")
                throw SwitchException("staging→live")
            }

            // d. 已提交。旧版清理尽力而为：没删干净不改变成功语义，
            //    下一次 recoverIfInterrupted 会接着收。
            ops.deleteTree(prev)
        }
    }

    /**
     * 崩溃恢复。切换步骤之间进程被杀后目录会停在中间态，读取路径调用本函数收敛：
     *  - live 缺失 + prev 在 → 旧版放回（b 之后 c 之前被杀，新版本没有上线）
     *  - live 与 prev 并存 → 新版已提交、只是 d 没做完 → 丢弃 prev
     *
     * [clearStaging]：是否顺带清暂存目录。同步进行期间暂存目录可能正在被构建，
     * 读路径不能删它——引擎按 [SyncGate] 的占用状态决定；同步入口传 true。
     *
     * @return false 表示「live 缺失 + prev 在」但 prev 放不回去；其余情况恒 true。
     */
    fun recoverIfInterrupted(
        parent: File,
        name: String,
        clearStaging: Boolean = true,
        ops: FsOps = FsOps.Default
    ): Boolean {
        synchronized(switchLock) {
            val converged = converge(File(parent, name), prevOf(parent, name), ops)
            if (clearStaging) ops.deleteTree(stagingOf(parent, name))
            return converged
        }
    }

    private fun converge(dir: File, prev: File, ops: FsOps): Boolean {
        if (!dir.exists() && prev.exists()) return ops.rename(prev, dir)
        if (dir.exists() && prev.exists()) ops.deleteTree(prev)
        return true
    }
}
