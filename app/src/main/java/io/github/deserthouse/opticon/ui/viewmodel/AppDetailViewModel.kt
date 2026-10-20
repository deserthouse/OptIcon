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
        // C07（X6）：同 pkg 已加载则跳过——Activity 重建时 LaunchedEffect 重跑
        // 不得用持久值覆盖尚未保存的草稿
        if (_state.value.packageName == packageName && _state.value.appName.isNotBlank()) return
        // 同步读取开关状态（经仓库：迁移先行，消除"迁移前值"窗口——C10）
        val initialEnabled = AppIconConfigRepository.load(packageName).enabled
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
                    iconPacksLoaded = true,
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
            // C05（X4）：预取不写当前来源预览——仅当用户所选来源就是 PICP 时才
            // 联动 hitSource/previewBitmap，否则只入素材缓存待选
            if (PicpEngine.hasIcon(packageName, context)) {
                withContext(Dispatchers.IO) {
                    val bmp = PicpEngine.downloadIcon(getApplication(), packageName)
                    if (bmp != null) {
                        val sel = _state.value
                        if (sel.strategy == IconStrategy.ASSET_IMPORT && sel.assetSubStrategy == AssetSubStrategy.PERFECT_ICONS) {
                            _state.update { it.copy(perfectIconsBitmap = bmp, previewBitmap = bmp, hitSource = HitSource.PICP) }
                        } else {
                            _state.update { it.copy(perfectIconsBitmap = bmp) }
                        }
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
                // C05（X4）：素材到达必须触发重算——旧实现只写字段，先行的预览
                // 停在 miss 直到用户再动参数
                _state.update { it.copy(adaptiveForeground = result.first, adaptiveBackground = result.second, isAdaptiveIcon = true) }
                debouncePreview()
            } else {
                _state.update { it.copy(isAdaptiveIcon = false) }
            }
        }
    }

    /** OI-13 补强（架构报告 §5）：分类协程的 generation 守卫——快速切换
     *  来源/素材时，晚到的旧分类结果不得回填覆盖新选择。 */
    private val algoClassGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** A04（F2-1）+C06（X5）：上传图片=解码校验 → 转存 App 私有稳定副本 →
     *  原子切源。content URI 不直接进长期配置（重启/授权失效后不可读）；
     *  私有副本以 App 属主常读，重绘随时可用原色字节。 */
    fun applyUploadedIcon(uri: String) {
        markDirty()
        val gen = algoClassGeneration.incrementAndGet()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val bmp = decodeFileOrUri(app, uri)
                    ?: return@withContext null // 解码失败：保持旧选择（下方反馈）
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val classified = IconRedrawEngine.classifyInput(px, bmp.width)
                // V02（Y2）：内容寻址副本——同字节复用、不同字节永不覆写旧文件，
                // 放弃/失败对已提交源零副作用。原色字节不预白化（重绘需要原始色距）
                val assetsDir = File(app.filesDir, "assets").apply { mkdirs() }
                val bytes = try {
                    app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                        ?: bmp.toPngBytes()
                } catch (_: Exception) {
                    bmp.toPngBytes()
                }
                val sha = io.github.deserthouse.opticon.engine.publish.PublishCore.sha256(bytes)
                val stable = File(assetsDir, "upload-$sha.png")
                if (!stable.exists()) stable.writeBytes(bytes) // 已存在=同内容复用，绝不覆写
                bmp.recycle()
                classified to stable.absolutePath
            }
            if (algoClassGeneration.get() != gen) return@launch // 过期结果丢弃
            if (result == null) {
                _saveFeedback.value = getApplication<Application>().getString(
                    io.github.deserthouse.opticon.R.string.upload_decode_failed
                )
                return@launch
            }
            val (classified, stablePath) = result
            _state.update { it.copy(customIconPath = stablePath, algoSource = AlgoSource.LOCAL_FILE) }
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
            // C11（X8）：字符串比较识别"是否已有反馈"不可靠——改显式标记
            var feedbackSet = false
            var ok = true

            // V06（Y1a）：完整提交流水线（含旧快照读取=一致性边界）与启动恢复
            // 经 PublishCoordinator 互斥——恢复不再能在保存阶段之间插入
            io.github.deserthouse.opticon.engine.publish.PublishCoordinator.withPipeline {
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
                    feedbackSet = true
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
                    if (failStage == null && draftRevision.get() == savedRevision) {
                        // C01：保存期间有新编辑时不回写——草稿字段（开关/来源等）优先于已提交快照
                        _state.update { it.copy(methodEnabled = true, hitSource = bakeResult.source) }
                    } else if (failStage == null) {
                        _state.update { it.copy(hitSource = bakeResult.source) } // 仅更新来源显示，不动开关草稿
                    } else {
                        ok = false
                        feedbackSet = true
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
                    feedbackSet = true
                    _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_failed)
                } else {
                    if (!cleanupOk) {
                        feedbackSet = true
                        _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_disable_cleanup_failed)
                    }
                    if (draftRevision.get() == savedRevision) {
                        // C01：新草稿在场时不覆盖开关草稿值
                        _state.update { it.copy(methodEnabled = false, hitSource = HitSource.DISABLED) }
                    }
                }
            } // if (s.methodEnabled) end
            } // V06: PublishCoordinator.withPipeline end

            if (ok) {
                // C01（X3）：新草稿在场时——不清 dirty、不自动离开、不覆盖草稿字段
                val newerDraft = draftRevision.get() != savedRevision
                _isDirty.value = newerDraft
                when {
                    requestLeaveAfter && newerDraft -> _saveState.value = SaveState.Saved // 留在页面：旧版已提交，新编辑仍待保存
                    requestLeaveAfter -> _saveState.value = SaveState.SavedAndExit
                    else -> _saveState.value = SaveState.Saved
                }
                if (!feedbackSet) {
                    _saveFeedback.value = ctx.getString(
                        if (newerDraft) io.github.deserthouse.opticon.R.string.save_partial_newer_draft
                        else if (s.methodEnabled) io.github.deserthouse.opticon.R.string.config_saved
                        else io.github.deserthouse.opticon.R.string.config_saved_disabled
                    )
                }
            } else {
                markDirty()
                _saveState.value = SaveState.Failed
            }
        }
    }

    private fun debouncePreview() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { delay(DEBOUNCE_MS); runPreview() } }
    private fun computePreviewNow() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { runPreview() } }

    private val previewGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun runPreview() {
        val gen = previewGeneration.incrementAndGet()
        val s = _state.value
        // C08（X4）：关闭态不再画应用图标伪结果——契约回落=规则库/原生图标，
        // 启动图标轮廓不代表真实回落产物；空预览+既有关闭引导文案才是诚实表达
        if (!s.methodEnabled) { _state.update { it.copy(previewBitmap = null, isRedrawing = false, hitSource = HitSource.DISABLED) }; return }
        _state.update { it.copy(isRedrawing = true) }
        try {
            val ctx = getApplication<Application>().applicationContext
            val result = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
            if (previewGeneration.get() != gen) return // C05：过期任务不回填
            _state.update { it.copy(previewBitmap = result.bitmap, hitSource = result.source) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // C05：取消继续传播，不清除新任务的进行中状态
        } catch (e: Exception) {
            if (previewGeneration.get() != gen) return
            TraceLogger.w("OptIcon/AppDetailVM", "runPreview failed: ${e.message}")
            _state.update { it.copy(hitSource = HitSource.OTHER) }
        } finally {
            if (previewGeneration.get() == gen) _state.update { it.copy(isRedrawing = false) }
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
        // V05（Y4）：按最终状态判定——目标本就不存在=幂等成功，不制造假告警；
        // 只有实际残留（存在但删不掉/索引与文件失配）才报失败
        val png = File(bakeDir(), "$pkg.png")
        val meta = File(bakeDir(), "$pkg.meta")
        val private1 = if (png.exists()) runCatching { png.delete() }.getOrDefault(false) else true
        val private2 = if (meta.exists()) runCatching { meta.delete() }.getOrDefault(false) else true
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

    private fun android.graphics.Bitmap.toPngBytes(): ByteArray = java.io.ByteArrayOutputStream().use {
        compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
}
