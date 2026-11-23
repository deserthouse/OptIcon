package io.github.deserthouse.opticon.engine

import java.io.File

/**
 * PicpLibrary — 应用图标库在磁盘上的布局与读取路径（纯 JVM）。
 *
 *   {cacheDir}/icons/{pkg}.png        图标
 *   {cacheDir}/icons/index.txt        索引——随图标目录同进同出，是唯一事实源
 *   {cacheDir}/icons.staging|.prev    切换的中间态
 *   {cacheDir}/picp_repo.zip(.tmp)    下载中的 / 下载完成的归档
 *
 * 索引放在目录内，目录切换就是唯一的原子操作：索引与图标不可能错位
 * （修复批 X-06）。读取时按「目录内索引 → 旧版根部索引 → 目录清单」取包集合：
 *  - 第二项兼容 0.19.6-beta 留在根部的索引（那个版本把索引搬到了根部）；
 *  - 第三项兜底索引缺失或不可信的遗留状态——图标目录里有什么，库就有什么。
 */
internal object PicpLibrary {

    const val ICONS_DIR = "icons"
    const val INDEX_FILE = "index.txt"

    /** 0.19.6-beta 的索引位置。修复批起只读兼容，首次成功切换后删除。 */
    const val LEGACY_ROOT_INDEX = "picp_index.txt"

    const val ARCHIVE = "picp_repo.zip"
    const val ARCHIVE_TMP = "picp_repo.zip.tmp"

    /** 索引取自哪一份。 */
    enum class IndexSource { INNER, LEGACY_ROOT, DIRECTORY_LISTING, NONE }

    data class Loaded(val packages: Set<String>, val source: IndexSource)

    /**
     * 读取路径：先收敛上次切换的中间态，再取包集合。
     *
     * [clearStaging] 为 true 时顺带清掉暂存目录和遗留的归档——只能在没有同步
     * 在跑时传 true（同步构建期间暂存目录是活的），由引擎按 [SyncGate] 决定。
     */
    fun readPackages(cacheDir: File, clearStaging: Boolean): Loaded {
        val icons = File(cacheDir, ICONS_DIR)
        if (!FileSnapshotSwitch.recoverIfInterrupted(cacheDir, ICONS_DIR, clearStaging)) {
            // 旧版放不回去：live 缺失，此刻没有可读的库。读取方下次再试。
            return Loaded(emptySet(), IndexSource.NONE)
        }
        if (clearStaging) {
            File(cacheDir, ARCHIVE_TMP).delete()
            File(cacheDir, ARCHIVE).delete()
        }

        // 0.19.6-beta 的根索引换位做到一半就被杀：根部的索引与当前图标目录
        // 是否对应取决于被杀在哪一步，无从分辨——不信任它，按目录清单为准。
        val legacyRoot = File(cacheDir, LEGACY_ROOT_INDEX)
        val legacyTmp = File(cacheDir, "$LEGACY_ROOT_INDEX.tmp")
        if (legacyTmp.exists()) {
            legacyTmp.delete()
            legacyRoot.delete()
        }

        readList(File(icons, INDEX_FILE))?.let { return Loaded(it, IndexSource.INNER) }
        readList(legacyRoot)?.let { return Loaded(it, IndexSource.LEGACY_ROOT) }
        val names = icons.list() ?: return Loaded(emptySet(), IndexSource.NONE)
        val fromListing = names.filter { it.endsWith(".png") }
            .map { it.removeSuffix(".png") }
            .toSet()
        return Loaded(fromListing, IndexSource.DIRECTORY_LISTING)
    }

    /** 新版本落位后调用：根部不再需要索引，旧版留下的换位残片一并清掉。 */
    fun discardLegacyIndex(cacheDir: File) {
        File(cacheDir, LEGACY_ROOT_INDEX).delete()
        File(cacheDir, "$LEGACY_ROOT_INDEX.tmp").delete()
    }

    private fun readList(file: File): Set<String>? {
        if (!file.isFile) return null
        return try {
            file.readLines().filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) {
            null
        }
    }
}
