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
 * 「做完」以结果为准：镜像这一步之后共享目录里必须真的每个规则图标都在（[mirrorsPresent]）。
 * 单个文件写不进去（磁盘满、权限被收回）不会抛异常，只看「镜像这一步有没有跑完」会把没发出去
 * 报成「已同步」。
 *
 * [finish] 返回 false = 规则库已同步，但镜像或撤销清理没有做完（独立复审 D-04：这两件事的结果分开报）。
 *
 * 上游没有新版本时走 [repairIfNeeded]（盲审 BU-02）：共享目录里的镜像可能已经不全（被用户或清理工具
 * 清掉、上一次收尾中途被杀、镜像时写失败），「请再同步一次」要真的能把它们补回来。
 */
internal class RuleSyncFinisher(
    private val importLegacyRules: () -> Unit,
    /** 与保存流水线互斥。[label] 只用于日志与排查。 */
    private val withPipeline: (label: String, block: () -> Unit) -> Unit,
    private val mirrorAll: () -> Unit,
    /** 共享目录里是不是每个该镜像的规则图标都在。核实不了也算不在。默认当作都在（只测四步顺序的旧用例）。 */
    private val mirrorsPresent: () -> Boolean = { true },
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
            if (!present()) {
                complete = false
                warn("rule sync follow-up: some rule icons are missing from the shared dir after mirroring")
            }
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

    /**
     * 规则库没有变化时的收尾：镜像齐全就什么都不做；缺了就重新镜像（幂等）并写版本戳，
     * 让 hook 重新取图。撤销清单不动——这一轮没有撤销，上一轮的清单是什么就是什么。
     *
     * @return false = 镜像不全，补发之后仍然不全（或者镜像这一步出异常）。核实不出结果也按不全处理：
     *         宁可让用户再同步一次，也不把「不知道发没发出去」报成已发布。
     */
    fun repairIfNeeded(): Boolean {
        if (present()) {
            info("rule sync follow-up: snapshot unchanged, mirrors complete — nothing to republish")
            return true
        }
        info("rule sync follow-up: snapshot unchanged, mirrors missing — republishing")
        try {
            withPipeline("ania-sync-mirror", mirrorAll)
        } catch (t: Throwable) {
            warn("rule sync follow-up: republishing failed: $t")
            return false
        }
        val complete = present()
        if (!complete) warn("rule sync follow-up: mirrors still incomplete after republishing")
        if (!writeStamp()) warn("rules stamp write failed — hook icon cache may stay stale")
        return complete
    }

    private fun present(): Boolean = try {
        mirrorsPresent()
    } catch (t: Throwable) {
        warn("rule sync follow-up: could not verify the shared dir: $t")
        false
    }
}
