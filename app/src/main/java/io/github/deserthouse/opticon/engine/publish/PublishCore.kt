package io.github.deserthouse.opticon.engine.publish

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * PublishCore — 可恢复发布事务核（F1-1，post-change 审查 A01/A02/A03 关闭项）。
 *
 * JVM 纯净：只依赖 java.io/java.security + 注入的 [PublishStore]/[PublishLog]，
 * 可在普通 JVM 单测中以真临时目录直测。生产侧经 PublishTransaction 适配
 * （SharedIconStore 交付 + TraceLogger 日志）。
 *
 * 阶段序：prepare → record → publish(交付+回读校验→markPublished) → complete。
 * 任一阶段中断：重启后 recover 按证据收敛（交付内容 SHA 为权威判据，不凭
 * "文件存在"清记录——审查不变量①）；compensate 校验写回结果且不误删非本次
 * 创建的共享产物（审查不变量②③）。
 *
 * 所有公开方法 synchronized：恢复（后台）与新保存互斥。
 */
class PublishCore(
    private val store: PublishStore,
    private val opsDir: File,
    private val bakedDir: File,
    private val log: PublishLog
) {
    enum class Stage { PREPARED, PUBLISHED }

    data class PendingOp(
        val opId: String = UUID.randomUUID().toString(),
        val pkg: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** 私有旧产物备份路径；null = 首次发布（交付位可能仍存在规则库镜像） */
        val backupPath: String?,
        /** 私有新产物路径 */
        val newPath: String,
        val stage: Stage = Stage.PREPARED,
        /** 完整旧配置快照 JSON；null/blank = 旧态无配置记录（仅恢复产物，不动配置） */
        val oldConfigJson: String?,
        /** 完整新配置快照 JSON；blank = 旧版本记录，恢复时不触碰配置 */
        val newConfigJson: String?,
        /** 新产物字节 SHA-256（交付位一致性判据） */
        val newSha: String
    )

    // ━━ 阶段 1：准备 ━━

    fun prepare(pkg: String, pngBytes: ByteArray, oldConfigJson: String?, newConfigJson: String?): PendingOp? =
        synchronized(lock) {
            try {
                bakedDir.mkdirs()
                val newFile = File(bakedDir, "$pkg.png")
                val oldFile = File(bakedDir, "$pkg.old.png")
                val backupPath = if (newFile.exists()) {
                    newFile.copyTo(oldFile, overwrite = true)
                    oldFile.absolutePath
                } else null
                newFile.writeBytes(pngBytes)
                PendingOp(
                    pkg = pkg, backupPath = backupPath, newPath = newFile.absolutePath,
                    oldConfigJson = oldConfigJson, newConfigJson = newConfigJson,
                    newSha = sha256(pngBytes)
                )
            } catch (e: Exception) {
                log.e("prepare failed: ${e.message}")
                null
            }
        }

    // ━━ 阶段 2：记录意图（tmp+rename，防半条日志） ━━

    fun record(op: PendingOp): Boolean = synchronized(lock) {
        try {
            opsDir.mkdirs()
            val tmp = File(opsDir, "${op.opId}.json.tmp")
            tmp.writeText(toJson(op))
            val target = File(opsDir, "${op.opId}.json")
            // Windows 的 renameTo 不能覆盖已存在目标；copyTo(overwrite) 双平台
            // 一致。记录内容只有 PREPARED/PUBLISHED 两种合法中间态，写覆盖的
            // 非原子窗口不影响恢复判定（copyTo 失败以抛异常表达，外层 catch）。
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        } catch (e: Exception) {
            log.e("record failed: ${e.message}")
            false
        }
    }

    // ━━ 阶段 3：发布（交付 + 回读比对 → 标记 PUBLISHED） ━━

    /** 不变量④：deliver 声称成功但交付字节不符 → 失败，不虚报 */
    fun publish(op: PendingOp): Boolean = synchronized(lock) {
        try {
            val bytes = File(op.newPath).readBytes()
            if (!store.deliver(op.pkg, bytes)) {
                log.e("publish: deliver refused for ${op.pkg}")
                return false
            }
            val confirmed = store.readDelivered(op.pkg)
            if (confirmed == null || !confirmed.contentEquals(bytes)) {
                log.e("publish: delivered bytes mismatch for ${op.pkg}")
                return false
            }
            markPublishedLocked(op)
        } catch (e: Exception) {
            log.e("publish failed: ${e.message}")
            false
        }
    }

    fun markPublished(op: PendingOp): Boolean = synchronized(lock) { markPublishedLocked(op) }

    private fun markPublishedLocked(op: PendingOp): Boolean = try {
        val target = File(opsDir, "${op.opId}.json")
        if (!target.exists()) {
            log.e("markPublished: no record for ${op.opId}")
            false
        } else {
            val tmp = File(opsDir, "${op.opId}.json.tmp")
            tmp.writeText(toJson(op.copy(stage = Stage.PUBLISHED)))
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        }
    } catch (e: Exception) {
        log.e("markPublished failed: ${e.message}")
        false
    }

    // ━━ 阶段 5：完成 ━━

    fun complete(op: PendingOp) {
        synchronized(lock) {
            try {
                File(opsDir, "${op.opId}.json").delete()
                op.backupPath?.let { File(it).delete() }
            } catch (_: Exception) {
            }
        }
    }

    // ━━ 补偿（审查不变量②③） ━━

    /**
     * 恢复发布前的有效产物状态。契约：
     * - 有备份：备份字节重发（失败重试一次）+ 回读比对；任一不符 → false（Partial，记录保留）
     * - 无备份（首次自定义发布）：仅当交付位内容确实是本次产物（SHA 相符）才删除；
     *   其他内容（如规则库镜像）一律保留；交付位为空视为已完成清理
     * - 恢复成功时同步覆写私有 newPath，使私有/共享/配置三者一致
     */
    fun compensate(op: PendingOp): Boolean = synchronized(lock) {
        try {
            val backupPath = op.backupPath
            if (backupPath != null) {
                val backup = File(backupPath)
                if (!backup.exists()) {
                    log.e("compensate: backup missing for ${op.pkg}")
                    return false
                }
                val bytes = backup.readBytes()
                var ok = store.deliver(op.pkg, bytes)
                if (!ok) {
                    log.i("compensate: deliver refused, retrying once for ${op.pkg}")
                    ok = store.deliver(op.pkg, bytes)
                }
                if (!ok) return false
                val confirmed = store.readDelivered(op.pkg)
                if (confirmed == null || !confirmed.contentEquals(bytes)) {
                    log.e("compensate: restored bytes mismatch for ${op.pkg}")
                    return false
                }
                backup.copyTo(File(op.newPath), overwrite = true)
                log.i("compensate: restored old artifact for ${op.pkg}")
                true
            } else {
                File(op.newPath).delete()
                val delivered = store.readDelivered(op.pkg)
                when {
                    delivered == null -> true
                    sha256(delivered) == op.newSha -> store.remove(op.pkg)
                    else -> {
                        log.i("compensate: shared artifact is not ours, kept for ${op.pkg}")
                        true
                    }
                }
            }
        } catch (e: Exception) {
            log.e("compensate failed (Partial state): ${e.message}")
            false
        }
    }

    // ━━ 重启恢复（审查不变量①：证据收敛，不以文件存在清记录） ━━

    /**
     * 交付位内容 SHA 与 op.newSha 的比对是唯一权威判据。
     * applyConfig 仅在记录里有对应配置快照时调用（旧版本记录只恢复产物，
     * 不触碰配置——当时流程先提交配置，磁盘配置已为新值）。
     * 无法安全收敛时保留记录（Partial），不静默丢弃。
     */
    fun recover(applyConfig: (String?) -> Unit) {
        synchronized(lock) {
            try {
                if (!opsDir.isDirectory) return
                val files = opsDir.listFiles { f -> f.name.endsWith(".json") } ?: return
                if (files.isEmpty()) return
                log.i("recover: ${files.size} pending ops")
                for (f in files) {
                    try {
                        val op = fromRecord(f.readText())
                        if (op == null) {
                            f.renameTo(File(opsDir, f.name + ".corrupt"))
                            log.e("recover: corrupt record ${f.name} → .corrupt")
                            continue
                        }
                        val delivered = store.readDelivered(op.pkg)
                        val deliveredSha = delivered?.let { sha256(it) }
                        val applyOld = {
                            if (op.oldConfigJson.isNullOrBlank()) {
                                log.i("recover ${op.pkg}: no old config recorded — artifact-only")
                            } else {
                                applyConfig(op.oldConfigJson)
                            }
                        }
                        when {
                            // 发布已确认完成 → 幂等补提交新配置
                            deliveredSha != null && deliveredSha == op.newSha -> {
                                if (op.newConfigJson.isNullOrBlank()) {
                                    log.i("recover ${op.pkg}: publish confirmed, no new config recorded")
                                } else {
                                    applyConfig(op.newConfigJson)
                                }
                                f.delete()
                                op.backupPath?.let { File(it).delete() }
                                log.i("recover ${op.pkg}: publish confirmed — config applied, record cleared")
                            }
                            // 发布未完成且有旧产物备份 → 恢复旧版
                            op.backupPath != null -> {
                                val backup = File(op.backupPath)
                                val backupBytes = if (backup.exists()) backup.readBytes() else null
                                var restored = false
                                if (backupBytes != null) {
                                    restored = store.deliver(op.pkg, backupBytes)
                                    if (!restored) restored = store.deliver(op.pkg, backupBytes)
                                    val confirmed = store.readDelivered(op.pkg)
                                    if (restored && (confirmed == null || !confirmed.contentEquals(backupBytes))) {
                                        restored = false
                                    }
                                    if (restored) {
                                        File(op.newPath).writeBytes(backupBytes)
                                        applyOld()
                                        f.delete()
                                        backup.delete()
                                        log.i("recover ${op.pkg}: publish incomplete — old artifact restored")
                                    }
                                }
                                if (!restored) {
                                    log.e("recover ${op.pkg}: restore failed — record kept (Partial)")
                                }
                            }
                            // 首次发布且新产物未交付
                            else -> {
                                if (delivered == null) {
                                    store.remove(op.pkg) // 尽力清理可能残留
                                    File(op.newPath).delete()
                                } else {
                                    log.i("recover ${op.pkg}: delivered content is not ours; kept")
                                }
                                applyOld()
                                f.delete()
                                log.i("recover ${op.pkg}: first publish not delivered — reverted")
                            }
                        }
                    } catch (e: Exception) {
                        log.e("recover: error processing ${f.name}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log.e("recoverOnStartup failed: ${e.message}")
            }
        }
    }

    // ━━ 序列化 ━━

    internal fun toJson(op: PendingOp): String = jsonObj(
        "opId" to op.opId,
        "pkg" to op.pkg,
        "ts" to op.timestamp.toString(),
        "backup" to op.backupPath,
        "new" to op.newPath,
        "stage" to op.stage.name,
        "oldCfg" to op.oldConfigJson,
        "newCfg" to op.newConfigJson,
        "sha" to op.newSha
    )

    /** 兼容 v0.13/14 旧记录（无 stage/sha/config 字段）：sha=""永不与交付位相符 → 走备份/保留分支 */
    internal fun fromRecord(json: String): PendingOp? {
        val opId = jsonGetString(json, "opId") ?: return null
        val pkg = jsonGetString(json, "pkg") ?: return null
        val newPath = jsonGetString(json, "new") ?: return null
        val ts = jsonGetString(json, "ts")?.toLongOrNull() ?: System.currentTimeMillis()
        val backup = jsonGetString(json, "backup")?.takeIf { it.isNotBlank() }
        val stage = try {
            Stage.valueOf(jsonGetString(json, "stage") ?: Stage.PREPARED.name)
        } catch (_: Exception) {
            Stage.PREPARED
        }
        val oldCfg = jsonGetString(json, "oldCfg")?.takeIf { it.isNotBlank() }
        val newCfg = jsonGetString(json, "newCfg")?.takeIf { it.isNotBlank() }
        val sha = jsonGetString(json, "sha") ?: ""
        return PendingOp(opId, pkg, ts, backup, newPath, stage, oldCfg, newCfg, sha)
    }

    private val lock = Any()

    companion object {
        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
