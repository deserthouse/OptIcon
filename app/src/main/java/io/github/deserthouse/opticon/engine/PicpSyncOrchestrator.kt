package io.github.deserthouse.opticon.engine

import java.io.File

/**
 * PicpSyncOrchestrator — 应用图标库同步的编排（纯 JVM，不依赖 Android / OkHttp）。
 *
 *   下载（到临时文件）→ 归档落位 → 校验并解压进暂存目录 → 非空校验 → 目录切换
 *   → 清理 → 返回结果
 *
 * 修复批（M-08 / X-08 / X-16 / X-18 / X-19）之前这段流程内联在 PicpEngine 里：
 * 线程、资源文案、网络缠在一起，没有任何测试进得去；解压失败重试耗尽后下载的
 * 归档留在盘上；回调发出的瞬间暂存目录还没清。现在它是同步函数，清理全部在
 * 返回之前完成（finally 兜底），结果用类型表示，引擎外壳只负责线程与文案。
 *
 * 除 [Outcome.Success] 外，任何结果都不触碰现有图标库。
 */
internal object PicpSyncOrchestrator {

    /** 瞬时失败（网络、残缺归档）的重试次数——总共两次尝试。 */
    const val ATTEMPTS = 2
    const val RETRY_DELAY_MS = 1500L

    sealed class Outcome {
        data class Success(val count: Int) : Outcome()

        /** 归档完好，但里面没有任何可用图标。确定性的结果，不重试。 */
        object Empty : Outcome()

        /** 归档不可信：不是压缩包、被截断、条目数据与登记不符。[detail] 供日志与提示。 */
        data class Invalid(val detail: String) : Outcome()

        object DownloadFailed : Outcome()
        object SwitchFailed : Outcome()
        object Cancelled : Outcome()
        object TooLarge : Outcome()
    }

    /** 进度节点。文案由引擎外壳负责——这里不带资源 id。 */
    sealed class Stage {
        object Downloading : Stage()
        data class Bytes(val downloaded: Long, val total: Long) : Stage()
        object Retrying : Stage()
        object Extracting : Stage()
    }

    enum class FetchResult { OK, CANCELLED, TOO_LARGE, FAILED }

    /** 下载器的最小抽象：把归档写进 [dest]。编排层负责 [dest] 之外的落位与清理。 */
    fun interface Downloader {
        fun download(dest: File, onProgress: (downloaded: Long, total: Long) -> Unit): FetchResult
    }

    /**
     * 执行一次完整同步（阻塞直到结束）。调用方保证此时没有别的同步在跑
     * （[SyncGate]）。
     *
     * [pauseBetweenAttempts] 与 [switch] 是测试注入点；生产用默认值。
     */
    fun run(
        cacheDir: File,
        download: Downloader,
        cancelled: () -> Boolean,
        onStage: (Stage) -> Unit = {},
        pauseBetweenAttempts: (Long) -> Unit = { Thread.sleep(it) },
        switch: (File) -> Unit = { FileSnapshotSwitch.switchIn(it, PicpLibrary.ICONS_DIR) },
    ): Outcome {
        cacheDir.mkdirs()
        val staging = FileSnapshotSwitch.stagingOf(cacheDir, PicpLibrary.ICONS_DIR)
        val zip = File(cacheDir, PicpLibrary.ARCHIVE)
        val tmp = File(cacheDir, PicpLibrary.ARCHIVE_TMP)
        // 入口收敛：上次同步中断留下的中间态，连同没建完的暂存目录
        FileSnapshotSwitch.recoverIfInterrupted(cacheDir, PicpLibrary.ICONS_DIR)

        var lastInvalid: String? = null
        var downloadFailed = false
        try {
            for (attempt in 1..ATTEMPTS) {
                if (cancelled()) return Outcome.Cancelled
                if (attempt > 1) {
                    onStage(Stage.Retrying)
                    pauseBetweenAttempts(RETRY_DELAY_MS)
                    if (cancelled()) return Outcome.Cancelled
                }
                onStage(Stage.Downloading)
                when (download.download(tmp) { downloaded, total -> onStage(Stage.Bytes(downloaded, total)) }) {
                    FetchResult.OK -> Unit
                    FetchResult.CANCELLED -> return Outcome.Cancelled
                    FetchResult.TOO_LARGE -> return Outcome.TooLarge
                    FetchResult.FAILED -> {
                        downloadFailed = true
                        continue
                    }
                }
                // 下载器只负责把数据写全；落位（临时名 → 正式名）由编排层做
                if (!settle(tmp, zip)) {
                    downloadFailed = true
                    continue
                }

                onStage(Stage.Extracting)
                val count = try {
                    PicpZipScanner.extractToStaging(zip, staging, cancelled)
                } catch (e: PicpZipScanner.CancelledException) {
                    return Outcome.Cancelled
                } catch (e: PicpZipScanner.InvalidArchiveException) {
                    // 代理返回的错误页、被截断的下载……重试一次，两次都坏就如实报「无效」
                    lastInvalid = e.detail
                    continue
                }
                zip.delete() // 归档已用完

                if (count == 0) return Outcome.Empty

                try {
                    switch(cacheDir)
                } catch (e: Exception) {
                    // switchIn 失败时旧版已放回原位，或停在可由恢复收敛的中间态；
                    // 这里再收一次，尽量不让「旧版放不回去」留到下一次读取。
                    FileSnapshotSwitch.recoverIfInterrupted(cacheDir, PicpLibrary.ICONS_DIR)
                    return Outcome.SwitchFailed
                }
                PicpLibrary.discardLegacyIndex(cacheDir)
                return Outcome.Success(count)
            }
            val invalid = lastInvalid
            if (invalid != null) return Outcome.Invalid(invalid)
            return if (downloadFailed) Outcome.DownloadFailed else Outcome.Invalid("no archive")
        } finally {
            // 清理先于返回（X-08）：调用方拿到结果时，盘上已无暂存目录、无归档
            staging.deleteRecursively()
            zip.delete()
            tmp.delete()
        }
    }

    /** 下载的临时文件换到正式名；目标先清掉（Windows 上 renameTo 不能覆盖已存在目标）。 */
    private fun settle(tmp: File, zip: File): Boolean {
        if (!tmp.isFile || tmp.length() == 0L) return false
        zip.delete()
        return tmp.renameTo(zip)
    }
}
