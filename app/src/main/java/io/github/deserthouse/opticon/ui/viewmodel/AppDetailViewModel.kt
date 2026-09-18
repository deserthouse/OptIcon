package io.github.deserthouse.opticon.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

            val savedStrategy = PreferenceManager.getStrategy(packageName)
            val packs = withContext(Dispatchers.IO) { IconPackEngine.listInstalledIconPacks(context) }

            _state.update {
                it.copy(
                    appName = app.appName, isSystemApp = app.isSystemApp,
                    strategy = savedStrategy,
                    customIconPath = PreferenceManager.getCustomIconPath(packageName),
                    materialIconName = PreferenceManager.getMaterialIconName(packageName),
                    emojiText = PreferenceManager.getEmojiText(packageName),
                    emojiUnlocked = PreferenceManager.isEmojiUnlocked(),
                    redrawParams = loadSavedParams(packageName),
                    iconPacks = packs,
                    selectedIconPack = null,
                    colorOverride = io.github.deserthouse.opticon.engine.SharedIconStore.readColorOverride(packageName),
                    hasOriginalCaptured = io.github.deserthouse.opticon.engine.SharedIconStore.originalIconFile(packageName).exists()
                )
            }

            // Auto-download PICP icon if available — no user tap required
            if (PicpEngine.hasIcon(packageName, context)) {
                withContext(Dispatchers.IO) {
                    val bmp = PicpEngine.downloadIcon(getApplication(), packageName)
                    if (bmp != null) {
                        _state.update { it.copy(perfectIconsBitmap = bmp, previewBitmap = bmp, hitLevel = "PICP hit") }
                    }
                }
            }

            decomposeAdaptiveIcon()
            computePreviewNow()
        }
    }

    fun setStrategy(strategy: IconStrategy) {
        _isDirty.value = true
        _state.update { it.copy(strategy = strategy, previewBitmap = null, hitLevel = "") }
        debouncePreview()
    }

    fun setAssetSubStrategy(sub: AssetSubStrategy) {
        _isDirty.value = true
        val cached = state.value.perfectIconsBitmap
        _state.update {
            if (sub == AssetSubStrategy.PERFECT_ICONS && cached != null) {
                it.copy(assetSubStrategy = sub, previewBitmap = cached, hitLevel = "PICP hit")
            } else {
                it.copy(assetSubStrategy = sub)
            }
        }
        if (sub == AssetSubStrategy.ADAPTIVE_DECOMPOSE) decomposeAdaptiveIcon()
        debouncePreview()
    }

    fun setAlgoSource(src: AlgoSource) {
        _isDirty.value = true
        _state.update { it.copy(algoSource = src) }
        debouncePreview()
    }

    fun setMethodEnabled(enabled: Boolean) {
        _isDirty.value = true
        _state.update { it.copy(methodEnabled = enabled) }
        debouncePreview()
    }

    fun setSelectedIconPack(pkg: String?) {
        _isDirty.value = true
        _state.update { it.copy(selectedIconPack = pkg, selectedPackIconDrawable = null) }
        if (pkg != null) loadIconPackIcons(pkg)
        debouncePreview()
    }

    fun setSelectedPackIconDrawable(drawableName: String?) {
        _isDirty.value = true
        _state.update { it.copy(selectedPackIconDrawable = drawableName) }
        debouncePreview()
    }

    private fun loadIconPackIcons(pkg: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val icons = withContext(Dispatchers.IO) { IconPackEngine.listAllIcons(ctx, pkg) }
            _state.update { it.copy(iconPackIcons = icons) }
        }
    }

    fun pullFromPerfectIcons() {
        _state.update { it.copy(perfectIconsLoading = true) }
        viewModelScope.launch {
            val pkg = _state.value.packageName
            try {
                val bitmap = withContext(Dispatchers.IO) { fetchPerfectIcon(pkg) }
                if (bitmap != null) {
                    val white = withContext(Dispatchers.IO) { IconTint.toWhite(bitmap) }
                    _state.update { it.copy(previewBitmap = white, hitLevel = "Perfect Icons hit", perfectIconsLoading = false) }
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
                if (bitmap != null) it.copy(perfectIconsBitmap = bitmap, previewBitmap = bitmap, hitLevel = "PICP hit", perfectIconsLoading = false)
                else it.copy(perfectIconsLoading = false, hitLevel = "PICP download failed")
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
                _state.update { it.copy(previewBitmap = libIcon, hitLevel = "Local built-in (IconLib)", perfectIconsLoading = false) }
            } else {
                _state.update { it.copy(previewBitmap = null, hitLevel = "Network unavailable, all sources missed", perfectIconsLoading = false) }
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

    fun setCustomIconPath(path: String?) {
        _isDirty.value = true
        _state.update { it.copy(customIconPath = path) }
        debouncePreview()
    }

    /** #13 每应用色彩覆盖：null=继承全局, "mono"=强制单色, "color"=保持原色。
     *  立即写共享目录（hook 侧 mtime 缓存即时感知），不随保存流程。 */
    fun setColorOverride(value: String?) {
        val ctx = getApplication<Application>()
        if (value == null) io.github.deserthouse.opticon.engine.SharedIconStore.deleteColorOverride(ctx, _state.value.packageName)
        else io.github.deserthouse.opticon.engine.SharedIconStore.writeColorOverride(ctx, _state.value.packageName, value)
        _state.update { it.copy(colorOverride = value) }
    }
    fun setEmojiText(text: String?) {
        _isDirty.value = true
        _state.update { it.copy(emojiText = text) }
        debouncePreview()
    }
    fun selectMaterialIcon(name: String) {
        _isDirty.value = true
        _state.update { it.copy(materialIconName = name) }
        debouncePreview()
    }

    fun setScale(v: Float) {
        _isDirty.value = true
        _state.update { it.copy(redrawParams = it.redrawParams.copy(scale = v.coerceIn(RedrawParams.SCALE_MIN, RedrawParams.SCALE_MAX))) }
        debouncePreview()
    }
    fun setOffsetX(v: Float) {
        _isDirty.value = true
        _state.update { it.copy(redrawParams = it.redrawParams.copy(offsetX = v.coerceIn(-RedrawParams.OFFSET_MAX, RedrawParams.OFFSET_MAX))) }
        debouncePreview()
    }
    fun setOffsetY(v: Float) {
        _isDirty.value = true
        _state.update { it.copy(redrawParams = it.redrawParams.copy(offsetY = v.coerceIn(-RedrawParams.OFFSET_MAX, RedrawParams.OFFSET_MAX))) }
        debouncePreview()
    }
    fun setThreshold(v: Int) {
        _isDirty.value = true
        _state.update { it.copy(redrawParams = it.redrawParams.copy(threshold = v.coerceIn(0, 255))) }
        debouncePreview()
    }
    fun setRadius(v: Float) {
        _isDirty.value = true
        _state.update { it.copy(redrawParams = it.redrawParams.copy(radius = v.coerceIn(0f, RedrawParams.RADIUS_MAX))) }
        debouncePreview()
    }

    fun showSheet() = _state.update { it.copy(sheetVisible = true) }
    fun hideSheet() = _state.update { it.copy(sheetVisible = false) }
    fun togglePreviewMode() { _state.update { it.copy(previewMode = if (it.previewMode == StatusBarMode.DARK) StatusBarMode.LIGHT else StatusBarMode.DARK) } }

    fun saveConfig() {
        _isDirty.value = false
        val s = _state.value; val pkg = s.packageName
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            var enabled = s.methodEnabled
            var bakeResult: IconEngine.BakeResult? = null
            if (enabled) {
                // Guard: a strategy that cannot produce an icon (e.g. FANKES
                // rule not covering this app) must NOT persist as "enabled" —
                // that would desync the switch from reality. Force it off and
                // tell the user why.
                bakeResult = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
                if (bakeResult.bitmap == null) {
                    enabled = false
                    _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.save_blocked_no_icon)
                }
            }
            PreferenceManager.setMethodEnabled(pkg, enabled)
            PreferenceManager.setStrategy(pkg, s.strategy)
            PreferenceManager.setCustomIconPath(pkg, s.customIconPath)
            PreferenceManager.setMaterialIconName(pkg, s.materialIconName)
            PreferenceManager.setEmojiText(pkg, s.emojiText)
            PreferenceManager.setScale(pkg, s.redrawParams.scale)
            PreferenceManager.setThreshold(pkg, s.redrawParams.threshold)
            PreferenceManager.setOffsetX(pkg, s.redrawParams.offsetX)
            PreferenceManager.setOffsetY(pkg, s.redrawParams.offsetY)
            PreferenceManager.setRadius(pkg, s.redrawParams.radius)

            withContext(Dispatchers.IO) {
                if (enabled && bakeResult?.bitmap != null) writeBaked(pkg, bakeResult.bitmap, bakeResult.hitLevel) else deleteBaked(pkg)
            }
            if (enabled) {
                _saveFeedback.value = ctx.getString(io.github.deserthouse.opticon.R.string.config_saved)
            }
            _state.update { it.copy(methodEnabled = enabled, hitLevel = bakeResult?.hitLevel ?: "Disabled") }
        }
    }

    private fun debouncePreview() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { delay(DEBOUNCE_MS); runPreview() } }
    private fun computePreviewNow() { redrawDebounceJob?.cancel(); redrawDebounceJob = viewModelScope.launch { runPreview() } }

    private suspend fun runPreview() {
        val s = _state.value
        if (!s.methodEnabled) { _state.update { it.copy(previewBitmap = appBitmap, isRedrawing = false, hitLevel = "Disabled") }; return }
        _state.update { it.copy(isRedrawing = true) }
        try {
            val ctx = getApplication<Application>().applicationContext
            val result = withContext(Dispatchers.IO) { bakeCurrentState(ctx, s) }
            _state.update { it.copy(previewBitmap = result.bitmap, hitLevel = result.hitLevel) }
        } catch (e: Exception) {
            TraceLogger.w("OptIcon/AppDetailVM", "runPreview failed: ${e.message}")
            _state.update { it.copy(hitLevel = "Preview error: ${e.message}") }
        } finally {
            _state.update { it.copy(isRedrawing = false) }
        }
    }

    private fun bakeCurrentState(context: android.content.Context, s: AppDetailState): IconEngine.BakeResult {
        return when (s.strategy) {
            IconStrategy.FANKES -> {
                val icon = IconLibEngine.lookup(s.packageName)
                if (icon != null) IconEngine.BakeResult(icon, "Fankes 规则库命中")
                else IconEngine.BakeResult(null, "Fankes 规则库未适配")
            }
            IconStrategy.ASSET_IMPORT -> when (s.assetSubStrategy) {
                AssetSubStrategy.ICON_PACK -> {
                    if (s.selectedPackIconDrawable != null && s.selectedIconPack != null) {
                        IconEngine.bakeIconPackViaDrawable(context, s.packageName, s.selectedIconPack, s.selectedPackIconDrawable, s.redrawParams)
                    } else {
                        val packs = IconPackEngine.listInstalledIconPacks(context)
                        val target = if (s.selectedIconPack != null) packs.filter { it.packageName == s.selectedIconPack } else packs
                        var result: IconEngine.BakeResult? = null
                        for (pack in target) {
                            val r = IconPackEngine.extractForPackage(context, pack.packageName, s.packageName)
                            if (r != null) { result = IconEngine.BakeResult(r, "Icon pack hit: ${pack.label}"); break }
                        }
                        result ?: IconEngine.BakeResult(null, "Icon pack miss")
                    }
                }
                AssetSubStrategy.ADAPTIVE_DECOMPOSE -> {
                    val fg = s.adaptiveForeground
                    if (fg != null) {
                        val normalized = IconNormalizer.normalize(fg)
                        val white = IconTint.toWhite(normalized)
                        if (white != null) IconEngine.BakeResult(white, "Adaptive decompose: foreground")
                        else IconEngine.BakeResult(null, "Adaptive decompose: tint failed")
                    } else {
                        IconEngine.BakeResult(null, "Not an adaptive icon")
                    }
                }
                AssetSubStrategy.PERFECT_ICONS -> {
                    val cached = s.previewBitmap ?: s.perfectIconsBitmap
                    if (cached != null) IconEngine.BakeResult(cached, s.hitLevel.takeIf { it.isNotBlank() } ?: "PICP hit")
                    else IconEngine.BakeResult(null, "Perfect Icons: not fetched yet")
                }
            }
            IconStrategy.ALGORITHM -> when (s.algoSource) {
                AlgoSource.SELF -> {
                    // Zero-friction default: redraw the app's own launcher
                    // icon through the four-corner self-filter pipeline.
                    IconEngine.bake(
                        context = context,
                        packageName = s.packageName,
                        method = io.github.deserthouse.opticon.util.PreferenceManager.IconMethod.SELF_FILTER,
                        branch = null,
                        params = s.redrawParams,
                        localPath = null,
                        materialIconName = null,
                        emojiText = null
                    )
                }
                AlgoSource.LOCAL_FILE -> {
                    if (s.customIconPath.isNullOrBlank()) IconEngine.BakeResult(null, "Algo: no file selected")
                    else {
                        val src = decodeFileOrUri(context, s.customIconPath)
                        if (src != null) {
                            val white = IconTint.toWhite(src)
                            if (src !== white) src.recycle()
                            if (white == null) return IconEngine.BakeResult(null, "Algo: tint failed")
                            val refined = IconRedrawEngine.redraw(white, s.redrawParams)
                            if (refined !== white) white.recycle()
                            IconEngine.BakeResult(refined ?: white, "Algo: local file + redraw")
                        } else IconEngine.BakeResult(null, "Algo: file decode failed")
                    }
                }
                AlgoSource.MATERIAL_LIB -> {
                    if (s.materialIconName.isNullOrBlank()) IconEngine.BakeResult(null, "Algo: no material icon selected")
                    else {
                        val icon = MaterialIconRenderer.findByName(s.materialIconName)
                        if (icon != null) {
                            val bmp = MaterialIconRenderer.renderToWhiteBitmap(icon)
                                ?: return IconEngine.BakeResult(null, "Algo: material icon render failed")
                            val refined = IconRedrawEngine.redraw(bmp, s.redrawParams) ?: bmp
                            IconEngine.BakeResult(refined, "Algo: Material icon + redraw")
                        } else IconEngine.BakeResult(null, "Algo: material icon not found")
                    }
                }
                AlgoSource.EMOJI_TEXT -> {
                    if (s.emojiText.isNullOrBlank()) IconEngine.BakeResult(null, "Algo: no emoji/text")
                    else {
                        val bmp = io.github.deserthouse.opticon.engine.EmojiRenderer.renderToWhiteBitmap(s.emojiText)
                        if (bmp != null) {
                            val refined = IconRedrawEngine.redraw(bmp, s.redrawParams) ?: bmp
                            IconEngine.BakeResult(refined, "Algo: Emoji + redraw")
                        } else IconEngine.BakeResult(null, "Algo: Emoji render failed")
                    }
                }
            }
        }
    }

    private fun bakeDir() = File(getApplication<Application>().filesDir, BAKED_DIR).apply {
        if (!exists()) mkdirs()
        // Make directory world-traversable so SystemUI (UID 1000) can access files inside
        setExecutable(true, false)
        setReadable(true, false)
    }
    private fun writeBaked(pkg: String, bitmap: Bitmap, hitLevel: String) {
        try {
            val dir = bakeDir()
            val pngFile = File(dir, "$pkg.png")
            FileOutputStream(pngFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pngFile.setReadable(true, false) // World-readable for SystemUI
            val metaFile = File(dir, "$pkg.meta")
            metaFile.writeText(hitLevel, Charsets.UTF_8)
            metaFile.setReadable(true, false)
            // Production delivery: publish to shared Downloads dir (SystemUI hook reads there)
            io.github.deserthouse.opticon.engine.SharedIconStore.mirrorIconToShared(getApplication(), pngFile, pkg)
        } catch (_: Exception) {}
    }
    private fun deleteBaked(pkg: String) { runCatching { File(bakeDir(), "$pkg.png").delete() }; runCatching { File(bakeDir(), "$pkg.meta").delete() }; runCatching { io.github.deserthouse.opticon.engine.SharedIconStore.deleteIcon(getApplication(), pkg) } }

    private fun loadSavedParams(pkg: String) = RedrawParams(PreferenceManager.getScale(pkg), PreferenceManager.getOffsetX(pkg), PreferenceManager.getOffsetY(pkg), PreferenceManager.getThreshold(pkg), PreferenceManager.getRadius(pkg))

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
