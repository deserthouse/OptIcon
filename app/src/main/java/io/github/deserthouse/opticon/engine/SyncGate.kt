package io.github.deserthouse.opticon.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SyncGate — 进程级同步互斥（修复批 X-22）。
 *
 * 同一进程内同一时刻只允许一次同步在跑。此前守卫只存在于设置页的视图模型
 * 实例里：换一个入口、或模型被重建，就能并发发起第二次同步——两次同步会互删
 * 对方的暂存目录，结果是「同步失败」或者更糟。第二次发起在这里被拒，由调用方
 * 转成「已有同步在进行」。
 *
 * 读取路径还用它判断「现在能不能清暂存目录」（见 [isHeld]）：同步进行期间
 * 暂存目录可能正在被构建，读路径不能碰。
 */
internal object SyncGate {

    private val held = AtomicBoolean(false)

    /** 尝试占用。true = 占用成功，调用方必须在同步结束（无论成败）时 [release]。 */
    fun tryAcquire(): Boolean = held.compareAndSet(false, true)

    fun release() {
        held.set(false)
    }

    /** 是否有同步正在占用。 */
    val isHeld: Boolean get() = held.get()
}
