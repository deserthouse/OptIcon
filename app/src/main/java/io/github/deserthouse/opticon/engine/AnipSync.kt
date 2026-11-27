package io.github.deserthouse.opticon.engine

import android.content.Context
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.util.Locale

/**
 * AnipSync — ANIP (Android Notification Icon Project) rules sync.
 *
 * Upstream: https://github.com/BetterAndroid/android-notification-icon-project
 * (renamed from fankes/AndroidNotifyIconAdapt, license AGPL-3.0 -> Apache-2.0).
 * The old repository's json/ data was removed upstream — the legacy base64
 * subscription format is dead; this adapter speaks the new format instead:
 *
 *   icons/<category>/manifest.json — { "<pkg>": { label, format, color, overlay, contributors } }
 *   icons/<category>/res/<pkg>.png — 72px monochrome (LA) compliant glyph
 *
 * Transport: ONE zipball request (main branch archive, ~3MB) instead of ~870
 * individual raw fetches — direct GitHub connections from constrained networks
 * reset long bursts of small requests (empirically dies after ~50 in a row).
 *
 * Categories: app, game, system/common, system/coloros, system/mios.
 * Output layout is identical to IconLibEngine.ingestJson (dir/{pkg}.png +
 * meta.json), so every downstream consumer (IconLibEngine.lookup,
 * bakeRemote, hook-side cache) works unchanged.
 *
 * Batch SYNC V2: the sync is snapshot-atomic. The new version is built
 * completely inside a sibling staging dir, then swapped in via
 * FileSnapshotSwitch. Upstream revocations (a package absent from the new
 * bundle) take effect — the old live dir is replaced wholesale — and an
 * interrupted sync can no longer leave a half-new/half-old mix (U02: the
 * previous write-in-place loop never deleted anything, so hasIcon/lookup,
 * which key on file existence, could never observe a revocation).
 *
 * 修复批（X-03 / X-07 / X-15 / X-17 / X-23）之后本类只是外壳：构建逻辑在
 * [AnipSnapshotBuilder]（纯 JVM、有测试），读取路径的恢复在 [RuleLibrary]。
 *  - 清单解析不了 = 整体失败；声明了但读不出的包沿用旧图标（失败 ≠ 撤销）；
 *  - 归档根目录自动探测，[url] 可指向自定义归档（默认仍是上游官方地址）；
 *  - 切换失败由原语自己恢复，调用方不再回滚。
 */
object AnipSync {

    private const val TAG = "OptIcon/AnipSync"
    const val BASE =
        "https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/"
    const val ZIP_URL =
        "https://github.com/BetterAndroid/android-notification-icon-project/archive/refs/heads/main.zip"
    val CATEGORIES = listOf("app", "game", "system/common", "system/coloros", "system/mios")

    /** Internal legacy builtin source id (kept stable so stored prefs resolve). */
    const val BUILTIN_SOURCE_ID = "ania_raw"

    /**
     * 一次规则库同步的结果。count ≤ 0 = 失败，现有目录不动。
     * [revokedPackages]：同步前在库里、新版本里没有的包（上游撤销）——
     * 供调用方在别的读取通道上把它们的图标一并清掉（修复批 X-04）。
     */
    data class SyncOutcome(val count: Int, val revokedPackages: List<String> = emptyList())

    /** 内置源的地址是清单基址（历史遗留），不是归档地址；其余地址视为自定义归档地址。 */
    fun archiveUrlFor(sourceUrl: String): String = if (sourceUrl == BASE) ZIP_URL else sourceUrl

    /**
     * Sync ANIP resources into [dir] using the legacy cache layout
     * ({pkg}.png + meta.json). Single zipball download; the new version is
     * built in a staging dir and switched in atomically. Returns the number
     * of icons in the new snapshot; 0 = failure (live state untouched).
     */
    internal fun syncTo(
        dir: File,
        onProgress: ((String) -> Unit)? = null,
        context: Context? = null,
        url: String = ZIP_URL
    ): Int = syncDetailed(dir, onProgress, context, url).count

    /** [syncTo] 的完整结果（含被撤销的包）。同步是阻塞的，调用方负责线程。
     *  [ops] 是测试注入点，生产用默认值。 */
    internal fun syncDetailed(
        dir: File,
        onProgress: ((String) -> Unit)? = null,
        context: Context? = null,
        url: String = ZIP_URL,
        ops: FileSnapshotSwitch.FsOps = FileSnapshotSwitch.FsOps.Default
    ): SyncOutcome {
        // Context-free callers (hook self-sync) fall back to locale tables so the
        // progress strings stay single-language on either locale.
        fun progress(resId: Int) = onProgress?.invoke(
            if (context != null) context.getString(resId)
            else if (Locale.getDefault().language == "zh") ZH_PROGRESS[resId].orEmpty()
            else EN_PROGRESS[resId].orEmpty()
        )
        val failed = SyncOutcome(0)
        // 入口收敛。旧版放不回去就拒绝同步：绝不能在完好的 prev 旁边建一个空的 live
        // ——下一次收敛会把它当成「新版已上线」而删掉 prev（X-07）。
        if (!RuleLibrary.prepareForSync(dir, ops)) {
            TraceLogger.w(TAG, "ANIP sync refused: previous snapshot cannot be restored")
            return failed
        }
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        val parent = dir.parentFile ?: return failed
        // Zip lands OUTSIDE dir — the whole dir is about to be replaced, and
        // this also sweeps legacy in-dir zips on the first snapshot switch.
        val zipFile = File(parent, dir.name + RuleLibrary.ARCHIVE_TMP_SUFFIX)
        val staging = FileSnapshotSwitch.stagingOf(parent, dir.name)
        try {
            progress(io.github.deserthouse.opticon.R.string.sync_anip_bundle)
            if (!NetworkExecutor.fetchToFile(url, zipFile, null)) {
                TraceLogger.w(TAG, "ANIP zipball download failed")
                return failed
            }
            progress(io.github.deserthouse.opticon.R.string.sync_unpacking)
            val before = RuleLibrary.packages(dir)
            val built = when (
                val result = AnipSnapshotBuilder.build(
                    zipFile, oldDir = dir, staging = staging,
                    preferZh = Locale.getDefault().language == "zh"
                )
            ) {
                is AnipSnapshotBuilder.Result.Rejected -> {
                    TraceLogger.w(TAG, "ANIP snapshot rejected (${result.reason}) — keeping live dir")
                    return failed
                }
                is AnipSnapshotBuilder.Result.Built -> result
            }
            val stats = built.stats
            TraceLogger.i(
                TAG,
                "ANIP snapshot built: ${built.items.size} icons " +
                    "(declared ${stats.declared}, written ${stats.written}, rendered ${stats.rendered}, " +
                    "aliased ${stats.aliased}, carried over ${stats.carriedOver}, missing ${stats.missing}, " +
                    "unresolved ${stats.unresolved})"
            )

            // Switch: staging validated, swap atomically. IconLibEngine's
            // cacheDir File is path-keyed (re-resolved per lookup) and the App
            // caller rebuilds metaIndex/l1Cache right after this returns, so
            // no extra invalidation hook is needed.
            try {
                FileSnapshotSwitch.switchIn(parent, dir.name, ops)
                dir.setExecutable(true, false)
                dir.setReadable(true, false)
            } catch (e: Exception) {
                // switchIn 自己负责恢复：返回前旧版已放回原位，
                // 或停在读取路径认得的中间态（X-23，调用方不再回滚）。
                TraceLogger.w(TAG, "ANIP switch failed, live dir restored: ${e.message}")
                return failed
            }
            val after = HashSet<String>(built.items.size)
            built.items.forEach { after.add(it.pkg) }
            val revoked = before.filter { it !in after }.sorted()
            TraceLogger.i(TAG, "ANIP sync: ${built.items.size} icons switched in, ${revoked.size} revoked")
            return SyncOutcome(built.items.size, revoked)
        } catch (e: Exception) {
            TraceLogger.w(TAG, "ANIP sync error: ${e.message}")
            return failed
        } finally {
            // 清理先于返回：归档与没上位的暂存目录都不留
            staging.deleteRecursively()
            zipFile.delete()
        }
    }

    private val ZH_PROGRESS = mapOf(
        io.github.deserthouse.opticon.R.string.sync_anip_bundle to "正在下载 ANIP 资源包…",
        io.github.deserthouse.opticon.R.string.sync_unpacking to "正在解包 ANIP 资源…"
    )
    private val EN_PROGRESS = mapOf(
        io.github.deserthouse.opticon.R.string.sync_anip_bundle to "Downloading ANIP bundle…",
        io.github.deserthouse.opticon.R.string.sync_unpacking to "Unpacking ANIP resources…"
    )
}
