package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import io.github.deserthouse.opticon.engine.publish.PublishCore
import io.github.deserthouse.opticon.engine.publish.PublishLog
import io.github.deserthouse.opticon.engine.publish.PublishStore
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PublishTransaction — 可恢复发布的 Android 适配层（F1-1 重写）。
 *
 * 事务逻辑全部在 [PublishCore]（JVM 纯净、常驻单测覆盖）；本类只做边界适配：
 * Bitmap↔PNG 字节、SharedIconStore 交付（含回读校验）、TraceLogger 日志、
 * 烘焙文件的 world-readable 位。
 *
 * 阶段序：prepare → recordIntent → publish（交付+回读→标记 PUBLISHED）→ complete；
 * 失败 → compensate；进程中断 → recoverOnStartup 证据收敛。
 * 配置的提交/回滚由调用方（VM）在 publish 成功后执行（F1-3 起经
 * AppIconConfigRepository，op 内携带新旧配置快照供失败恢复）。
 */
object PublishTransaction {
    private const val TAG = "OptIcon/PublishTx"
    private const val PENDING_DIR = "pending_ops"
    private const val BAKED_DIR = "baked"

    // C02（X2）：进程级单例——lock 是 PublishCore 实例字段，逐调用建实例等于
    // 没有公共锁；applicationContext 进程内稳定，单例后方法级 synchronized 才生效。
    @Volatile
    private var coreRef: PublishCore? = null
    private val coreGuard = Any()
    private fun coreOf(context: Context): PublishCore {
        coreRef?.let { return it }
        synchronized(coreGuard) {
            coreRef?.let { return it }
            coreRef = PublishCore(
                store = SharedPublishStore(context.applicationContext),
                opsDir = File(context.applicationContext.filesDir, PENDING_DIR),
                bakedDir = File(context.applicationContext.filesDir, BAKED_DIR),
                log = object : PublishLog {
                    override fun i(m: String) = TraceLogger.i(TAG, m)
                    override fun e(m: String) = TraceLogger.e(TAG, m)
                }
            )
            return coreRef!!
        }
    }

    /** 阶段 1：准备——烘焙到私有目录+备份旧产物（newFile 保持 world-readable 供 SystemUI） */
    fun prepare(
        context: Context,
        pkg: String,
        bitmap: Bitmap,
        oldConfigJson: String? = null,
        newConfigJson: String? = null
    ): PublishCore.PendingOp? {
        val pngBytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        }
        val op = coreOf(context).prepare(pkg, pngBytes, oldConfigJson, newConfigJson) ?: return null
        try {
            File(op.newPath).setReadable(true, false)
        } catch (_: Exception) {
        }
        return op
    }

    /** 阶段 2：记录意图——持久化 opId（发布前必须完成） */
    fun recordIntent(context: Context, op: PublishCore.PendingOp): Boolean =
        coreOf(context).record(op)

    /** 阶段 3：发布——写入共享目录并回读校验（核心内完成） */
    fun publish(context: Context, op: PublishCore.PendingOp): Boolean =
        coreOf(context).publish(op)

    /** 阶段 5：完成——清除 pending 记录与备份 */
    fun complete(context: Context, op: PublishCore.PendingOp) =
        coreOf(context).complete(op)

    /** 补偿：发布/提交失败时恢复旧产物；false = Partial（记录保留，等待重启恢复） */
    fun compensate(context: Context, op: PublishCore.PendingOp): Boolean =
        coreOf(context).compensate(op)

    /** 重启恢复：按交付内容证据收敛。applyConfig 返回配置提交结果——false 时
     *  核心保留记录为 Partial（C03），调用方须如实消费。 */
    fun recoverOnStartup(context: Context, applyConfig: ((String) -> Boolean)? = null) {
        val dir = File(context.filesDir, PENDING_DIR)
        TraceLogger.i(TAG, "recoverOnStartup: enter (opsDir exists=${dir.isDirectory}, files=${dir.listFiles()?.size ?: -1})")
        coreOf(context).recover { cfg -> if (cfg != null) (applyConfig?.invoke(cfg) ?: true) else true }
        TraceLogger.i(TAG, "recoverOnStartup: done")
    }

    /**
     * 生产交付边界：SharedIconStore 写入 + iconFile 回读比对（hook 直读该
     * canonical 路径，File 读即权威目标；审查不变量④的实装位）。
     */
    private class SharedPublishStore(private val context: Context) : PublishStore {
        override fun deliver(pkg: String, bytes: ByteArray): Boolean = try {
            if (SharedIconStore.writeIcon(context, pkg, bytes) == null) false
            else SharedIconStore.iconFile(pkg).readBytes().contentEquals(bytes)
        } catch (e: Exception) {
            TraceLogger.e(TAG, "deliver: ${e.message}")
            false
        }

        override fun readDelivered(pkg: String): ByteArray? = try {
            val f = SharedIconStore.iconFile(pkg)
            if (f.exists()) f.readBytes() else null
        } catch (_: Exception) {
            null
        }

        override fun remove(pkg: String): Boolean = try {
            // 终态语义（K8）：canonical 文件不存在即 true（对齐 PublishStore 契约）
            SharedIconStore.deleteIcon(context, pkg) || !SharedIconStore.iconFile(pkg).exists()
        } catch (_: Exception) {
            false
        }
    }
}
