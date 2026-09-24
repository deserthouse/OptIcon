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
        private val ROOT_CACHE_MS = 5 * 60 * 1000L

        @Volatile var rootCache: Pair<Long, Boolean>? = null

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

    private val _lsposedActive = MutableStateFlow<Boolean?>(null)
    val lsposedActive: StateFlow<Boolean?> = _lsposedActive.asStateFlow()

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    private val _rechecking = MutableStateFlow(false)
    val rechecking: StateFlow<Boolean> = _rechecking.asStateFlow()

    init {
        // Run slow checks off main thread (H2 fix)
        viewModelScope.launch { recheckStatuses() }
    }

    /** 手动重检: LSPosed 活性 + Root 可用性 (状态卡刷新按钮)。
     *  Root 探测每次进设置都会弹系统的 su 授权/拒绝 toast（Magisk 行为），
     *  自动路径走 5 分钟缓存，只有手动刷新才强制执行 su。 */
    fun recheckStatuses(force: Boolean = false) {
        if (_rechecking.value) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            _rechecking.value = true
            val lsposed = withContext(Dispatchers.IO) { checkLsposed(app) }
            _lsposedActive.value = lsposed
            val cached = rootCache
            val root = if (!force && cached != null &&
                System.currentTimeMillis() - cached.first < ROOT_CACHE_MS
            ) cached.second
            else withContext(Dispatchers.IO) {
                checkRoot().also { rootCache = System.currentTimeMillis() to it }
            }
            _rootAvailable.value = root
            _rechecking.value = false
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

    private val _rambleExtraShown = MutableStateFlow(PreferenceManager.isRambleExtraShown())
    val rambleExtraShown: StateFlow<Boolean> = _rambleExtraShown.asStateFlow()

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

    private val _shadeAppIconMode = MutableStateFlow(PreferenceManager.getShadeAppIconMode())
    val shadeAppIconMode: StateFlow<String> = _shadeAppIconMode.asStateFlow()

    fun setShadeAppIconMode(mode: String) {
        PreferenceManager.setShadeAppIconMode(getApplication(), mode)
        _shadeAppIconMode.value = mode
    }

    // ━━ #13 色彩策略矩阵：全局强制单色（默认关，写共享文件供 hook 读取） ━━
    private val _forceMono = MutableStateFlow(
        io.github.deserthouse.opticon.engine.SharedIconStore.readColorMode() == "force_mono"
    )
    val forceMono: StateFlow<Boolean> = _forceMono.asStateFlow()

    /** True when the policy file exists but could not be read (owner/EACCES):
     *  the switch then shows OFF without knowing the truth — surface that. */
    private val _forceMonoUnknown = MutableStateFlow(
        io.github.deserthouse.opticon.engine.SharedIconStore.readColorMode() == null
    )
    val forceMonoUnknown: StateFlow<Boolean> = _forceMonoUnknown.asStateFlow()

    fun setForceMono(enabled: Boolean) {
        val written = io.github.deserthouse.opticon.engine.SharedIconStore.writeColorMode(
            getApplication(), if (enabled) "force_mono" else "off"
        )
        if (written == null) {
            // Optimistic flip would desync UI from storage — revert and tell.
            _toastEvent.value = getApplication<Application>().getString(
                io.github.deserthouse.opticon.R.string.sync_write_failed
            )
            return
        }
        _forceMono.value = enabled
        _forceMonoUnknown.value = false  // a successful write is self-verifying
    }

    fun setVerboseLogging(enabled: Boolean) {
        PreferenceManager.setVerboseLogging(enabled)
        TraceLogger.setLevel(if (enabled) TraceLogger.DEBUG else TraceLogger.INFO)
        _verboseLogging.value = enabled
    }

    fun setMasterEnabled(enabled: Boolean) {
        val written = PreferenceManager.setModuleEnabled(enabled)
        if (!written) {
            // Optimistic flip would show OFF while the hook keeps replacing —
            // roll back and say so (same contract as setForceMono).
            _toastEvent.value = getApplication<Application>().getString(
                io.github.deserthouse.opticon.R.string.sync_write_failed
            )
            return
        }
        _masterEnabled.value = enabled
    }


    // ━━━ ANIA sync ━━━

    fun syncAnia() {
        if (_aniaSyncing.value) return
        val ctx = getApplication<Application>().applicationContext
        _aniaSyncing.value = true
        _aniaSyncStatus.value = ctx.getString(io.github.deserthouse.opticon.R.string.sync_preparing)
        val sourceUrl = PreferenceManager.getSourceUrl(_activeAniaSource.value)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SubscriptionManager.sync(ctx, sourceUrl,
                    onProgress = { _aniaSyncStatus.value = it },
                    onResult = { r ->
                        _aniaSyncStatus.value = when {
                            !r.success -> r.errorMessage ?: ctx.getString(io.github.deserthouse.opticon.R.string.sync_failed)
                            r.wasChanged -> ctx.getString(io.github.deserthouse.opticon.R.string.sync_anip_done, r.iconCount)
                            else -> ctx.getString(io.github.deserthouse.opticon.R.string.sync_anip_uptodate, r.iconCount)
                        }
                        if (r.success && r.wasChanged) {
                            _aniaIconCount.value = r.iconCount
                            // ANIP sync writes fankes_cache + meta.json directly;
                            // legacy base64 sources still go through ingestJson.
                            if (SubscriptionManager.readRulesJson(ctx) != null) {
                                IconLibEngine.ingestJson(ctx, SubscriptionManager.readRulesJson(ctx)!!)
                            } else {
                                IconLibEngine.initializeFromFiles(ctx, emptyList())
                            }
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
        val ctx = getApplication<Application>().applicationContext
        _picpSyncing.value = true
        _picpSyncStatus.value = ctx.getString(io.github.deserthouse.opticon.R.string.sync_picp_index)
        val sourceUrl = PreferenceManager.getSourceUrl(_activePicpSource.value)
        if (sourceUrl == null) {
            _picpSyncStatus.value = ctx.getString(io.github.deserthouse.opticon.R.string.sync_no_source)
            _picpSyncing.value = false
            return
        }
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
                            _picpSyncStatus.value = ctx.getString(io.github.deserthouse.opticon.R.string.sync_picp_icons, done, total)
                        },
                        onDone = { downloaded ->
                            _picpSyncStatus.value = ctx.getString(io.github.deserthouse.opticon.R.string.sync_picp_done, count, downloaded)
                            _picpSyncing.value = false
                        }
                    )
                } else {
                    _picpSyncStatus.value = err ?: ctx.getString(io.github.deserthouse.opticon.R.string.sync_picp_failed)
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

    /** 解锁后直接展开/收起（含 UI 上的 chevron 按钮），不再走彩蛋计数流程 */
    fun toggleEasterEgg() {
        _easterEggExpanded.value = !_easterEggExpanded.value
    }

    fun onVersionTapped() {
        // 已解锁：直接切换可见性，无 emoji 序列
        if (_emojiUnlocked.value) {
            toggleEasterEgg()
            return
        }
        versionTapCount++
        if (versionTapCount < EMOJI_UNLOCK_TAPS) {
            _toastEvent.value = "🐾"
            return
        }
        // 第 7 击：解锁并常驻（持久化），展开彩蛋区
        PreferenceManager.setEmojiUnlocked(true)
        _emojiUnlocked.value = true
        _easterEggExpanded.value = true
        _toastEvent.value = "🐺"
        versionTapCount = 0
    }

    /** 碎碎念二层彩蛋: 🍆×6 → 💦+1% 行常驻; 触发后再点 → 「一滴也没有了」 */
    fun onRambleTapped() {
        if (_rambleExtraShown.value) {
            _toastEvent.value = getApplication<Application>().getString(io.github.deserthouse.opticon.R.string.easter_egg_dry)
            return
        }
        rambleTapCount++
        if (rambleTapCount < EMOJI_UNLOCK_TAPS) {
            _toastEvent.value = "🍆"
            return
        }
        PreferenceManager.setRambleExtraShown(true)
        _rambleExtraShown.value = true
        _toastEvent.value = "💦"
        rambleTapCount = 0
    }

    private var rambleTapCount = 0
}
