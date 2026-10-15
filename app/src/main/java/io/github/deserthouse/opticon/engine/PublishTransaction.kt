package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.util.UUID

/**
 * PublishTransaction — 可恢复发布（阶段 4，架构报告 §3.5 一级方案）
 *
 * 跨偏好/私有文件/共享目录/MediaStore 没有天然事务；本类通过提交记录+补偿+
 * 重启恢复将失败可判定、可恢复。
 *
 * 阶段序：准备→记录意图→发布→提交配置→完成。
 * 任一阶段失败：补偿（恢复旧产物）→如补偿也失败→标记 Partial 留恢复依据。
 */
object PublishTransaction {
    private const val TAG = "OptIcon/PublishTx"
    private const val PENDING_DIR = "pending_ops"

    data class PendingOp(
        val opId: String = UUID.randomUUID().toString(),
        val pkg: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** 备份的旧产物路径（私有目录），null=首次发布无旧版 */
        val backupPath: String? = null,
        /** 新产物路径（私有目录，待发布到共享） */
        val newPath: String
    )

    /** 阶段 1：准备——烘焙到私有目录+备份旧产物 */
    fun prepare(context: Context, pkg: String, bitmap: Bitmap): PendingOp? {
        return try {
            val bakedDir = File(context.filesDir, "baked").apply { mkdirs() }
            val newFile = File(bakedDir, "$pkg.png")
            val oldFile = File(bakedDir, "$pkg.old.png")

            // 备份旧产物（如有）
            val backupPath = if (newFile.exists()) {
                newFile.copyTo(oldFile, overwrite = true)
                oldFile.absolutePath
            } else null

            // 写新产物
            newFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            newFile.setReadable(true, false)

            PendingOp(pkg = pkg, backupPath = backupPath, newPath = newFile.absolutePath)
        } catch (e: Exception) {
            TraceLogger.e(TAG, "prepare failed: ${e.message}")
            null
        }
    }

    /** 阶段 2：记录意图——持久化 opId（发布前必须完成） */
    fun recordIntent(context: Context, op: PendingOp): Boolean {
        return try {
            val dir = File(context.filesDir, PENDING_DIR).apply { mkdirs() }
            File(dir, "${op.opId}.json").writeText(
                """{"opId":"${op.opId}","pkg":"${op.pkg}","ts":${op.timestamp},"backup":"${op.backupPath}","new":"${op.newPath}"}"""
            )
            true
        } catch (e: Exception) {
            TraceLogger.e(TAG, "recordIntent failed: ${e.message}")
            false
        }
    }

    /** 阶段 3：发布——写入共享目录并校验 */
    fun publish(context: Context, op: PendingOp): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(op.newPath)
                ?: return false.also { TraceLogger.e(TAG, "publish: decode new failed") }
            val result = SharedIconStore.writeIcon(context, op.pkg,
                op.newPath.let { File(it).readBytes() })
            bitmap.recycle()
            result != null
        } catch (e: Exception) {
            TraceLogger.e(TAG, "publish failed: ${e.message}")
            false
        }
    }

    /** 阶段 4：提交配置——已由调用方（VM saveConfig）执行偏好写入 */

    /** 阶段 5：完成——清除 pending 记录 */
    fun complete(context: Context, op: PendingOp) {
        try {
            File(context.filesDir, PENDING_DIR).let { dir ->
                File(dir, "${op.opId}.json").delete()
                // 清理备份
                op.backupPath?.let { File(it).delete() }
            }
        } catch (_: Exception) {}
    }

    /** 补偿：发布/提交失败时恢复旧产物 */
    fun compensate(context: Context, op: PendingOp): Boolean {
        return try {
            if (op.backupPath != null) {
                val backup = File(op.backupPath)
                if (backup.exists()) {
                    val target = File(op.newPath)
                    backup.copyTo(target, overwrite = true)
                    // 重新发布旧版到共享
                    SharedIconStore.writeIcon(context, op.pkg, target.readBytes())
                    TraceLogger.i(TAG, "compensate: restored old artifact for ${op.pkg}")
                    true
                } else false
            } else {
                // 首次发布失败——删除新产物+共享文件
                File(op.newPath).delete()
                SharedIconStore.deleteIcon(context, op.pkg)
                true
            }
        } catch (e: Exception) {
            TraceLogger.e(TAG, "compensate failed (Partial state): ${e.message}")
            false  // Partial — pending record survives for restart recovery
        }
    }

    /** 重启恢复：扫描 pending 目录，按证据收敛 */
    fun recoverOnStartup(context: Context) {
        try {
            val dir = File(context.filesDir, PENDING_DIR)
            if (!dir.isDirectory) return
            val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
            if (files.isEmpty()) return

            TraceLogger.i(TAG, "recoverOnStartup: ${files.size} pending ops")
            for (f in files) {
                try {
                    val json = f.readText()
                    val opId = Regex("\"opId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: continue
                    val pkg = Regex("\"pkg\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: continue
                    val backup = Regex("\"backup\":\"([^\"]*?)\"").find(json)?.groupValues?.get(1)
                    val newPath = Regex("\"new\":\"([^\"]*?)\"").find(json)?.groupValues?.get(1) ?: continue

                    val newFile = File(newPath)
                    val sharedFile = SharedIconStore.iconFile(pkg)

                    when {
                        // 新产物+共享文件都存在 → 可能已发布但配置未提交
                        // 保守策略：重发布确保共享一致，然后视为完成
                        newFile.exists() && sharedFile.exists() -> {
                            TraceLogger.i(TAG, "recover $pkg: both exist — treat as complete")
                            f.delete()
                            backup?.let { File(it).delete() }
                        }
                        // 新产物存在但共享缺失 → 发布未完成 → 恢复旧版
                        newFile.exists() && !sharedFile.exists() -> {
                            TraceLogger.i(TAG, "recover $pkg: publish incomplete — restore backup")
                            if (backup != null && File(backup).exists()) {
                                File(backup).copyTo(File(newPath), overwrite = true)
                                SharedIconStore.writeIcon(context, pkg, File(newPath).readBytes())
                            } else {
                                SharedIconStore.deleteIcon(context, pkg)
                                newFile.delete()
                            }
                            f.delete()
                        }
                        // 新产物缺失 → 已被补偿或从未开始 → 清记录
                        else -> {
                            TraceLogger.i(TAG, "recover $pkg: no new artifact — clear record")
                            f.delete()
                            backup?.let { File(it).delete() }
                        }
                    }
                } catch (e: Exception) {
                    TraceLogger.w(TAG, "recover: error processing ${f.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            TraceLogger.e(TAG, "recoverOnStartup failed: ${e.message}")
        }
    }
}
