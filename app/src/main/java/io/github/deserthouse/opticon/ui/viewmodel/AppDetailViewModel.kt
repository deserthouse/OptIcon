package io.github.deserthouse.opticon.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.opticon.data.AssetRepository as AppIconAssetRepository
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
import io.github.deserthouse.opticon.ui.session.EditorSessionCore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AppDetailViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptIcon/AppDetailVM"
        private const val DEBOUNCE_MS = 300L
        private const val BAKED_DIR = "baked"
        private const val OUTPUT_SIZE = 96
        /** Q6：非 ALGORITHM 路径的 scale 有效域上界（引擎 applyAdaptiveScale coerce 口径） */
        private const val ADAPTIVE_SCALE_MAX = 1.5f
    }

    private val _state = MutableStateFlow(AppDetailState())
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    /** A05（F2-2）：草稿版本号——每次编辑递增。保存完成时仅当快照版本仍为
     *  最新才清 dirty，保存期间的新编辑保持未保存状态。 */
    // RB-1：三手工计数器收敛于 EditorSessionCore（revision/inputVersion/previewRound）
    private val session = io.github.deserthouse.opticon.ui.session.EditorSessionCore()
    private fun markDirty() {
        session.touch()
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
        // C07（RB-3a）：幂等守卫内建于 EditorSessionCore——同 pkg 已激活即跳过
        if (!session.beginSession(packageName)) return
        // K9（Z3）：主线程只做纯读（无迁移写）——迁移/校验在异步块经仓库完成
        // R2-12：新会话统一重置位图族字段——防未来 detail→detail 导航时 B 应用
        // 继承 A 的 PICP/自适应位图参与预览烘焙（当前导航模型不可达，防御性）
        _state.update {
            it.copy(
                packageName = packageName,
                methodEnabled = PreferenceManager.isMethodEnabled(packageName),
                perfectIconsBitmap = null, adaptiveForeground = null, adaptiveBackground = null,
                isAdaptiveIcon = null, previewBitmap = null,
                previewState = EditorSessionCore.PreviewState.Missing, hitSource = HitSource.OTHER
            )
        }
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
            } ?: run {
                // Q4：加载失败（应用被卸载/pm 异常）释放会话登记——重进同包名可重试，
                // 不再永久锁死在空白默认态
                session.abandonSession(packageName)
                _saveFeedback.value = getApplication<Application>().getString(
                    io.github.deserthouse.opticon.R.string.app_load_failed
                )
                return@launch
            }
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
            // Q2（K11）：索引文件读取移 IO
            val hasPicp = withContext(Dispatchers.IO) { PicpEngine.hasIcon(packageName, context) }
            if (hasPicp) {
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

    /** Q6：跨策略 scale 域迁移——ALGORITHM（兼容重绘）允许 0.5..2.0，其余
     *  路径引擎侧 coerce 0.5..1.5；切换时按目标域钳制，消除滑杆显示值与
     *  生效值不一致（A13 要消除的"显示变化但结果不变"类残余洞）。 */
    private fun clampedScaleFor(strategy: IconStrategy, params: RedrawParams): RedrawParams =
        if (strategy == IconStrategy.ALGORITHM) params
        else params.copy(scale = params.scale.coerceIn(RedrawParams.SCALE_MIN, ADAPTIVE_SCALE_MAX))

    fun setStrategy(strategy: IconStrategy) {
        markDirty()
        _state.update { it.copy(strategy = strategy, redrawParams = clampedScaleFor(strategy, it.redrawParams), previewBitmap = null, hitSource = HitSource.OTHER) }
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
                // Q6：ASSET_IMPORT 全部子策略走自适应域（引擎 coerce 0.5..1.5）
                redrawParams = if (it.strategy == IconStrategy.ALGORITHM) it.redrawParams
                    else it.redrawParams.copy(scale = it.redrawParams.scale.coerceIn(RedrawParams.SCALE_MIN, ADAPTIVE_SCALE_MAX)),
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
            val gen = session.bumpInput()
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
                if (!session.isCurrentInput(gen)) return@launch   // 过期结果丢弃
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
    /** A04（F2-1）+C06（X5）：上传图片=解码校验 → 转存 App 私有稳定副本 →
     *  原子切源。content URI 不直接进长期配置（重启/授权失效后不可读）；
     *  私有副本以 App 属主常读，重绘随时可用原色字节。 */
    fun applyUploadedIcon(uri: String) {
        val gen = session.bumpInput()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    // 原始字节全量读入（内容寻址需要）——文件量级，安全
                    val bytes: ByteArray = if (uri.startsWith("content://")) {
                        app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                            ?: return@withContext null
                    } else {
                        File(android.net.Uri.parse(uri).path ?: uri).takeIf { it.exists() }?.readBytes()
                            ?: return@withContext null
                    }
                    // K5（Z2）：采样解码——长边 ≤512，分类/副本/预览都不需要全分辨率
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@withContext null
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val classified = IconRedrawEngine.classifyInput(px, bmp.width)
                    // RB-2：资产仓统一导入（内容寻址+原子写；同字节复用）
                    val ref = AppIconAssetRepository.importAsset(app, bytes) ?: return@withContext null
                    bmp.recycle()
                    classified to ref.path
                } catch (e: Exception) {
                    // K4（Z2）：落盘/解码 IOException 不崩溃——保持旧选择
                    android.util.Log.w("OptIcon/AppDetailVM", "applyUploadedIcon: ${e.message}")
                    null
                }
            }
            if (!session.isCurrentInput(gen)) return@launch // 过期结果丢弃
            if (result == null) {
                _saveFeedback.value = getApplication<Application>().getString(
                    io.github.deserthouse.opticon.R.string.upload_decode_failed
                )
                return@launch
            }
            val (classified, stablePath) = result
            // Q3：素材确实导入成功才置脏——失败路径（含解码失败）不留假脏，
            // 离开时不再误弹「未保存更改」
            markDirty()
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
        val gen = session.bumpInput()
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
            if (!session.isCurrentInput(gen)) return@launch   // 过期结果丢弃
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
    /** A3（R2-03）：色彩策略写互斥——快速连续切换时磁盘终态=最后一次点击，
     *  杜绝"UI 取最后完成者、磁盘取最后执行者"的顺序分离。 */
    private val colorWriteMutex = Mutex()

    fun setColorOverride(value: String?) {
        val ctx = getApplication<Application>()
        val pkg = _state.value.packageName
        // Q2：共享目录写盘（含 MediaStore/迁移检查）移出主线程；「立即生效」
        // 语义与 Coordinator 锁豁免口径不变。
        viewModelScope.launch {
            // A10（F2-4）：写入/删除结果决定 UI 显示值——失败保持旧值并如实反馈，
            // 不得出现"显示已切换但从未持久生效"。
            val ok = colorWriteMutex.withLock {
                withContext(Dispatchers.IO) {
                    if (value == null) {
                        io.github.deserthouse.opticon.engine.SharedIconStore.deleteColorOverride(ctx, pkg)
                    } else {
                        io.github.deserthouse.opticon.engine.SharedIconStore.writeColorOverride(ctx, pkg, value) != null
                    }
                }
            }
            if (ok) {
                _state.update { it.copy(colorOverride = value) }
            } else {
                _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.color_override_write_failed)
            }
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
        // R2-02：与滑杆/引擎同域（10..150）
        _state.update { it.copy(redrawParams = it.redrawParams.copy(threshold = v.coerceIn(io.github.deserthouse.opticon.engine.RedrawParams.THRESHOLD_MIN, io.github.deserthouse.opticon.engine.RedrawParams.THRESHOLD_MAX))) }
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
        val savedRevision = session.saveStarted()
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
                val newConfig = stateToConfig(s, enabled = true)
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
                            // K10（Z1a）：record 失败也走补偿——幂等清理已写的私有残留
                            io.github.deserthouse.opticon.engine.PublishTransaction.compensate(ctx, op)
                            return@withContext "record"
                        }
                        if (!io.github.deserthouse.opticon.engine.PublishTransaction.publish(ctx, op)) {
                            val compensated = io.github.deserthouse.opticon.engine.PublishTransaction.compensate(ctx, op)
                            if (compensated) {
                                // 双保险：回写完整旧配置（本流程未先写配置，等值防御）
                                val rolled = AppIconConfigRepository.save(old)
                                // K1（Z1a）：补偿成功即清账——残留记录会在重启时回滚后继
                                // 成功保存（K1 触发链）；配置回滚失败则留 Partial 待收敛
                                if (rolled) io.github.deserthouse.opticon.engine.PublishTransaction.complete(ctx, op)
                                if (rolled) "publish" else "partial"
                            } else {
                                "partial" // 补偿也失败 → 记录保留，重启恢复收敛
                            }
                        } else if (!AppIconConfigRepository.save(newConfig)) {
                            io.github.deserthouse.opticon.engine.PublishTransaction.compensate(ctx, op)
                            "commit"
                        } else {
                            // RB-1c：meta 归事务核管辖（baked/ 单一所有权）
                            io.github.deserthouse.opticon.engine.PublishTransaction.complete(ctx, op, bakeResult.detail ?: "saved")
                            // RB-2：旧素材不再被引用即回收（草稿仍引用则保留）
                            val oldAsset = old.customIconPath
                            if (oldAsset != null && oldAsset != newConfig.customIconPath &&
                                _state.value.customIconPath != oldAsset) {
                                AppIconAssetRepository.sweepIfUnreferenced(ctx, oldAsset)
                            }
                            null
                        }
                    }
                    if (failStage == null && session.revisionNow() == savedRevision) {
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
                val newConfig = stateToConfig(s, enabled = false)
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
                    if (session.revisionNow() == savedRevision) {
                        // C01：新草稿在场时不覆盖开关草稿值
                        _state.update { it.copy(methodEnabled = false, hitSource = HitSource.DISABLED) }
                    }
                }
            } // if (s.methodEnabled) end
            } // V06: PublishCoordinator.withPipeline end

            if (ok) {
                // C01（RB-1）：保存结算收敛于 EditorSessionCore——新草稿在场时
                // 不清 dirty、不自动离开、不覆盖草稿字段
                when (session.resolveSaveCompletion(savedRevision, ok = true, requestLeaveAfter = requestLeaveAfter)) {
                    io.github.deserthouse.opticon.ui.session.EditorSessionCore.SaveOutcome.SavedStayDirty -> {
                        _isDirty.value = true
                        _saveState.value = SaveState.Saved
                        if (!feedbackSet) {
                            _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_partial_newer_draft)
                        }
                    }
                    io.github.deserthouse.opticon.ui.session.EditorSessionCore.SaveOutcome.SavedExit -> {
                        _isDirty.value = false
                        _saveState.value = SaveState.SavedAndExit
                        if (!feedbackSet) {
                            _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.config_saved)
                        }
                    }
                    else -> {
                        _isDirty.value = false
                        _saveState.value = SaveState.Saved
                        if (!feedbackSet) {
                            _saveFeedback.value = ctx.getString(
                                if (s.methodEnabled) io.github.deserthouse.opticon.R.string.config_saved
                                else io.github.deserthouse.opticon.R.string.config_saved_disabled
                            )
                        }
                    }
                }
            } else {
                session.resolveSaveCompletion(savedRevision, ok = false, requestLeaveAfter = false)
                _isDirty.value = true
                _saveState.value = SaveState.Failed
            }
        }
    }

    private fun debouncePreview() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { delay(DEBOUNCE_MS); runPreview() } }
    private fun computePreviewNow() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { runPreview() } }

    private suspend fun runPreview() {
        val gen = session.nextPreviewRound()
        val s = _state.value
        val pkgAtStart = s.packageName // RB-3b：会话切走后过期预览不回填
        // C08（X4）：关闭态不再画应用图标伪结果——契约回落=规则库/原生图标，
        // 启动图标轮廓不代表真实回落产物；空预览+既有关闭引导文案才是诚实表达
        if (!s.methodEnabled) { _state.update { it.copy(previewBitmap = null, previewState = EditorSessionCore.PreviewState.Missing, hitSource = HitSource.DISABLED) }; return }
        _state.update { it.copy(previewState = EditorSessionCore.PreviewState.Updating(lastSourceTag = s.hitSource.name)) }
        try {
            val ctx = getApplication<Application>().applicationContext
            val result = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
            if (!session.isLatestPreviewRound(gen)) return // C05：过期任务不回填
            if (_state.value.packageName != pkgAtStart) return // RB-3b：会话已切走
            // Q10（C08 残余）：守卫拒绝（不可用素材）不再与"未配置"共用"?"——
            // 预览走拒绝态，判定口径与保存侧 guardHit 一致
            val rejected = result.bitmap == null && (
                result.detail?.startsWith("redraw guard") == true ||
                result.detail?.startsWith("self-filter guard") == true
            )
            val ps = when {
                rejected -> EditorSessionCore.PreviewState.Rejected(result.detail ?: "")
                result.bitmap != null -> EditorSessionCore.PreviewState.Current(result.source.name)
                else -> EditorSessionCore.PreviewState.Missing
            }
            _state.update { it.copy(previewBitmap = result.bitmap, hitSource = result.source, previewState = ps) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // C05：取消继续传播，不清除新任务的进行中状态
        } catch (e: Exception) {
            if (!session.isLatestPreviewRound(gen)) return
            TraceLogger.w("OptIcon/AppDetailVM", "runPreview failed: ${e.message}")
            // A5.2：异常也必须落终态，否则 Updating 态卡死 spinner
            val ps = if (_state.value.previewBitmap != null) EditorSessionCore.PreviewState.Current(HitSource.OTHER.name)
                     else EditorSessionCore.PreviewState.Missing
            _state.update { it.copy(hitSource = HitSource.OTHER, previewState = ps) }
        }
    }

    /** RB-1b（架构前置⑤）：草稿 → 已提交配置的唯一构造映射（原三处重复收敛）。 */
    private fun stateToConfig(s: AppDetailState, enabled: Boolean): io.github.deserthouse.opticon.data.AppIconConfig =
        io.github.deserthouse.opticon.data.AppIconConfig(
            pkg = s.packageName, enabled = enabled, strategy = s.strategy,
            assetSubStrategy = s.assetSubStrategy, algoSource = s.algoSource,
            iconPackPkg = s.selectedIconPack, packDrawable = s.selectedPackIconDrawable,
            customIconPath = s.customIconPath, materialIconName = s.materialIconName,
            emojiText = s.emojiText, params = s.redrawParams
        )

    private fun bakeCurrentState(context: android.content.Context, s: AppDetailState): IconEngine.BakeResult {
        // A3（阶段 3）：统一烘焙入口——预览与保存共用同一生产路径。
        // VM 只负责从 UI 状态构造 config + inputs；引擎负责全部处理。
        val config = stateToConfig(s, enabled = s.methodEnabled)
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
    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, size, size); d.draw(Canvas(b)); return b
    }

    /** V05+K2（Z2）：终态幂等删除——hook 只读共享 canonical 文件，它不存在即
     *  交付终态达成；私有位删除照常判定；仅实际残留报失败（假告警已修）。 */
    private fun deleteBaked(pkg: String): Boolean {
        val png = File(bakeDir(), "$pkg.png")
        val meta = File(bakeDir(), "$pkg.meta")
        val private1 = if (png.exists()) runCatching { png.delete() }.getOrDefault(false) else true
        val private2 = if (meta.exists()) runCatching { meta.delete() }.getOrDefault(false) else true
        io.github.deserthouse.opticon.engine.SharedIconStore.deleteIcon(getApplication(), pkg)
        val sharedGone = !io.github.deserthouse.opticon.engine.SharedIconStore.iconFile(pkg).exists()
        return sharedGone && private1 && private2
    }

    private fun decodeFileOrUri(context: android.content.Context, path: String): Bitmap? {
        return if (path.startsWith("content://")) {
            try { val uri = android.net.Uri.parse(path); context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        } else { BitmapFactory.decodeFile(path) }
    }

    override fun onCleared() { super.onCleared(); redrawDebounceJob?.cancel() }

}
