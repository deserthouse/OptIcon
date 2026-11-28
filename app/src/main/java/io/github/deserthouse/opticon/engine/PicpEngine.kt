package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File

/**
 * PicpEngine — downloads ALL PICP icons as a GitHub zipball, extracts locally.
 * No more API rate limits, no more step-by-step enumeration.
 * One big download, then done.
 *
 * 批 SYNC V1/V4 的原子更新、字节预算与取消现在都住在 [PicpSyncOrchestrator]（纯 JVM、
 * 有测试）与 [PicpLibrary]（磁盘布局与读取路径）里。本类只是外壳：线程、资源文案、
 * 进程级同步互斥（[SyncGate]）。
 *
 * 修复批（X-06/X-08/X-16/X-18/X-19/X-22/X-23）之后的行为变化：
 *  - 索引随图标目录走（目录内的 index.txt 是唯一事实源），不再有「根索引换位」；
 *  - 读路径先收敛上次切换的中间态，空闲时顺带清暂存目录与遗留归档；
 *  - 截断的归档一律判无效；取消直达网络调用；
 *  - 结果区分「失败 / 取消 / 已有同步在进行」，后两者不是失败。
 */
object PicpEngine {

    private const val TAG = "OptIcon/PicpEngine"
    private const val CACHE_DIR = "picp"

    /** Batch SYNC V4: hard download budget — real zipball is ~0.5GB; 2GB
     *  absorbs reasonable growth while still capping a hostile source. */
    const val MAX_ZIP_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** 同步的最终结果。[Cancelled] 与 [Busy] 不是失败——界面不以错误色显示（X-18）。 */
    sealed class SyncEnd {
        data class Success(val count: Int) : SyncEnd()
        data class Failed(val message: String) : SyncEnd()
        data class Cancelled(val message: String) : SyncEnd()
        data class Busy(val message: String) : SyncEnd()
    }

    /** 在库中的包集合。整份替换，读方拿到的永远是完整的一份。 */
    @Volatile
    private var packages: Set<String> = emptySet()

    /** 资源文案。生产走 Context.getString；单测换成不依赖 Android 资源的实现
     *  （android.jar 的桩里 getString 永远返回 null，引擎外壳的接线测试走不通）。 */
    internal var strings: (Context, Int, Array<out Any?>) -> String = { c, id, args ->
        if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }

    private fun Context.txt(id: Int, vararg args: Any?): String = strings(this, id, args)

    private val indexLock = Any()

    /** 进行中的同步的取消信号；没有同步在跑时为 null。 */
    @Volatile
    private var activeToken: NetworkExecutor.CancelToken? = null

    fun getIndexedCount(context: Context): Int {
        ensureIndex(context)
        return packages.size
    }

    fun hasIcon(packageName: String, context: Context): Boolean {
        ensureIndex(context)
        return packageName in packages
    }

    /** Batch SYNC V4: request cancellation of an in-flight sync. 没有同步在跑时
     *  是 no-op——不会给下一次同步留下一个陈旧的取消位。 */
    fun syncCancel() {
        activeToken?.cancel()
    }

    /** Download entire PICP repo as zipball, extract into staging, validate,
     *  atomically switch. An empty/invalid/truncated payload fails WITHOUT
     *  touching the live library. [onResult] 在后台线程回调，回调时盘上的
     *  清理已经做完。 */
    fun syncIndex(
        context: Context,
        url: String,
        onProgress: ((String) -> Unit)?,
        onResult: (SyncEnd) -> Unit
    ) {
        // 进程级互斥（X-22）：第二次发起直接得到「已有同步在进行」，
        // 不排队，也不去碰另一个同步正在构建的暂存目录。
        if (!SyncGate.tryAcquire()) {
            onResult(SyncEnd.Busy(context.txt(R.string.sync_busy)))
            return
        }
        val token = NetworkExecutor.CancelToken()
        activeToken = token
        val cacheDir = File(context.filesDir, CACHE_DIR)
        val worker = Thread {
            val end: SyncEnd = try {
                runAndReload(context, url, cacheDir, token, onProgress)
            } finally {
                activeToken = null
                SyncGate.release()
            }
            if (end is SyncEnd.Success) {
                onProgress?.invoke(context.txt(R.string.sync_picp_extracted, end.count))
            }
            onResult(end)
        }
        try {
            worker.start()
        } catch (t: Throwable) {
            // 线程都没起来（起不了新线程等）：锁由调用线程放掉，并如实回调
            TraceLogger.w(TAG, "PICP sync thread could not start: $t")
            activeToken = null
            SyncGate.release()
            onResult(SyncEnd.Failed(context.txt(R.string.picp_fail_generic, t.message ?: "")))
        }
    }

    /** 在同步线程上执行编排并让内存里的包集合与磁盘对齐。
     *  编排层只接 Exception；逃到这里 Throwable（内存不足等 Error、线程体里的任何意外）
     *  一律兜成「失败」结果——不兜的话回调不发，界面永远停在「同步中」。锁由调用方的 finally 放。 */
    private fun runAndReload(
        context: Context,
        url: String,
        cacheDir: File,
        token: NetworkExecutor.CancelToken,
        onProgress: ((String) -> Unit)?
    ): SyncEnd = try {
        val end = executeSync(context, url, cacheDir, token, onProgress)
        // 磁盘状态已定（清理在编排函数返回前完成），内存里的包集合与磁盘对齐之后才回调
        packages = readFromDisk(context).packages
        end
    } catch (t: Throwable) {
        TraceLogger.w(TAG, "PICP sync thread died: $t")
        SyncEnd.Failed(context.txt(R.string.picp_fail_generic, t.message ?: ""))
    }

    /** 在同步线程上阻塞执行一次编排，把编排结果翻译成带文案的最终结果。 */
    private fun executeSync(
        context: Context,
        url: String,
        cacheDir: File,
        token: NetworkExecutor.CancelToken,
        onProgress: ((String) -> Unit)?
    ): SyncEnd = try {
        val outcome = PicpSyncOrchestrator.run(
            cacheDir = cacheDir,
            download = PicpSyncOrchestrator.Downloader { dest, onBytes ->
                val result = NetworkExecutor.fetchToFileCancellable(
                    url, dest, token, MAX_ZIP_BYTES, onBytes
                )
                PicpSyncOrchestrator.FetchResult.valueOf(result.name)
            },
            cancelled = { token.isCancelled },
            onStage = { stage -> onProgress?.invoke(stageText(context, stage)) },
        )
        when (outcome) {
            is PicpSyncOrchestrator.Outcome.Success -> SyncEnd.Success(outcome.count)
            PicpSyncOrchestrator.Outcome.Empty ->
                SyncEnd.Failed(context.txt(R.string.picp_fail_empty))
            is PicpSyncOrchestrator.Outcome.Invalid ->
                SyncEnd.Failed(context.txt(R.string.picp_fail_invalid, outcome.detail))
            PicpSyncOrchestrator.Outcome.DownloadFailed ->
                SyncEnd.Failed(context.txt(R.string.picp_fail_download))
            PicpSyncOrchestrator.Outcome.SwitchFailed ->
                SyncEnd.Failed(context.txt(R.string.picp_fail_switch))
            PicpSyncOrchestrator.Outcome.Cancelled ->
                SyncEnd.Cancelled(context.txt(R.string.picp_fail_cancelled))
            PicpSyncOrchestrator.Outcome.TooLarge ->
                SyncEnd.Failed(context.txt(R.string.picp_fail_too_large))
            PicpSyncOrchestrator.Outcome.StorageFailed ->
                SyncEnd.Failed(context.txt(R.string.sync_storage_full))
        }
    } catch (e: Exception) {
        TraceLogger.w(TAG, "PICP sync error: $e")
        SyncEnd.Failed(context.txt(R.string.picp_fail_generic, e.message ?: ""))
    }

    private fun ensureIndex(context: Context) {
        if (packages.isNotEmpty()) return
        synchronized(indexLock) {
            if (packages.isNotEmpty()) return
            packages = readFromDisk(context).packages
        }
    }

    /** 读路径：收敛中间态；暂存目录与遗留归档只在没有同步在跑时才清（判断与清理之间不留缝隙）。 */
    private fun readFromDisk(context: Context): PicpLibrary.Loaded {
        val cacheDir = File(context.filesDir, CACHE_DIR)
        val loaded = try {
            SyncGate.ifIdle(
                whenIdle = { PicpLibrary.readPackages(cacheDir, clearStaging = true) },
                whenBusy = { PicpLibrary.readPackages(cacheDir, clearStaging = false) }
            )
        } catch (e: Exception) {
            TraceLogger.w(TAG, "PICP library read failed: $e")
            PicpLibrary.Loaded(emptySet(), PicpLibrary.IndexSource.NONE)
        }
        if (loaded.source != PicpLibrary.IndexSource.INNER) {
            TraceLogger.i(TAG, "PICP index source: ${loaded.source} (${loaded.packages.size} packages)")
        }
        return loaded
    }

    private fun stageText(context: Context, stage: PicpSyncOrchestrator.Stage): String =
        when (stage) {
            PicpSyncOrchestrator.Stage.Downloading ->
                context.txt(R.string.sync_picp_streaming)
            is PicpSyncOrchestrator.Stage.Bytes -> {
                val total = stage.total
                val pct = if (total > 0) (stage.downloaded * 100 / total).toInt() else 0
                val sizeText = "${stage.downloaded / 1024 / 1024}MB" +
                    if (total > 0) " / ${total / 1024 / 1024}MB ($pct %)" else ""
                context.txt(R.string.sync_picp_bytes, sizeText)
            }
            PicpSyncOrchestrator.Stage.Retrying ->
                context.txt(R.string.sync_picp_retrying)
            PicpSyncOrchestrator.Stage.Extracting ->
                context.txt(R.string.sync_picp_extracting)
        }

    /** Download PICP icon — reads from local cache, trims transparent padding, scales for preview. */
    fun downloadIcon(context: Context, packageName: String): Bitmap? {
        if (!hasIcon(packageName, context)) return null

        val iconDir = File(context.filesDir, CACHE_DIR)
        val cacheFile = File(iconDir, "icons/$packageName.png")
        if (!cacheFile.exists()) return null
        val src = BitmapFactory.decodeFile(cacheFile.absolutePath) ?: return null
        return IconNormalizer.normalize(src)
    }

    /** Prefetch PICP icons — no-op since zipball extraction already has all icons.
     *  回调在一条没有调用方兜底的线程上执行（批 MT M4）：查询或 onProgress 抛出异常，线程就带着异常结束、
     *  onDone 永远不来——界面停在「同步中」。所以先算结果，再逐个回调，每个回调各自兜底，onDone 一定送达。 */
    fun prefetchForInstalled(
        context: Context,
        installedPackages: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((Int) -> Unit)? = null
    ) {
        Thread {
            val targets = try {
                installedPackages.filter { hasIcon(it, context) }
            } catch (t: Throwable) {
                TraceLogger.w(TAG, "prefetch lookup failed: $t")
                emptyList()
            }
            try {
                onProgress?.invoke(targets.size, targets.size) // all icons already local
            } catch (t: Throwable) {
                TraceLogger.w(TAG, "prefetch progress callback failed: $t")
            }
            try {
                onDone?.invoke(targets.size)
            } catch (t: Throwable) {
                TraceLogger.w(TAG, "prefetch done callback failed: $t")
            }
        }.start()
    }

    const val FEEDBACK_URL = "https://github.com/pzcn/Perfect-Icons-Completion-Project/issues/new?template=icon_request.yml"
}
