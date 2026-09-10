package io.github.deserthouse.opticon.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.engine.SubscriptionManager
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptIcon/SettingsVM"
        private const val EMOJI_UNLOCK_TAPS = 7
        const val AI_UNLOCK_TOAST = "已解锁文本/Emoji 转图标与云端 AI 图标重绘！"

        /** 检测 LSPosed 是否已激活作用域
         *
         *  原理: Hook 安装时记录 SystemUI 的 PID 到 ContentProvider。
         *  UI 读取 PID 后检查该进程是否仍然存活 — 如果存活, 说明当前 SystemUI 实例有 Hook。
         *  如果用户取消勾选作用域后重启 SystemUI, 新 PID 与记录不匹配, 判定为未激活。
         */
        fun checkLsposed(context: android.content.Context): Boolean {
            return try {
                val uri = android.net.Uri.parse("content://io.github.deserthouse.opticon.icons/__flag__")
                val result = context.contentResolver.call(uri, "get_flag", null, null) ?: return false
                val hookPid = result.getInt("hook_pid", 0)
                if (hookPid <= 0) return false
                // Liveness = flag freshness: the hook re-reports every 5 min
                // (heartbeat); SystemUI crash/deactivation stops the heartbeat
                // and the flag goes stale. Avoids /proc cross-process reads
                // (unreliable under SELinux/hidepid).
                val flagFile = java.io.File(context.filesDir, "hook_installed")
                val age = System.currentTimeMillis() - flagFile.lastModified()
                age < HEARTBEAT_TIMEOUT_MS
            } catch (e: Exception) {
                false
            }
        }

        private val HEARTBEAT_TIMEOUT_MS = 10 * 60 * 1000L

        /** Root 检测 — InstallerX Revived 风格: 实际执行 su -c 命令验证 (3s 超时) */
        fun checkRoot(): Boolean {
            val paths = "/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk/bin"
            return try {
                val proc = ProcessBuilder("su", "-c", "export PATH=\$PATH:$paths && echo ok")
                    .redirectErrorStream(true)
                    .start()
                val result = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                if (!exited) { proc.destroy(); return false }
                result.contains("ok")
            } catch (e: Exception) { false }
        }
    }

    private val _lsposedActive = MutableStateFlow(false)
    val lsposedActive: StateFlow<Boolean> = _lsposedActive.asStateFlow()

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    init {
        // Run slow checks off main thread (H2 fix)
        viewModelScope.launch {
            val app = getApplication<Application>()
            _lsposedActive.value = withContext(Dispatchers.IO) { checkLsposed(app) }
            _rootAvailable.value = withContext(Dispatchers.IO) { checkRoot() }
        }
    }

    private val _verboseLogging = MutableStateFlow(PreferenceManager.isVerboseLogging())
    val verboseLogging: StateFlow<Boolean> = _verboseLogging.asStateFlow()

    private val _masterEnabled = MutableStateFlow(PreferenceManager.isModuleEnabled())
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _emojiUnlocked = MutableStateFlow(PreferenceManager.isEmojiUnlocked())
    val emojiUnlocked: StateFlow<Boolean> = _emojiUnlocked.asStateFlow()

    // ━━━ ANIA subscription ━━━
    private val _aniaSyncStatus = MutableStateFlow<String?>(null)
    val aniaSyncStatus: StateFlow<String?> = _aniaSyncStatus.asStateFlow()
    private val _aniaIconCount = MutableStateFlow(SubscriptionManager.getIconCount(getApplication()))
    val aniaIconCount: StateFlow<Int> = _aniaIconCount.asStateFlow()
    private val _aniaSyncing = MutableStateFlow(false)
    val aniaSyncing: StateFlow<Boolean> = _aniaSyncing.asStateFlow()
    private val _aniaSources = MutableStateFlow(PreferenceManager.getAniaSources())
    val aniaSources: StateFlow<List<PreferenceManager.SubscriptionSource>> = _aniaSources.asStateFlow()
    private val _activeAniaSource = MutableStateFlow(PreferenceManager.getActiveAniaSourceId())
    val activeAniaSource: StateFlow<String> = _activeAniaSource.asStateFlow()

    // ━━━ PICP subscription ━━━
    private val _picpSyncStatus = MutableStateFlow<String?>(null)
    val picpSyncStatus: StateFlow<String?> = _picpSyncStatus.asStateFlow()
    private val _picpCount = MutableStateFlow(PicpEngine.getIndexedCount(getApplication()))
    val picpCount: StateFlow<Int> = _picpCount.asStateFlow()
    private val _picpSyncing = MutableStateFlow(false)
    val picpSyncing: StateFlow<Boolean> = _picpSyncing.asStateFlow()
    private val _picpSources = MutableStateFlow(PreferenceManager.getPicpSources())
    val picpSources: StateFlow<List<PreferenceManager.SubscriptionSource>> = _picpSources.asStateFlow()
    private val _activePicpSource = MutableStateFlow(PreferenceManager.getActivePicpSourceId())
    val activePicpSource: StateFlow<String> = _activePicpSource.asStateFlow()

    // ━━━ Shared source management ━━━
    private val _showAddSource = MutableStateFlow(false)
    val showAddSource: StateFlow<Boolean> = _showAddSource.asStateFlow()
    private val _showEditSource = MutableStateFlow<PreferenceManager.SubscriptionSource?>(null)
    val showEditSource: StateFlow<PreferenceManager.SubscriptionSource?> = _showEditSource.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent: StateFlow<String?> = _toastEvent.asStateFlow()

    private val _easterEggExpanded = MutableStateFlow(false)
    val easterEggExpanded: StateFlow<Boolean> = _easterEggExpanded.asStateFlow()

    private var versionTapCount = 0

    fun setVerboseLogging(enabled: Boolean) {
        PreferenceManager.setVerboseLogging(enabled)
        TraceLogger.setLevel(if (enabled) TraceLogger.DEBUG else TraceLogger.INFO)
        _verboseLogging.value = enabled
    }

    fun setMasterEnabled(enabled: Boolean) {
        PreferenceManager.setModuleEnabled(enabled)
        _masterEnabled.value = enabled
    }


    // ━━━ ANIA sync ━━━

    fun syncAnia() {
        if (_aniaSyncing.value) return
        _aniaSyncing.value = true
        _aniaSyncStatus.value = "Preparing..."
        val sourceUrl = PreferenceManager.getSourceUrl(_activeAniaSource.value)
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            withContext(Dispatchers.IO) {
                SubscriptionManager.sync(ctx, sourceUrl,
                    onProgress = { _aniaSyncStatus.value = it },
                    onResult = { r ->
                        _aniaSyncStatus.value = when {
                            !r.success -> r.errorMessage ?: "Sync failed"
                            r.wasChanged -> "ANIA synced ${r.iconCount} icons - restart SystemUI"
                            else -> "ANIA up to date (${r.iconCount} icons)"
                        }
                        if (r.success && r.wasChanged) {
                            _aniaIconCount.value = r.iconCount
                            SubscriptionManager.readRulesJson(ctx)?.let { IconLibEngine.ingestJson(ctx, it) }
                            // Publish all fankes_cache icons to the shared Downloads
                            // dir so the SystemUI hook can read them (SELinux-safe)
                            io.github.deserthouse.opticon.engine.SharedIconStore
                                .mirrorAllFromDir(ctx, java.io.File(ctx.filesDir, "fankes_cache"))
                        }
                        _aniaSyncing.value = false
                    }
                )
            }
        }
    }

    // ━━━ PICP sync ━━━

    fun syncPicp() {
        if (_picpSyncing.value) return
        _picpSyncing.value = true
        _picpSyncStatus.value = "Downloading PICP index..."
        val sourceUrl = PreferenceManager.getSourceUrl(_activePicpSource.value)
        if (sourceUrl == null) {
            _picpSyncStatus.value = "No PICP source selected"
            _picpSyncing.value = false
            return
        }
        val ctx = getApplication<Application>().applicationContext
        PicpEngine.syncIndex(ctx, sourceUrl,
            onProgress = { _picpSyncStatus.value = it },
            onResult = { ok, count, err ->
                if (ok) {
                    _picpCount.value = count
                    // After index sync, prefetch icons for installed apps
                    val installed = ctx.packageManager.getInstalledApplications(0)
                        .map { it.packageName.toString() }
                        .filter { it != ctx.packageName }
                    PicpEngine.prefetchForInstalled(ctx, installed,
                        onProgress = { done, total ->
                            _picpSyncStatus.value = "Downloading PICP icons: $done/$total"
                        },
                        onDone = { downloaded ->
                            _picpSyncStatus.value = "PICP synced $count packages, $downloaded icons cached"
                            _picpSyncing.value = false
                        }
                    )
                } else {
                    _picpSyncStatus.value = err ?: "PICP sync failed"
                    _picpSyncing.value = false
                }
            }
        )
    }

    // ━━━ Source management ━━━

    fun setActiveAniaSource(id: String) { PreferenceManager.setActiveAniaSourceId(id); _activeAniaSource.value = id }
    fun setActivePicpSource(id: String) { PreferenceManager.setActivePicpSourceId(id); _activePicpSource.value = id }

    fun startAddSource() { _showAddSource.value = true }
    fun dismissAddSource() { _showAddSource.value = false }
    fun startEditSource(source: PreferenceManager.SubscriptionSource) { _showEditSource.value = source }
    fun dismissEditSource() { _showEditSource.value = null }

    fun addSource(name: String, url: String, description: String) {
        PreferenceManager.addCustomSource(name, url, description)
        refreshSources()
    }

    fun updateSource(id: String, name: String, url: String, description: String) {
        PreferenceManager.updateCustomSource(id, name, url, description)
        refreshSources()
    }

    fun removeSource(id: String) {
        PreferenceManager.removeCustomSource(id)
        refreshSources()
        _activeAniaSource.value = PreferenceManager.getActiveAniaSourceId()
        _activePicpSource.value = PreferenceManager.getActivePicpSourceId()
    }

    fun restoreDefaultAnia() {
        PreferenceManager.restoreBuiltinSource("ania_raw")
        refreshSources()
    }

    fun restoreDefaultPicp() {
        PreferenceManager.restoreBuiltinSource("picp_github")
        refreshSources()
    }

    private fun refreshSources() {
        _aniaSources.value = PreferenceManager.getAniaSources()
        _picpSources.value = PreferenceManager.getPicpSources()
        _showAddSource.value = false
        _showEditSource.value = null
    }

    fun dismissInfo() { _infoMessage.value = null }
    fun consumeToast() { _toastEvent.value = null }

    fun onVersionTapped() {
        versionTapCount++
        if (versionTapCount < EMOJI_UNLOCK_TAPS) {
            _toastEvent.value = "🐾"
            return
        }
        _easterEggExpanded.value = !_easterEggExpanded.value
        _toastEvent.value = if (_easterEggExpanded.value) "🐺" else "🌙"
        versionTapCount = 0
    }
}
