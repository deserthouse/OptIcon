package io.github.deserthouse.opticon.engine

import android.content.Context
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SubscriptionManager - icon rule sync from user-selected source 
 *
 * Single-source sync with validation. User selects source in Settings.
 */
object SubscriptionManager {

    private const val TAG = "OptIcon/Subscription"

    private const val RULES_FILE = "icon_rules.json"
    private const val HASH_FILE = "icon_rules.hash"

    data class SyncResult(
        val success: Boolean,
        val iconCount: Int = 0,
        val wasChanged: Boolean = false,
        val errorMessage: String? = null
    )

    fun sync(
        context: Context,
        sourceUrl: String? = null,
        onProgress: ((String) -> Unit)? = null,
        onResult: (SyncResult) -> Unit
    ) {
        val url = sourceUrl
            ?: io.github.deserthouse.opticon.util.PreferenceManager.getSourceUrl(
                io.github.deserthouse.opticon.util.PreferenceManager.getActiveAniaSourceId()
            )
        if (url == null) {
            onResult(SyncResult(false, errorMessage = context.getString(io.github.deserthouse.opticon.R.string.sync_no_source)))
            return
        }

        onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_preparing))
        NetworkExecutor.checkConnectivity { networkOk ->
            if (!networkOk) {
                onResult(SyncResult(false, errorMessage = context.getString(io.github.deserthouse.opticon.R.string.sync_network_unavailable)))
                return@checkConnectivity
            }

            // ANIP source (manifest + per-app PNG) — resource-sync, no base64 JSON
            if (url.startsWith(AnipSync.BASE)) {
                onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_fetching_manifests))
                val count = AnipSync.syncTo(
                    File(context.filesDir, IconLibEngine.CACHE_DIR_ACCESSIBLE),
                    onProgress,
                    context
                )
                if (count <= 0) {
                    onResult(SyncResult(false, errorMessage = context.getString(io.github.deserthouse.opticon.R.string.sync_invalid_data)))
                    return@checkConnectivity
                }
                File(context.filesDir, HASH_FILE).writeText("$count", Charsets.UTF_8)
                onResult(SyncResult(true, iconCount = count, wasChanged = true))
                return@checkConnectivity
            }

            onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_downloading))
            val json = NetworkExecutor.fetchStringSync(url) { downloaded, total ->
                val kb = downloaded / 1024
                val text = if (total > 0) "$kb / ${total / 1024} KB (${downloaded * 100 / total} %)" else "$kb KB"
                onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_downloading_progress, text))
            }

            if (json == null) {
                onResult(SyncResult(false, errorMessage = context.getString(io.github.deserthouse.opticon.R.string.sync_download_failed)))
                return@checkConnectivity
            }

            val iconCount = validateAndCountIcons(json)
            if (iconCount <= 0) {
                onResult(SyncResult(false, errorMessage = context.getString(io.github.deserthouse.opticon.R.string.sync_invalid_data)))
                return@checkConnectivity
            }

            if (!isDataChanged(context, json)) {
                onResult(SyncResult(true, iconCount = iconCount, wasChanged = false))
                return@checkConnectivity
            }

            onProgress?.invoke(context.getString(io.github.deserthouse.opticon.R.string.sync_saving, iconCount))
            saveRules(context, json, iconCount)
            onResult(SyncResult(true, iconCount = iconCount, wasChanged = true))
        }
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
