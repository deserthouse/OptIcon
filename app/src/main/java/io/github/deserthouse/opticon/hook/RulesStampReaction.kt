package io.github.deserthouse.opticon.hook

/**
 * 规则版本戳变化之后要做的事（批 MT M2：从 NotificationHook 里抽出来，单测进得去）。
 *
 * 应用侧一次规则库同步的收尾会写新的版本戳。hook 看到它变化时：
 *  ① 位图缓存整体作废（作废不回收位图，仍在显示的图标不受影响，之后按需重新读取）；
 *  ② 按撤销清单删掉 hook 本地缓存里被撤销的包——共享目录的镜像由应用侧删，本地缓存是独立的一份快照，
 *     不清的话，hook 在共享目录未命中后退到本地缓存，被撤销的图标还会显示出来（X-04）；
 *  ③ 叫醒自同步循环（自动更新开着时，它会让本地缓存与共享目录重新一致）。
 *
 * ② 出意外不打断 ③：循环醒来读到的是当下真实的状态，少删的文件它会重新同步掉。
 */
internal class RulesStampReaction(
    private val dropIconCache: () -> Unit,
    private val dropLocalPackages: (revoked: List<String>) -> Unit,
    private val wakeSyncLoop: () -> Unit,
    private val warn: (String) -> Unit = {},
) {
    fun onStampChanged(revoked: List<String>) {
        dropIconCache()
        if (revoked.isNotEmpty()) {
            try {
                dropLocalPackages(revoked)
            } catch (t: Throwable) {
                warn("drop revoked packages failed: ${t.message}")
            }
        }
        wakeSyncLoop()
    }
}
