package io.github.deserthouse.opticon.engine

/**
 * 规则库同步成功之后的收尾（批 MT M2：从 SettingsViewModel 里抽出来，单测进得去）。
 *
 * 四步，顺序是契约：
 *  ① 当前源确实是 Legacy JSON 时才读入旧版规则文件（X-21）；
 *  ② 把规则库镜像到共享目录（跳过用户自己配过图标的包）；
 *  ③ 删掉被撤销、且用户没有启用自定义的包的共享镜像（X-04）；
 *  ④ 先写撤销清单、再写规则版本戳——先后不能反：hook 看到版本戳变化时读清单，
 *     删它本地缓存里对应的文件（本地缓存是独立的一份快照，只清共享目录的话，hook 退到本地缓存
 *     还会把被撤销的图标显示出来）。
 *
 * ② ③ 各自在流水线锁内做（与「保存自定义图标」互斥）。出异常不打断后面的步骤，只是收尾记为不完整；
 * ④ 写失败只记日志——规则库确实换了，版本戳写不成时 hook 的图标缓存可能留在旧图上。
 *
 * [finish] 返回 false = 规则库已同步，但镜像或撤销清理没有做完（独立复审 D-04：这两件事的结果分开报）。
 */
internal class RuleSyncFinisher(
    private val importLegacyRules: () -> Unit,
    /** 与保存流水线互斥。[label] 只用于日志与排查。 */
    private val withPipeline: (label: String, block: () -> Unit) -> Unit,
    private val mirrorAll: () -> Unit,
    private val isCustomized: (pkg: String) -> Boolean,
    private val deleteMirror: (pkg: String) -> Boolean,
    private val writeRevokedList: (revoked: List<String>) -> Boolean,
    private val writeStamp: () -> Boolean,
    private val warn: (String) -> Unit,
    private val info: (String) -> Unit,
) {
    fun finish(revoked: List<String>): Boolean {
        importLegacyRules()

        var complete = true
        try {
            withPipeline("ania-sync-mirror", mirrorAll)
        } catch (t: Throwable) {
            complete = false
            warn("rule sync follow-up: mirroring failed: $t")
        }

        // 撤销清理逐包尽力而为，失败只记日志。
        // 「是否启用自定义」的判定放在流水线锁内求值——判定与删除之间若插进一次
        // 「为同一个包保存自定义」的保存，删掉的会是用户刚保存的图标。
        var removed = 0
        var kept = 0
        try {
            withPipeline("ania-sync-revoke") {
                val doomed = RuleSyncFollowUp.mirrorsToDelete(revoked, isCustomized)
                kept = revoked.size - doomed.size
                doomed.forEach { pkg ->
                    runCatching { deleteMirror(pkg) }
                        .onSuccess { if (it) removed++ }
                        .onFailure { warn("revoke cleanup failed for $pkg: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            complete = false
            warn("rule sync follow-up: revoke cleanup failed: $t")
        }
        info(
            "rule sync follow-up: revoked=${revoked.size}, mirrors removed=$removed, kept (customized)=$kept" +
                if (complete) "" else " (incomplete)"
        )

        if (!writeRevokedList(revoked)) warn("revoked list write failed — hook-local cache may keep revoked icons")
        if (!writeStamp()) warn("rules stamp write failed — hook icon cache may stay stale")
        return complete
    }
}
