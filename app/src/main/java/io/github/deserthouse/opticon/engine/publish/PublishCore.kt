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
        val newSha: String,
        /** 事务开始时交付位旧内容的字节快照路径；null=当时交付位为空（C04） */
        val sharedBackupPath: String? = null,
        /** Z1c：事务开始时交付位旧内容的 SHA；null=当时交付位为空。恢复分支的精确判据 */
        val oldSha: String? = null,
        /** R3-1(F01)：移除事务标志——显式字段而非 newSha 哨兵（"" 与 v0.13/14
         *  无 sha 字段的 legacy 记录撞车，补课单测已实证）。 */
        val removal: Boolean = false
    )

    // ━━ 阶段 1：准备 ━━

    fun prepare(pkg: String, pngBytes: ByteArray, oldConfigJson: String?, newConfigJson: String?): PendingOp? =
        synchronized(lock) {
            try {
                bakedDir.mkdirs()
                val opId = UUID.randomUUID().toString()
                val opId8 = opId.take(8)
                val newFile = File(bakedDir, "$pkg.png")
                // K1（Z1b）：备份带 opId——pkg 常量名会被后继事务的 complete 清掉，
                // 残留记录的恢复源随之丢失（K1 分支3 永久 Partial 的根因）
                val oldFile = File(bakedDir, "$pkg.old-$opId8.png")
                val backupPath = if (newFile.exists()) {
                    newFile.copyTo(oldFile, overwrite = true)
                    oldFile.absolutePath
                } else null
                newFile.writeBytes(pngBytes)
                // C04：交付位旧内容快照——私有 baked 备份不代表共享权威旧态；
                // 首次自定义发布覆盖了规则库镜像等共享内容时，补偿的恢复源在此
                val deliveredOld = store.readDelivered(pkg)
                val sharedBackupPath = deliveredOld?.let { oldShared ->
                    val bak = File(bakedDir, "$pkg.shared-$opId8.bak")
                    bak.writeBytes(oldShared)
                    bak.absolutePath
                }
                PendingOp(
                    opId = opId,
                    pkg = pkg, backupPath = backupPath, newPath = newFile.absolutePath,
                    oldConfigJson = oldConfigJson, newConfigJson = newConfigJson,
                    newSha = sha256(pngBytes),
                    sharedBackupPath = sharedBackupPath,
                    oldSha = deliveredOld?.let { sha256(it) }
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
            // R3-2(F04)：Android 上 renameTo 为原子替换（POSIX 语义，目标存在
            // 也可替换）——PUBLISHED 覆写被中断不再可能留下半写 JSON。Windows
            // 测试环境 renameTo 目标存在即失败 → 回退 copyTo（仅测试路径）。
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
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
            // R3-2(F04)：同 record——原子替换优先
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }
    } catch (e: Exception) {
        log.e("markPublished failed: ${e.message}")
        false
    }

    // ━━ 阶段 5：完成 ━━

    fun complete(op: PendingOp, metaDetail: String? = null) {
        synchronized(lock) {
            try {
                File(opsDir, "${op.opId}.json").delete()
                if (metaDetail != null) {
                    File(bakedDir, "${op.pkg}.meta").writeText(metaDetail)
                }
                op.backupPath?.let { File(it).delete() }
                op.sharedBackupPath?.let { File(it).delete() }
                // K1（Z1）：同包吸收——本事务成功收敛后，同 pkg 的其他残留记录
                // 全部作废（它们描述的状态已被后继覆盖，恢复只会造成回滚）
                opsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { stale ->
                    val stalePkg = runCatching { fromRecord(stale.readText())?.pkg }.getOrNull()
                    if (stalePkg == op.pkg) stale.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    // ━━ 移除事务（R3-1/F01：关闭=可恢复的移除发布，与启用同构） ━━

    /**
     * 关闭路径的阶段 1：快照当前交付位（旧自定义图）+ 登记移除意图。
     * newSha="" 为移除标志；newConfigJson 携带 disabled 配置供恢复收敛。
     */
    fun prepareRemoval(pkg: String, oldConfigJson: String?, newConfigJson: String?): PendingOp? =
        synchronized(lock) {
            try {
                bakedDir.mkdirs()
                val opId = UUID.randomUUID().toString()
                val opId8 = opId.take(8)
                val deliveredOld = store.readDelivered(pkg)
                val sharedBackupPath = deliveredOld?.let { old ->
                    val bak = File(bakedDir, "$pkg.shared-$opId8.bak")
                    bak.writeBytes(old)
                    bak.absolutePath
                }
                PendingOp(
                    opId = opId, pkg = pkg, backupPath = null,
                    newPath = File(bakedDir, "$pkg.png").absolutePath,
                    stage = Stage.PREPARED,
                    oldConfigJson = oldConfigJson, newConfigJson = newConfigJson,
                    newSha = "",
                    removal = true,
                    sharedBackupPath = sharedBackupPath,
                    oldSha = deliveredOld?.let { sha256(it) }
                )
            } catch (e: Exception) {
                log.e("prepareRemoval failed: ${e.message}")
                null
            }
        }

    /** 移除发布的交付阶段：删除共享产物并回读确认（与 publish 对称，不变量④）。 */
    fun publishRemoval(op: PendingOp): Boolean = synchronized(lock) {
        try {
            if (!store.remove(op.pkg)) {
                log.e("publishRemoval: remove refused for ${op.pkg}")
                return false
            }
            val confirmed = store.readDelivered(op.pkg)
            if (confirmed != null) {
                log.e("publishRemoval: delivered still present for ${op.pkg}")
                return false
            }
            markPublishedLocked(op)
        } catch (e: Exception) {
            log.e("publishRemoval failed: ${e.message}")
            false
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
            // C04：共享快照优先——它才是事务开始时交付位的真实旧态（无私有
            // baked 备份的首次自定义发布也靠它恢复被覆盖的规则库镜像）
            val sharedBak = op.sharedBackupPath?.let { File(it) }?.takeIf { it.exists() }
            val backupPath = if (sharedBak != null) null else op.backupPath
            if (sharedBak != null) {
                val bytes = sharedBak.readBytes()
                // 交付位未被本事务改动（仍等于快照）→ 已是一致态，免重发
                val current = store.readDelivered(op.pkg)
                if (current != null && current.contentEquals(bytes)) {
                    log.i("compensate: shared artifact already consistent for ${op.pkg}")
                    return true
                }
                var ok = store.deliver(op.pkg, bytes)
                if (!ok) {
                    log.i("compensate: shared-restore refused, retrying once for ${op.pkg}")
                    ok = store.deliver(op.pkg, bytes)
                }
                if (!ok) return false
                val confirmed = store.readDelivered(op.pkg)
                if (confirmed == null || !confirmed.contentEquals(bytes)) {
                    log.e("compensate: shared-restore bytes mismatch for ${op.pkg}")
                    return false
                }
                // K6（Z2）：契约"三者一致"——私有 newPath 同步回写
                File(op.newPath).writeBytes(bytes)
                log.i("compensate: restored previous shared artifact for ${op.pkg}")
                true
            } else if (backupPath != null) {
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
    fun recover(applyConfig: (String?) -> Boolean) {
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
                        // C03：配置提交结果决定收敛——false 时本记录整体保留（Partial）
                        fun applyOld(): Boolean = if (op.oldConfigJson.isNullOrBlank()) {
                            log.i("recover ${op.pkg}: no old config recorded — artifact-only")
                            true
                        } else applyConfig(op.oldConfigJson)

                        // R3-1(F01)：移除事务恢复（rm 字段显式标志；不用 newSha
                        // 哨兵——与无 sha 字段的 v0.13/14 legacy 记录撞车，单测实证）——
                        // 交付位空=移除已确认（提交 disabled 配置后清账）；
                        // 交付位仍有内容=移除未确认（回滚快照+旧配置，Partial 保留）
                        if (op.removal) {
                            when {
                                delivered == null -> {
                                    val cfgOk = if (op.newConfigJson.isNullOrBlank()) true else applyConfig(op.newConfigJson)
                                    if (cfgOk) {
                                        f.delete()
                                        op.sharedBackupPath?.let { File(it).delete() }
                                        File(op.newPath).takeIf { it.isFile }?.delete()
                                        log.i("recover ${op.pkg}: removal confirmed — disabled config applied, record cleared")
                                    } else log.e("recover ${op.pkg}: disabled config commit failed — record kept (Partial)")
                                }
                                else -> {
                                    val bak = op.sharedBackupPath?.let { File(it) }?.takeIf { it.exists() }
                                    if (bak == null) {
                                        store.remove(op.pkg)
                                        val cfgOk = applyOld()
                                        if (cfgOk) {
                                            f.delete()
                                            log.i("recover ${op.pkg}: removal rolled back (delivery was empty) — old config applied")
                                        } else log.e("recover ${op.pkg}: removal rollback config failed — record kept (Partial)")
                                    } else {
                                        val bytes = bak.readBytes()
                                        var okRestore = store.deliver(op.pkg, bytes)
                                        if (!okRestore) okRestore = store.deliver(op.pkg, bytes)
                                        val confirmed = store.readDelivered(op.pkg)
                                        if (okRestore && confirmed != null && confirmed.contentEquals(bytes)) {
                                            File(op.newPath).writeBytes(bytes)
                                            val cfgOk = applyOld()
                                            if (cfgOk) {
                                                f.delete()
                                                bak.delete()
                                                log.i("recover ${op.pkg}: removal rolled back — previous artifact restored")
                                            } else log.e("recover ${op.pkg}: removal rollback config failed — record kept (Partial)")
                                        } else log.e("recover ${op.pkg}: removal rollback deliver failed — record kept (Partial)")
                                    }
                                }
                            }
                            continue
                        }
                        // V07（Y1b）：共享快照是事务开始时交付位的真实旧态——
                        // 私有备份缺失不得推断"没有旧交付"
                        val sharedBakFile = op.sharedBackupPath?.let { File(it) }?.takeIf { it.exists() }
                        when {
                            // 发布已确认完成 → 幂等补提交新配置
                            deliveredSha != null && deliveredSha == op.newSha -> {
                                val cfgOk = if (op.newConfigJson.isNullOrBlank()) {
                                    log.i("recover ${op.pkg}: publish confirmed, no new config recorded")
                                    true
                                } else applyConfig(op.newConfigJson)
                                if (cfgOk) {
                                    f.delete()
                                    op.backupPath?.let { File(it).delete() }
                                    op.sharedBackupPath?.let { File(it).delete() }
                                    log.i("recover ${op.pkg}: publish confirmed — config applied, record cleared")
                                } else {
                                    log.e("recover ${op.pkg}: config commit failed — record kept (Partial)")
                                }
                            }
                            // V07（Y1b）：发布未完成但交付位有共享快照 → 恢复快照内容
                            sharedBakFile != null -> {
                                val bytes = sharedBakFile.readBytes()
                                var ok = store.deliver(op.pkg, bytes)
                                if (!ok) ok = store.deliver(op.pkg, bytes)
                                val confirmed = store.readDelivered(op.pkg)
                                if (ok && confirmed != null && confirmed.contentEquals(bytes)) {
                                    File(op.newPath).writeBytes(bytes)
                                    val cfgOk = applyOld()
                                    if (cfgOk) {
                                        f.delete()
                                        sharedBakFile.delete()
                                        op.backupPath?.let { File(it).delete() }
                                        log.i("recover ${op.pkg}: shared snapshot restored — old artifact recovered")
                                    } else {
                                        log.e("recover ${op.pkg}: config revert failed — record kept (Partial)")
                                    }
                                } else {
                                    log.e("recover ${op.pkg}: shared restore failed — record kept (Partial)")
                                }
                            }
                            // 发布未完成且有旧产物备份 → 恢复旧版
                            // K1（Z1c）：备份文件已被后继事务清场（按 opId 命名后缺失
                            // 即真缺失）且交付位非本事务产物 → 无可恢复内容，只清记录
                            // 发布未完成且有旧产物备份 → 恢复旧版（后继已在顶部拦截）
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
                                        val cfgOk = applyOld()
                                        if (cfgOk) {
                                            f.delete()
                                            backup.delete()
                                            op.sharedBackupPath?.let { File(it).delete() }
                                        } else {
                                            restored = false
                                        }
                                        log.i("recover ${op.pkg}: publish incomplete — old artifact restored" + if (cfgOk) "" else " (config kept Partial)")
                                    }
                                }
                                if (!restored) {
                                    log.e("recover ${op.pkg}: restore failed — record kept (Partial)")
                                }
                            }
                            // 首次发布且新产物未交付
                            // K1（Z1c）：oldSha 精确三态——
                            //   a) 交付位仍是事务前内容（SHA 相符或双方皆空）→ 回退安全
                            //   b) 交付位是后继写入（≠oldSha≠newSha）→ 未知状态只清记录
                            //   c) 交付位为空且 oldSha=null → 事务前后皆空，回退
                            else -> {
                                val wasEmptyBefore = op.oldSha == null
                                val matchesPreState = delivered != null && op.oldSha != null &&
                                    deliveredSha == op.oldSha
                                if (delivered != null && !wasEmptyBefore && !matchesPreState &&
                                    deliveredSha != op.newSha) {
                                    // 后继写入（如后续保存已成功）→ 配置不得回滚，只清记录
                                    log.i("recover ${op.pkg}: successor write detected — record only cleared")
                                    f.delete()
                                    op.sharedBackupPath?.let { File(it).delete() }
                                } else {
                                    if (delivered == null) {
                                        store.remove(op.pkg) // 尽力清理可能残留
                                        File(op.newPath).delete()
                                    } else {
                                        log.i("recover ${op.pkg}: delivered content is not ours; kept")
                                    }
                                    if (applyOld()) {
                                        f.delete()
                                        op.sharedBackupPath?.let { File(it).delete() }
                                        log.i("recover ${op.pkg}: first publish not delivered — reverted")
                                    } else {
                                        log.e("recover ${op.pkg}: config revert failed — record kept (Partial)")
                                    }
                                }
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
        "sha" to op.newSha,
        "sharedBak" to op.sharedBackupPath,
        "oldSha" to op.oldSha,
        "rm" to if (op.removal) "1" else ""
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
        val sharedBak = jsonGetString(json, "sharedBak")?.takeIf { it.isNotBlank() }
        val oldSha = jsonGetString(json, "oldSha")?.takeIf { it.isNotBlank() }
        val removal = jsonGetString(json, "rm") == "1"
        return PendingOp(opId, pkg, ts, backup, newPath, stage, oldCfg, newCfg, sha, sharedBak, oldSha, removal)
    }

    private val lock = Any()

    companion object {
        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
