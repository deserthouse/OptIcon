package io.github.deserthouse.opticon.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.opticon.data.AppIconConfigRepository
import io.github.deserthouse.opticon.engine.HitSource
import io.github.deserthouse.opticon.engine.IconEngine
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.engine.IconNormalizer
import io.github.deserthouse.opticon.engine.IconPackEngine
import io.github.deserthouse.opticon.engine.IconRedrawEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.engine.IconTint
import io.github.deserthouse.opticon.engine.MaterialIconRenderer
import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AppDetailState
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.state.StatusBarMode
import io.github.deserthouse.opticon.util.AppEntry
import io.github.deserthouse.opticon.util.AppInfoProvider
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AppDetailViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptIcon/AppDetailVM"
        private const val DEBOUNCE_MS = 300L
        private const val BAKED_DIR = "baked"
        private const val OUTPUT_SIZE = 96
        private const val PERFECT_ICONS_BASE =
            "https://raw.githubusercontent.com/pzcn/Perfect-Icons-Completion-Project/main/app/src/main/res/drawable-nodpi/"
    }

    private val _state = MutableStateFlow(AppDetailState())
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    /** A05（F2-2）：草稿版本号——每次编辑递增。保存完成时仅当快照版本仍为
     *  最新才清 dirty，保存期间的新编辑保持未保存状态。 */
    private val draftRevision = java.util.concurrent.atomic.AtomicInteger(0)
    private fun markDirty() {
        draftRevision.incrementAndGet()
        _isDirty.value = true
    }

    /** M4：当前 algo 来源图的输入分类（换图时自动分类+参数重置） */
    private val _algoInputClass = MutableStateFlow<IconRedrawEngine.InputClass?>(null)
    val algoInputClass: StateFlow<IconRedrawEngine.InputClass?> = _algoInputClass.asStateFlow()

    /** One-shot message when a save was altered by validation (e.g. kept off) */
    private val _saveFeedback = MutableStateFlow<String?>(null)
    val saveFeedback: StateFlow<String?> = _saveFeedback.asStateFlow()
    fun consumeSaveFeedback() { _saveFeedback.value = null }
    val state: StateFlow<AppDetailState> = _state.asStateFlow()

    private var redrawDebounceJob: Job? = null
    private var appBitmap: Bitmap? = null

    fun loadApp(packageName: String) {
        // 同步读取开关状态，避免异步加载期间的闪烁
        val initialEnabled = PreferenceManager.isMethodEnabled(packageName)
        _state.update { it.copy(packageName = packageName, methodEnabled = initialEnabled) }
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val app = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(packageName, 0)
                    AppEntry(
                        packageName = packageName,
                        appName = pm.getApplicationLabel(ai).toString(),
                        icon = pm.getApplicationIcon(ai),
                        isSystemApp = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (e: Exception) { null }
            } ?: run { return@launch }
            // H9 fix: clear previewBitmap before recycling appBitmap to prevent
            // Compose from drawing a recycled bitmap
            _state.update { it.copy(previewBitmap = null) }
            appBitmap?.recycle()
            appBitmap = drawableToBitmap(app.icon, OUTPUT_SIZE)

            val packs = withContext(Dispatchers.IO) { IconPackEngine.listInstalledIconPacks(context) }
            // A06（F1-2）：详情页快照经仓库单一出口——加载与保存消费同一表示，
            // legacy method_ 键在此处自动迁移。
            val saved = withContext(Dispatchers.IO) { AppIconConfigRepository.load(packageName) }

            _state.update {
                it.copy(
                    appName = app.appName, isSystemApp = app.isSystemApp,
                    strategy = saved.strategy,
                    customIconPath = saved.customIconPath,
                    materialIconName = saved.materialIconName,
                    emojiText = saved.emojiText,
                    emojiUnlocked = PreferenceManager.isEmojiUnlocked(),
                    redrawParams = saved.params,
                    iconPacks = packs,
                    assetSubStrategy = saved.assetSubStrategy ?: AssetSubStrategy.ADAPTIVE_DECOMPOSE,
                    algoSource = saved.algoSource ?: AlgoSource.SELF,
                    selectedIconPack = saved.iconPackPkg,
                    selectedPackIconDrawable = saved.packDrawable,
                    colorOverride = io.github.deserthouse.opticon.engine.SharedIconStore.readColorOverride(packageName),
                    hasOriginalCaptured = io.github.deserthouse.opticon.engine.SharedIconStore.originalIconFile(packageName).exists()
                )
            }

            // Auto-download PICP icon if available — no user tap required
            if (PicpEngine.hasIcon(packageName, context)) {
                withContext(Dispatchers.IO) {
                    val bmp = PicpEngine.downloadIcon(getApplication(), packageName)
                    if (bmp != null) {
                        _state.update { it.copy(perfectIconsBitmap = bmp, previewBitmap = bmp, hitSource = HitSource.PICP) }
                    }
                }
            }

            decomposeAdaptiveIcon()
            computePreviewNow()
        }
    }

    fun setStrategy(strategy: IconStrategy) {
        markDirty()
        _state.update { it.copy(strategy = strategy, previewBitmap = null, hitSource = HitSource.OTHER) }
        debouncePreview()
    }

    /** B2（ME-03）：全屏素材选择器的原子回填——取消不落任何字段，
     *  确认一次写全（子策略+图标包+drawable+PICP 草稿素材）后统一刷新预览。 */
    fun applyAssetSelection(
        sub: AssetSubStrategy,
        iconPack: String?,
        drawable: String?,
        picpBitmap: Bitmap? = null
    ) {
        markDirty()
        _state.update {
            val newPicp = picpBitmap ?: it.perfectIconsBitmap
            it.copy(
                assetSubStrategy = sub,
                selectedIconPack = iconPack,
                selectedPackIconDrawable = drawable,
                perfectIconsBitmap = newPicp,
                previewBitmap = if (sub == AssetSubStrategy.PERFECT_ICONS && newPicp != null) newPicp else it.previewBitmap,
                hitSource = if (sub == AssetSubStrategy.PERFECT_ICONS && newPicp != null) HitSource.PICP else it.hitSource
            )
        }
        if (sub == AssetSubStrategy.ADAPTIVE_DECOMPOSE) decomposeAdaptiveIcon()
        debouncePreview()
    }

    fun setAlgoSource(src: AlgoSource) {
        markDirty()
        _state.update { it.copy(algoSource = src) }
        // OI-13: classification belongs to the SOURCE IMAGE — switching to SELF
        // must re-classify the app's own icon, otherwise "恢复自动" either
        // no-ops (null class) or reuses the previous upload's class.
        if (src == AlgoSource.SELF) {
            val gen = algoClassGeneration.incrementAndGet()
            viewModelScope.launch {
                val pkg = _state.value.packageName
                val cls = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val d = try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    if (d == null) null
                    else {
                        val bmp = drawableToBitmap(d, 96)
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        val r = IconRedrawEngine.classifyInput(px, bmp.width)
                        bmp.recycle()
                        r
                    }
                }
                if (algoClassGeneration.get() != gen) return@launch   // 过期结果丢弃
                _algoInputClass.value = cls
                if (cls != null) {
                    _state.update { it.copy(redrawParams = autoParamsFor(cls)) }
                    debouncePreview()
                }
            }
        } else debouncePreview()
    }

    fun setMethodEnabled(enabled: Boolean) {
        markDirty()
        _state.update { it.copy(methodEnabled = enabled) }
        debouncePreview()
    }

    fun pullFromPerfectIcons() {
        _state.update { it.copy(perfectIconsLoading = true) }
        viewModelScope.launch {
            val pkg = _state.value.packageName
            try {
                val bitmap = withContext(Dispatchers.IO) { fetchPerfectIcon(pkg) }
                if (bitmap != null) {
                    val white = withContext(Dispatchers.IO) { IconTint.toWhite(bitmap) }
                    _state.update { it.copy(previewBitmap = white, hitSource = HitSource.PICP, perfectIconsLoading = false) }
                } else {
                    fallbackToLocalBuiltin()
                }
            } catch (_: Exception) {
                fallbackToLocalBuiltin()
            }
        }
    }

    fun downloadPicpIcon() {
        val pkg = _state.value.packageName.ifBlank { return }
        _state.update { it.copy(perfectIconsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = PicpEngine.downloadIcon(getApplication(), pkg)
            _state.update {
                if (bitmap != null) it.copy(perfectIconsBitmap = bitmap, previewBitmap = bitmap, hitSource = HitSource.PICP, perfectIconsLoading = false)
                else it.copy(perfectIconsLoading = false, hitSource = HitSource.PICP_FAILED)
            }
            if (bitmap != null) computePreviewNow()
        }
    }

    private suspend fun fetchPerfectIcon(pkg: String): Bitmap? {
        val name = pkg.replace('.', '_')
        val suffixes = listOf("$name.png", "${name}_icon.png", "ic_launcher_$name.png")
        for (suffix in suffixes) {
            val bytes = NetworkExecutor.fetchBytesSync("$PERFECT_ICONS_BASE$suffix")
            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) return bitmap
            }
        }
        return null
    }

    private suspend fun fallbackToLocalBuiltin() {
        val pkg = _state.value.packageName
        withContext(Dispatchers.IO) {
            val libIcon = IconLibEngine.lookup(pkg)
            if (libIcon != null) {
                _state.update { it.copy(previewBitmap = libIcon, hitSource = HitSource.ICONLIB, perfectIconsLoading = false) }
            } else {
                _state.update { it.copy(previewBitmap = null, hitSource = HitSource.NETWORK_FAIL, perfectIconsLoading = false) }
            }
        }
    }

    private fun decomposeAdaptiveIcon() {
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val pkg = _state.value.packageName
            val result = withContext(Dispatchers.IO) { IconEngine.decomposeAdaptiveIcon(ctx, pkg) }
            if (result != null) {
                _state.update { it.copy(adaptiveForeground = result.first, adaptiveBackground = result.second, isAdaptiveIcon = true) }
            } else {
                _state.update { it.copy(isAdaptiveIcon = false) }
            }
        }
    }

    /** OI-13 补强（架构报告 §5）：分类协程的 generation 守卫——快速切换
     *  来源/素材时，晚到的旧分类结果不得回填覆盖新选择。 */
    private val algoClassGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** A04（F2-1）：上传图片=一次原子切换来源——解码成功才同写
     *  customIconPath+LOCAL_FILE+分类/参数重置；解码失败保持旧选择并如实反馈。
     *  （旧实现只写路径不切来源，引擎仍烘焙应用自身图标。） */
    fun applyUploadedIcon(uri: String) {
        markDirty()
        val gen = algoClassGeneration.incrementAndGet()
        viewModelScope.launch {
            val decoded: Pair<IconRedrawEngine.InputClass?, Boolean> = withContext(Dispatchers.IO) {
                val bmp = decodeFileOrUri(getApplication(), uri)
                if (bmp == null) null to false
                else {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val r = IconRedrawEngine.classifyInput(px, bmp.width)
                    bmp.recycle()
                    r to true
                }
            }
            if (algoClassGeneration.get() != gen) return@launch // 过期结果丢弃
            val (classified, ok) = decoded
            if (!ok) {
                _saveFeedback.value = getApplication<Application>().getString(
                    io.github.deserthouse.opticon.R.string.upload_decode_failed
                )
                return@launch
            }
            _state.update { it.copy(customIconPath = uri, algoSource = AlgoSource.LOCAL_FILE) }
            _algoInputClass.value = classified
            if (classified != null) {
                _state.update { it.copy(redrawParams = autoParamsFor(classified)) }
            }
            debouncePreview()
        }
    }

    fun setCustomIconPath(path: String?) {
        markDirty()
        _state.update { it.copy(customIconPath = path) }
        val gen = algoClassGeneration.incrementAndGet()
        if (path == null) {
            _algoInputClass.value = null
            debouncePreview()
            return
        }
        // M4 自动先行：换图即分类+自动参数重置（用户裁决的四条件之一）
        viewModelScope.launch {
            val cls = withContext(Dispatchers.IO) {
                decodeFileOrUri(getApplication(), path)?.let { bmp ->
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val result = IconRedrawEngine.classifyInput(px, bmp.width)
                    bmp.recycle()
                    result
                }
            }
            if (algoClassGeneration.get() != gen) return@launch   // 过期结果丢弃
            _algoInputClass.value = cls
            if (cls != null) {
                _state.update { s -> s.copy(redrawParams = autoParamsFor(cls)) }
                debouncePreview()
            }
        }
    }

    /** 输入类 → 该类自动默认参数（M3 参数表；alpha 通路不消费 threshold，保持默认即可） */
    private fun autoParamsFor(cls: IconRedrawEngine.InputClass): RedrawParams = when (cls) {
        IconRedrawEngine.InputClass.TRANSPARENT_MARGIN -> RedrawParams()
        else -> RedrawParams()
    }

    /** 「恢复自动」：滑杆回到当前输入类的自动默认值 */
    fun restoreAutoParams() {
        markDirty()
        // OI-13: no classification (SELF default) still restores — fall back to
        // the class-agnostic defaults instead of silently doing nothing.
        val cls = _algoInputClass.value
        _state.update { it.copy(redrawParams = if (cls != null) autoParamsFor(cls) else RedrawParams()) }
        debouncePreview()
    }

    /** #13 每应用色彩覆盖：null=继承全局, "mono"=强制单色, "color"=保持原色。
     *  立即写共享目录（hook 侧 mtime 缓存即时感知），不随保存流程。 */
    fun setColorOverride(value: String?) {
        val ctx = getApplication<Application>()
        // A10（F2-4）：写入/删除结果决定 UI 显示值——失败保持旧值并如实反馈，
        // 不得出现"显示已切换但从未持久生效"。
        val ok = if (value == null) {
            io.github.deserthouse.opticon.engine.SharedIconStore.deleteColorOverride(ctx, _state.value.packageName)
        } else {
            io.github.deserthouse.opticon.engine.SharedIconStore.writeColorOverride(ctx, _state.value.packageName, value) != null
        }
        if (ok) {
            _state.update { it.copy(colorOverride = value) }
        } else {
            _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.color_override_write_failed)
        }
    }
    fun setEmojiText(text: String?) {
        markDirty()
        _state.update { it.copy(emojiText = text) }
        debouncePreview()
    }
    fun selectMaterialIcon(name: String) {
        markDirty()
        _state.update { it.copy(materialIconName = name) }
        debouncePreview()
    }

    fun setScale(v: Float) {
        markDirty()
        _state.update { it.copy(redrawParams = it.redrawParams.copy(scale = v.coerceIn(RedrawParams.SCALE_MIN, RedrawParams.SCALE_MAX))) }
        debouncePreview()
    }
    fun setOffsetX(v: Float) {
        markDirty()
        _state.update { it.copy(redrawParams = it.redrawParams.copy(offsetX = v.coerceIn(-RedrawParams.OFFSET_MAX, RedrawParams.OFFSET_MAX))) }
        debouncePreview()
    }
    fun setOffsetY(v: Float) {
        markDirty()
        _state.update { it.copy(redrawParams = it.redrawParams.copy(offsetY = v.coerceIn(-RedrawParams.OFFSET_MAX, RedrawParams.OFFSET_MAX))) }
        debouncePreview()
    }
    fun setThreshold(v: Int) {
        markDirty()
        _state.update { it.copy(redrawParams = it.redrawParams.copy(threshold = v.coerceIn(0, 255))) }
        debouncePreview()
    }
    fun setRadius(v: Float) {
        markDirty()
        _state.update { it.copy(redrawParams = it.redrawParams.copy(radius = v.coerceIn(0f, RedrawParams.RADIUS_MAX))) }
        debouncePreview()
    }

    fun showSheet() = _state.update { it.copy(sheetVisible = true) }
    fun hideSheet() = _state.update { it.copy(sheetVisible = false) }
    fun togglePreviewMode() { _state.update { it.copy(previewMode = if (it.previewMode == StatusBarMode.DARK) StatusBarMode.LIGHT else StatusBarMode.DARK) } }

    /** OI-03/OI-06: save lifecycle as observable state — no premature dirty
     *  clear, no silent delivery failure, no fire-and-forget navigation. */
    sealed interface SaveState {
        data object Idle : SaveState
        data object Saving : SaveState
        data object Saved : SaveState
        /** Saved and the caller asked to leave afterwards (unsaved-dialog path). */
        data object SavedAndExit : SaveState
        data object Failed : SaveState
    }

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun saveConfig(requestLeaveAfter: Boolean = false) {
        if (_saveState.value == SaveState.Saving) return
        val s = _state.value; val pkg = s.packageName
        val savedRevision = draftRevision.get()
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val feedbackBefore = _saveFeedback.value
            var ok = true

            if (s.methodEnabled) {
                // A01（F1-3）：任何已提交数据被改动前固定旧配置快照——失败回滚
                // 的对象是磁盘上的已提交值，不是本次草稿值。
                val old = withContext(Dispatchers.IO) { AppIconConfigRepository.load(pkg) }
                val newConfig = io.github.deserthouse.opticon.data.AppIconConfig(
                    pkg = pkg, enabled = true, strategy = s.strategy,
                    assetSubStrategy = s.assetSubStrategy, algoSource = s.algoSource,
                    iconPackPkg = s.selectedIconPack, packDrawable = s.selectedPackIconDrawable,
                    customIconPath = s.customIconPath, materialIconName = s.materialIconName,
                    emojiText = s.emojiText, params = s.redrawParams
                )
                val bakeResult = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
                if (bakeResult.bitmap == null) {
                    // 守卫拒绝：不发布、不提交配置，诚实引导换源（M4 条件四）
                    ok = false
                    val guardHit = bakeResult.detail?.startsWith("redraw guard") == true ||
                        bakeResult.detail?.startsWith("self-filter guard") == true
                    _saveFeedback.value = ctx.getString(
                        if (guardHit) io.github.deserthouse.opticon.R.string.save_blocked_guard
                        else io.github.deserthouse.opticon.R.string.save_blocked_no_icon
                    )
                } else {
                    // 发布管线（全 IO，A08）：prepare → record → publish → 提交配置 → complete。
                    // 配置在发布确认之后才提交；失败按阶段补偿并恢复完整旧配置。
                    val failStage: String? = withContext(Dispatchers.IO) {
                        val op = io.github.deserthouse.opticon.engine.PublishTransaction.prepare(
                            ctx, pkg, bakeResult.bitmap!!, old.toJson(), newConfig.toJson()
                        ) ?: return@withContext "prepare"
                        if (!io.github.deserthouse.opticon.engine.PublishTransaction.recordIntent(ctx, op)) {
                            return@withContext "record"
                        }
                        if (!io.github.deserthouse.opticon.engine.PublishTransaction.publish(ctx, op)) {
                            val compensated = io.github.deserthouse.opticon.engine.PublishTransaction.compensate(ctx, op)
                            if (compensated) {
                                // 双保险：回写完整旧配置（本流程未先写配置，等值防御）
                                AppIconConfigRepository.save(old)
                                "publish"
                            } else {
                                "partial" // 补偿也失败 → 记录保留，重启恢复收敛
                            }
                        } else if (!AppIconConfigRepository.save(newConfig)) {
                            io.github.deserthouse.opticon.engine.PublishTransaction.compensate(ctx, op)
                            "commit"
                        } else {
                            io.github.deserthouse.opticon.engine.PublishTransaction.complete(ctx, op)
                            try { File(bakeDir(), "$pkg.meta").writeText(bakeResult.detail ?: "saved") } catch (_: Exception) {}
                            null
                        }
                    }
                    if (failStage == null) {
                        _state.update { it.copy(methodEnabled = true, hitSource = bakeResult.source) }
                    } else {
                        ok = false
                        _saveFeedback.value = ctx.getString(
                            if (failStage == "partial") io.github.deserthouse.opticon.R.string.save_recovery_pending
                            else io.github.deserthouse.opticon.R.string.save_failed
                        )
                    }
                }
            } else {
                // 关闭路径（A07 前置语义）：提交 enabled=false 即权威（契约§2 关闭=回落规则库）；
                // 产物清理尽力而为，失败如实反馈但不阻止关闭。
                val cleanupOk = withContext(Dispatchers.IO) { deleteBaked(pkg) }
                val newConfig = io.github.deserthouse.opticon.data.AppIconConfig(
                    pkg = pkg, enabled = false, strategy = s.strategy,
                    assetSubStrategy = s.assetSubStrategy, algoSource = s.algoSource,
                    iconPackPkg = s.selectedIconPack, packDrawable = s.selectedPackIconDrawable,
                    customIconPath = s.customIconPath, materialIconName = s.materialIconName,
                    emojiText = s.emojiText, params = s.redrawParams
                )
                val committed = withContext(Dispatchers.IO) { AppIconConfigRepository.save(newConfig) }
                if (!committed) {
                    ok = false
                    _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_failed)
                } else {
                    if (!cleanupOk) {
                        _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_disable_cleanup_failed)
                    }
                    _state.update { it.copy(methodEnabled = false, hitSource = HitSource.DISABLED) }
                }
            }

            if (ok) {
                // A05（F2-2）：仅当保存期间无新编辑才清 dirty——新草稿保持未保存
                _isDirty.value = draftRevision.get() != savedRevision
                _saveState.value = if (requestLeaveAfter) SaveState.SavedAndExit else SaveState.Saved
                // 本次保存中已产生反馈（如清理失败）则不覆盖为"已保存"
                if (_saveFeedback.value == feedbackBefore) {
                    _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.config_saved)
                }
            } else {
                markDirty()
                _saveState.value = SaveState.Failed
            }
        }
    }

    private fun debouncePreview() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { delay(DEBOUNCE_MS); runPreview() } }
    private fun computePreviewNow() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { runPreview() } }

    private suspend fun runPreview() {
        val s = _state.value
        if (!s.methodEnabled) { _state.update { it.copy(previewBitmap = appBitmap, isRedrawing = false, hitSource = HitSource.DISABLED) }; return }
        _state.update { it.copy(isRedrawing = true) }
        try {
            val ctx = getApplication<Application>().applicationContext
            val result = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
            _state.update { it.copy(previewBitmap = result.bitmap, hitSource = result.source) }
        } catch (e: Exception) {
            TraceLogger.w("OptIcon/AppDetailVM", "runPreview failed: ${e.message}")
            _state.update { it.copy(hitSource = HitSource.OTHER) }
        } finally {
            _state.update { it.copy(isRedrawing = false) }
        }
    }

    private fun bakeCurrentState(context: android.content.Context, s: AppDetailState): IconEngine.BakeResult {
        // A3（阶段 3）：统一烘焙入口——预览与保存共用同一生产路径。
        // VM 只负责从 UI 状态构造 config + inputs；引擎负责全部处理。
        val config = io.github.deserthouse.opticon.data.AppIconConfig(
            pkg = s.packageName,
            enabled = s.methodEnabled,
            strategy = s.strategy,
            assetSubStrategy = s.assetSubStrategy,
            algoSource = s.algoSource,
            iconPackPkg = s.selectedIconPack,
            packDrawable = s.selectedPackIconDrawable,
            customIconPath = s.customIconPath,
            materialIconName = s.materialIconName,
            emojiText = s.emojiText,
            params = s.redrawParams
        )
        val inputs = io.github.deserthouse.opticon.engine.BakeInputs(
            adaptiveForeground = s.adaptiveForeground,
            perfectIconsBitmap = s.perfectIconsBitmap,
            hitSource = s.hitSource
        )
        return io.github.deserthouse.opticon.engine.bakeFromConfigEntry(context, config, inputs)
    }

    private fun bakeDir() = File(getApplication<Application>().filesDir, BAKED_DIR).apply {
        if (!exists()) mkdirs()
        // Make directory world-traversable so SystemUI (UID 1000) can access files inside
        setExecutable(true, false)
        setReadable(true, false)
    }
    /** OI-06: returns true only when the private bake AND the shared-dir
     *  delivery both succeeded — the caller must treat false as a failed
     *  save (rollback + retry), never report "saved". */
    private fun writeBaked(pkg: String, bitmap: Bitmap, sourceName: String): Boolean {
        return try {
            val dir = bakeDir()
            val pngFile = File(dir, "$pkg.png")
            FileOutputStream(pngFile).use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return false }
            pngFile.setReadable(true, false) // World-readable for SystemUI
            val metaFile = File(dir, "$pkg.meta")
            metaFile.writeText(sourceName, Charsets.UTF_8)
            metaFile.setReadable(true, false)
            // Production delivery: publish to shared Downloads dir (SystemUI hook reads there)
            io.github.deserthouse.opticon.engine.SharedIconStore.mirrorIconToShared(getApplication(), pngFile, pkg) != null
        } catch (_: Exception) { false }
    }
    private fun deleteBaked(pkg: String): Boolean {
        val private1 = runCatching { File(bakeDir(), "$pkg.png").delete() }.getOrDefault(false)
        val private2 = runCatching { File(bakeDir(), "$pkg.meta").delete() }.getOrDefault(false)
        val shared = io.github.deserthouse.opticon.engine.SharedIconStore.deleteIcon(getApplication(), pkg)
        return shared || (private1 && private2)
    }

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, size, size); d.draw(Canvas(b)); return b
    }

    private fun decodeFileOrUri(context: android.content.Context, path: String): Bitmap? {
        return if (path.startsWith("content://")) {
            try { val uri = android.net.Uri.parse(path); context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        } else { BitmapFactory.decodeFile(path) }
    }

    override fun onCleared() { super.onCleared(); redrawDebounceJob?.cancel() }
}
