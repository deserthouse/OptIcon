package io.github.deserthouse.opticon.engine.publish

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PublishCoordinator — 应用级发布协调（X2，标杆复核 C02）。
 *
 * PublishCore 方法级 synchronized 只在单实例内有效（旧 coreOf 每调用建实例，
 * 锁不共享）；且方法级锁不能保证 prepare→record→publish→提交→complete 整段
 * 不被恢复穿插。本协调器以进程级互斥把「完整提交流水线」与「启动恢复」
 * 串行化——调用方（VM 保存 / MainActivity 恢复）把各自整段包进 [withLock]。
 *
 * 恢复的一次性守卫（进程内只执行一次）也在此：Activity 重建不重复触发。
 */
object PublishCoordinator {
    private val pipelineMutex = Mutex()
    private val recoveryStarted = AtomicBoolean(false)

    /** 完整提交流水线（prepare→…→complete/compensate）与恢复互斥执行 */
    suspend fun <T> withPipeline(block: suspend () -> T): T = pipelineMutex.withLock { block() }

    /** 进程内只放行一次启动恢复；重复调用（Activity 重建）返回 false */
    fun beginRecoveryOnce(): Boolean = recoveryStarted.compareAndSet(false, true)
}
