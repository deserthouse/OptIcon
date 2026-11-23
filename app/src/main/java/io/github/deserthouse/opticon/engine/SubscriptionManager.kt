package io.github.deserthouse.opticon.engine

import android.content.Context
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SubscriptionManager - icon rule sync from user-selected source
 *
 * Single-source sync with validation. User selects source in Settings.
 *
 * 修复批之后 [sync] 是**同步阻塞**的，调用方负责线程。此前连通性探测是异步回调，
 * 函数返回后真正的工作还在网络线程上跑——进程级互斥罩不住整个过程。
 */
object SubscriptionManager {

    private const val TAG = "OptIcon/Subscription"

    private const val RULES_FILE = "icon_rules.json"
    private const val HASH_FILE = "icon_rules.hash"

    data class SyncResult(
        val success: Boolean,
        val iconCount: Int = 0,
        val wasChanged: Boolean = false,
        val errorMessage: String? = null,
        /** 上游撤销的包（规则库快照同步）。调用方据此清理别的读取通道上的图标（修复批 X-04）。 */
        val revokedPackages: List<String> = emptyList(),
        /** 被进程级同步互斥拒绝（已有同步在进行）。不是失败，界面不以错误色显示。 */
        val busy: Boolean = false
    )

    /** Pre-V3 url-prefix heuristic, kept ONLY as the fallback for legacy
     *  callers that pass neither sourceFormat nor a resolvable source id. */
    private fun legacyRoute(url: String): SourceFormat =
        if (url.startsWith(AnipSync.BASE)) SourceFormat.ANIP_BUNDLE else SourceFormat.LEGACY_JSON

    /** 同步实际会走的格式：显式格式 → 源上登记的格式 → 旧的地址前缀启发式。
     *  同步后的收尾（旧版规则文件要不要导入）必须用同一个判断。 */
    fun resolveFormat(sourceFormat: SourceFormat?, url: String): SourceFormat =
        sourceFormat
            ?: PreferenceManager.getSourceFormat(PreferenceManager.getActiveAniaSourceId())
            ?: legacyRoute(url)

    /**
     * 阻塞直到同步结束。[onResult] 在调用线程上回调。
     *
     * 进行期间持有 [SyncGate]（修复批 X-22）：已有同步在跑时立即返回 busy，不排队——
     * 两次同步并发会互删对方的暂存目录。
     */
    fun sync(
        context: Context,
        sourceUrl: String? = null,
        sourceFormat: SourceFormat? = null,
        onProgress: ((String) -> Unit)? = null,
        onResult: (SyncResult) -> Unit
    ) {
        if (!SyncGate.tryAcquire()) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_busy), busy = true))
            return
        }
        try {
            syncHoldingGate(context, sourceUrl, sourceFormat, onProgress, onResult)
        } finally {
            SyncGate.release()
        }
    }

    private fun syncHoldingGate(
        context: Context,
        sourceUrl: String?,
        sourceFormat: SourceFormat?,
        onProgress: ((String) -> Unit)?,
        onResult: (SyncResult) -> Unit
    ) {
        val activeId = PreferenceManager.getActiveAniaSourceId()
        val url = sourceUrl ?: PreferenceManager.getSourceUrl(activeId)
        if (url == null) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_no_source)))
            return
        }
        val format = resolveFormat(sourceFormat, url)
        if (format == SourceFormat.UNSPECIFIED) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_format_unspecified)))
            return
        }

        onProgress?.invoke(context.getString(R.string.sync_preparing))
        if (!NetworkExecutor.checkConnectivitySync()) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_network_unavailable)))
            return
        }

        // ANIP source (manifest + per-app PNG) — resource-sync, no base64 JSON
        if (format == SourceFormat.ANIP_BUNDLE) {
            onProgress?.invoke(context.getString(R.string.sync_fetching_manifests))
            val outcome = AnipSync.syncDetailed(
                File(context.filesDir, IconLibEngine.CACHE_DIR_ACCESSIBLE),
                onProgress,
                context,
                // 修复批 X-17：自定义「ANIP 资源包」地址从此真的被使用
                AnipSync.archiveUrlFor(url)
            )
            if (outcome.count <= 0) {
                onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_invalid_data)))
                return
            }
            File(context.filesDir, HASH_FILE).writeText("${outcome.count}", Charsets.UTF_8)
            onResult(
                SyncResult(
                    success = true,
                    iconCount = outcome.count,
                    wasChanged = true,
                    revokedPackages = outcome.revokedPackages
                )
            )
            return
        }

        if (format == SourceFormat.PICP_ZIPBALL) {
            // A PICP payload has no place in the ANIA sync flow — the V3
            // list split should make this unreachable; fail honestly if a
            // stale pointer ever lands here.
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_invalid_data)))
            return
        }

        onProgress?.invoke(context.getString(R.string.sync_downloading))
        val json = NetworkExecutor.fetchStringSync(url) { downloaded, total ->
            val kb = downloaded / 1024
            val text = if (total > 0) "$kb / ${total / 1024} KB (${downloaded * 100 / total} %)" else "$kb KB"
            onProgress?.invoke(context.getString(R.string.sync_downloading_progress, text))
        }

        if (json == null) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_download_failed)))
            return
        }

        val iconCount = validateAndCountIcons(json)
        if (iconCount <= 0) {
            onResult(SyncResult(false, errorMessage = context.getString(R.string.sync_invalid_data)))
            return
        }

        if (!isDataChanged(context, json)) {
            onResult(SyncResult(true, iconCount = iconCount, wasChanged = false))
            return
        }

        onProgress?.invoke(context.getString(R.string.sync_saving, iconCount))
        saveRules(context, json, iconCount)
        onResult(SyncResult(true, iconCount = iconCount, wasChanged = true))
    }

    private fun validateAndCountIcons(json: String): Int {
        return try {
            if (json.trimStart().startsWith("<")) -1
            else {
                val arr = if (json.trimStart().startsWith("[")) JSONArray(json)
                else JSONObject(json).optJSONArray("apps") ?: JSONArray()
                var count = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.has("packageName") && obj.has("iconBitmap")) count++
                }
                count
            }
        } catch (e: Exception) { -1 }
    }

    private fun isDataChanged(context: Context, newJson: String): Boolean {
        val file = File(context.filesDir, RULES_FILE)
        if (!file.exists()) return true
        return file.readText() != newJson
    }

    private fun saveRules(context: Context, json: String, iconCount: Int) {
        val file = File(context.filesDir, RULES_FILE)
        val tmp = File(context.filesDir, "$RULES_FILE.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
        }
        try { file.setReadable(true, false) } catch (_: Exception) {}
        File(context.filesDir, HASH_FILE).writeText("$iconCount", Charsets.UTF_8)
        TraceLogger.i(TAG, "Saved $iconCount icon rules (${file.length()} bytes)")
    }

    fun hasRules(context: Context): Boolean =
        File(context.filesDir, RULES_FILE).let { it.exists() && it.length() > 100 }

    fun getIconCount(context: Context): Int =
        try { File(context.filesDir, HASH_FILE).readText().toIntOrNull() ?: 0 } catch (_: Exception) { 0 }

    fun readRulesJson(context: Context): String? =
        File(context.filesDir, RULES_FILE).takeIf { it.exists() }?.readText(Charsets.UTF_8)
}
