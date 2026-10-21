package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import io.github.deserthouse.opticon.data.AppIconConfig
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy

/**
 * BakeInputs — 统一烘焙入口的素材引用（阶段 3，架构报告 §3.3）
 *
 * 由调用方（VM）从 UI 状态解析后传入；引擎不持有 Compose 状态。
 * Bitmap 属于可重建的处理中间态（架构报告 §3.2）。
 */
data class BakeInputs(
    /** strategy=ASSET_IMPORT & sub=ADAPTIVE_DECOMPOSE 时的前景 Bitmap */
    val adaptiveForeground: Bitmap? = null,
    /** strategy=ASSET_IMPORT & sub=PERFECT_ICONS 时的已下载素材 */
    val perfectIconsBitmap: Bitmap? = null,
    /** hitSource 用于 PICP 产物标注来源（OI-15 修复后的正确引用） */
    val hitSource: HitSource = HitSource.OTHER
)

/**
 * 统一烘焙入口（阶段 3 核心，架构报告 §3.3）：
 * 预览与保存共用同一生产路径——同输入+同参数+同处理版本=同结果。
 *
 * 不读取 UI 状态；不执行偏好写入；不导航。
 * 所有路线的转白/键控/缩放均在此入口内完成后返回。
 */
fun bakeFromConfigEntry(
    context: Context,
    config: AppIconConfig,
    inputs: BakeInputs
): IconEngine.BakeResult {
    // R3-5：储备来源诚实守卫——Material/Emoji 渲染为占位实现且当前无 UI 入口，
    // legacy 配置不得静默产出占位图（detail 前缀走预览拒绝态/保存侧守卫通道）
    if (config.strategy == IconStrategy.ALGORITHM && config.algoSource == AlgoSource.MATERIAL_LIB) {
        return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "reserve guard: material icons renderer is a placeholder")
    }
    if (config.strategy == IconStrategy.ALGORITHM && config.algoSource == AlgoSource.EMOJI_TEXT) {
        return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "reserve guard: emoji renderer is a placeholder (UTF-16 incomplete)")
    }
    return when (config.strategy) {
        IconStrategy.FANKES -> bakeFankes(config.pkg)

        IconStrategy.ASSET_IMPORT -> when (config.assetSubStrategy) {
            AssetSubStrategy.ICON_PACK ->
                bakeIconPackRoute(context, config, inputs)
            AssetSubStrategy.ADAPTIVE_DECOMPOSE ->
                bakeAdaptiveRoute(config, inputs)
            AssetSubStrategy.PERFECT_ICONS ->
                bakePicpRoute(config, inputs)
            null -> IconEngine.BakeResult(null, HitSource.AUTO_MISS, "ASSET_IMPORT requires subStrategy")
        }

        IconStrategy.ALGORITHM -> when (config.algoSource) {
            AlgoSource.SELF -> IconEngine.bakeSelfFilter(context, config.pkg, config.params)
            AlgoSource.LOCAL_FILE ->
                bakeLocalFile(context, config, inputs)
            AlgoSource.MATERIAL_LIB ->
                bakeMaterialLib(config)
            AlgoSource.EMOJI_TEXT ->
                bakeEmojiText(config)
            null -> IconEngine.BakeResult(null, HitSource.AUTO_MISS, "ALGORITHM requires algoSource")
        }
    }
}

// ━━━ 私有路线实现（从 AppDetailViewModel.bakeCurrentState 逐条迁入） ━━━

private fun bakeFankes(pkg: String): IconEngine.BakeResult {
    val icon = IconLibEngine.lookup(pkg)
    return if (icon != null) IconEngine.BakeResult(icon, HitSource.ICONLIB)
    else IconEngine.BakeResult(null, HitSource.AUTO_MISS, "fankes rules miss")
}

private fun bakeIconPackRoute(context: Context, config: AppIconConfig, inputs: BakeInputs): IconEngine.BakeResult {
    // 手选手选路径
    if (!config.packDrawable.isNullOrBlank() && !config.iconPackPkg.isNullOrBlank()) {
        return IconEngine.bakeIconPackViaDrawable(
            context, config.pkg, config.iconPackPkg!!, config.packDrawable!!, config.params
        )
    }
    // 自动扫描路径：遍历已装图标包
    val packs = IconPackEngine.listInstalledIconPacks(context)
    val target = if (config.iconPackPkg != null) packs.filter { it.packageName == config.iconPackPkg } else packs
    var result: IconEngine.BakeResult? = null
    for (pack in target) {
        val r = IconPackEngine.extractForPackage(context, pack.packageName, config.pkg)
        if (r != null) { result = IconEngine.BakeResult(r, HitSource.ICONPACK, "${pack.label}"); break }
    }
    return result ?: IconEngine.BakeResult(null, HitSource.AUTO_MISS, "icon pack miss")
}

private fun bakeAdaptiveRoute(config: AppIconConfig, inputs: BakeInputs): IconEngine.BakeResult {
    val fg = inputs.adaptiveForeground
    return if (fg != null) {
        val white = IconEngine.bakeAdaptiveForeground(fg, config.params.scale)
        if (white != null) IconEngine.BakeResult(white, HitSource.ADAPTIVE, "decompose foreground")
        else IconEngine.BakeResult(null, HitSource.AUTO_MISS, "decompose tint failed")
    } else {
        IconEngine.BakeResult(null, HitSource.AUTO_MISS, "not an adaptive icon")
    }
}

private fun bakePicpRoute(config: AppIconConfig, inputs: BakeInputs): IconEngine.BakeResult {
    // OI-15: only the actual PICP asset may bake — never a generic preview cache.
    val cached = inputs.perfectIconsBitmap
    return if (cached != null) {
        val src = if (inputs.hitSource != HitSource.OTHER) inputs.hitSource else HitSource.PICP
        IconEngine.BakeResult(cached, src)
    } else {
        IconEngine.BakeResult(null, HitSource.AUTO_MISS, "picp not fetched")
    }
}

private fun bakeLocalFile(context: Context, config: AppIconConfig, inputs: BakeInputs): IconEngine.BakeResult {
    if (config.customIconPath.isNullOrBlank()) {
        return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: no file")
    }
    // OI-04: hand the ORIGINAL bitmap to the redraw engine — pre-whitening
    // destroys the bg/fg color distance the keying depends on.
    val src = try {
        // T5：解码预算——bake 路径与分类路径统一 inSampleSize（长边≤512），
        // 大图导入不再全尺寸解码（12MP≈48MB 位图→≤1MB）
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(config.customIconPath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeFile(config.customIconPath, opts)
            ?: decodeUri(context, config.customIconPath!!)
    } catch (_: Exception) { null }
    if (src == null) return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: file decode failed")

    return when (val outcome = IconRedrawEngine.redraw(src, config.params)) {
        is IconRedrawEngine.RedrawOutcome.Ok -> {
            src.recycle()
            IconEngine.BakeResult(outcome.bitmap, HitSource.MANUAL, "local file + redraw")
        }
        is IconRedrawEngine.RedrawOutcome.Rejected -> {
            src.recycle()
            IconEngine.BakeResult(null, HitSource.AUTO_MISS, "redraw guard: ${outcome.reason}")
        }
    }
}

private fun decodeUri(context: Context, path: String): Bitmap? = try {
    context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use {
        android.graphics.BitmapFactory.decodeStream(it)
    }
} catch (_: Exception) { null }

private fun bakeMaterialLib(config: AppIconConfig): IconEngine.BakeResult {
    if (config.materialIconName.isNullOrBlank()) {
        return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: no material icon")
    }
    val icon = MaterialIconRenderer.findByName(config.materialIconName)
        ?: return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: material icon not found")
    val bmp = MaterialIconRenderer.renderToWhiteBitmap(icon)
        ?: return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: material render failed")
    return when (val outcome = IconRedrawEngine.redraw(bmp, config.params)) {
        is IconRedrawEngine.RedrawOutcome.Ok -> {
            bmp.recycle()
            IconEngine.BakeResult(outcome.bitmap, HitSource.MANUAL, "material icon + redraw")
        }
        is IconRedrawEngine.RedrawOutcome.Rejected -> {
            bmp.recycle()
            IconEngine.BakeResult(null, HitSource.AUTO_MISS, "redraw guard: ${outcome.reason}")
        }
    }
}

private fun bakeEmojiText(config: AppIconConfig): IconEngine.BakeResult {
    if (config.emojiText.isNullOrBlank()) {
        return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: no emoji/text")
    }
    val bmp = EmojiRenderer.renderToWhiteBitmap(config.emojiText)
        ?: return IconEngine.BakeResult(null, HitSource.AUTO_MISS, "algo: emoji render failed")
    return when (val outcome = IconRedrawEngine.redraw(bmp, config.params)) {
        is IconRedrawEngine.RedrawOutcome.Ok -> {
            bmp.recycle()
            IconEngine.BakeResult(outcome.bitmap, HitSource.MANUAL, "emoji + redraw")
        }
        is IconRedrawEngine.RedrawOutcome.Rejected -> {
            bmp.recycle()
            IconEngine.BakeResult(null, HitSource.AUTO_MISS, "redraw guard: ${outcome.reason}")
        }
    }
}
