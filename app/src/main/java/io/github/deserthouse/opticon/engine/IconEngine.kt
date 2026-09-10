package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import io.github.deserthouse.opticon.util.PreferenceManager.IconMethod
import io.github.deserthouse.opticon.util.PreferenceManager.ManualBranch
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File

/**
 * IconEngine —— Icon baking core engine (5-strategy parallel)
 *
 * UI process bakes —— writes filesDir/baked/.
 * SystemUI Hook reads via IconContentProvider.
 */
object IconEngine {
    private const val TAG = "OptIcon/IconEngine"
    const val OUTPUT_SIZE = 96

    fun bakedPngFile(filesDir: File, pkg: String): File =
        File(File(filesDir, "baked").apply { mkdirs() }, "$pkg.png")

    fun bakedMetaFile(filesDir: File, pkg: String): File =
        File(File(filesDir, "baked").apply { mkdirs() }, "$pkg.meta")

    data class BakeResult(val bitmap: Bitmap?, val hitLevel: String)

    /**
     * Main bake entry. selectedIconPack only used when method==ICON_PACK.
     */
    fun bake(
        context: Context, packageName: String, method: IconMethod,
        branch: ManualBranch?, params: RedrawParams,
        localPath: String?, materialIconName: String?, emojiText: String?,
        selectedIconPack: String? = null,
        selectedPackIconDrawable: String? = null
    ): BakeResult {
        return when (method) {
            IconMethod.AUTO -> bakeAuto(context, packageName, params)
            IconMethod.REMOTE_SUB -> bakeRemote(packageName)
            IconMethod.ICON_PACK -> bakeIconPack(context, packageName, params, selectedIconPack, selectedPackIconDrawable)
            IconMethod.ADAPTIVE -> bakeAdaptive(context, packageName, params)
            IconMethod.SELF_FILTER -> bakeSelfFilter(context, packageName, params)
            IconMethod.MANUAL -> bakeManual(context, branch, params, localPath, materialIconName, emojiText)
        }
    }

    private fun bakeAuto(context: Context, pkg: String, params: RedrawParams): BakeResult {
        val lib = bakeRemote(pkg)
        if (lib.bitmap != null) return lib
        val pack = bakeIconPack(context, pkg, params, null, null)
        if (pack.bitmap != null) return pack
        val adaptive = bakeAdaptive(context, pkg, params)
        if (adaptive.bitmap != null) return adaptive
        return BakeResult(null, "AUTO: all levels missed (remote/iconpack/adaptive)")
    }

    private fun bakeRemote(pkg: String): BakeResult {
        val icon = IconLibEngine.lookup(pkg)
        return if (icon != null) BakeResult(icon, "Remote subscription hit")
        else BakeResult(null, "Remote subscription miss")
    }

    private fun bakeIconPack(context: Context, pkg: String, params: RedrawParams, selectedPack: String?, selectedDrawable: String?): BakeResult {
        // If user manually picked a drawable from the icon grid, use it directly
        if (selectedDrawable != null && selectedPack != null) {
            return bakeIconPackDrawable(context, pkg, selectedPack, selectedDrawable, params)
        }
        // Auto-match: try each installed pack
        val packs = IconPackEngine.listInstalledIconPacks(context)
        val target = if (selectedPack != null) packs.filter { it.packageName == selectedPack } else packs
        for (pack in target) {
            val result = IconPackEngine.extractForPackage(context, pack.packageName, pkg)
            if (result != null) return BakeResult(result, "Icon pack hit: ${pack.label}")
        }
        return BakeResult(null, "Icon pack miss")
    }

    private fun bakeIconPackDrawable(context: Context, targetPkg: String, iconPackPkg: String, drawableName: String, params: RedrawParams): BakeResult {
        try {
            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val drawableId = packRes.getIdentifier(drawableName, "drawable", iconPackPkg)
            if (drawableId == 0) return BakeResult(null, "Icon pack drawable not found: $drawableName")
            val drawable = packRes.getDrawable(drawableId, null) ?: return BakeResult(null, "Icon pack drawable null")
            val foreground = if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
                drawable.foreground
            } else {
                drawable
            }
            val bitmap = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            foreground.setBounds(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
            foreground.draw(canvas)
            val white = IconTint.toWhite(bitmap) ?: return BakeResult(null, "Icon pack tint failed")
            if (bitmap !== white) bitmap.recycle()
            val scaled = IconTint.scaleAndTintWhite(white, params.scale.coerceIn(0.5f, 1.0f), OUTPUT_SIZE)
                ?: white
            return BakeResult(scaled, "Icon pack manual: $drawableName")
        } catch (e: Exception) {
            return BakeResult(null, "Icon pack manual error: ${e.message}")
        }
    }

    private fun bakeAdaptive(context: Context, pkg: String, params: RedrawParams): BakeResult {
        val result = AdaptiveIconExtractor.extractForegroundWhite(context, pkg)
        return if (result != null) BakeResult(result, "Adaptive icon extracted")
        else BakeResult(null, "Adaptive icon miss")
    }

    private fun bakeSelfFilter(context: Context, pkg: String, params: RedrawParams): BakeResult {
        val base = resolveAppBitmap(context, pkg) ?: return BakeResult(null, "Self-filter: cannot get app icon")
        val effParams = if (params.scale == RedrawParams.DEFAULT_SCALE) params.copy(scale = 0.8f) else params
        val result = IconRedrawEngine.redraw(base, effParams)
        if (base !== result) base.recycle()
        return if (result != null) BakeResult(result, "Self-filter: four-corner sampling")
        else BakeResult(null, "Self-filter: processing failed")
    }

    private fun bakeManual(
        context: Context, branch: ManualBranch?, params: RedrawParams,
        localPath: String?, materialIconName: String?, emojiText: String?
    ): BakeResult {
        return when (branch) {
            ManualBranch.LOCAL_FILE -> {
                if (localPath.isNullOrBlank()) return BakeResult(null, "Manual: no file selected")
                val src = decodeFileOrUri(context, localPath) ?: return BakeResult(null, "Manual: file decode failed")
                val white = IconTint.toWhite(src) ?: return BakeResult(null, "Manual: tint failed")
                if (src !== white) src.recycle()
                BakeResult(white, "Manual: local upload")
            }
            ManualBranch.MATERIAL_LIB -> {
                if (materialIconName.isNullOrBlank()) return BakeResult(null, "Manual: no icon selected")
                val icon = MaterialIconRenderer.findByName(materialIconName)
                    ?: return BakeResult(null, "Manual: icon not found")
                val bmp = MaterialIconRenderer.renderToWhiteBitmap(icon)
                BakeResult(bmp, "Manual: Material icon library")
            }
            ManualBranch.EMOJI_TEXT -> {
                if (emojiText.isNullOrBlank()) return BakeResult(null, "Manual: no text entered")
                val bmp = EmojiRenderer.renderToWhiteBitmap(emojiText)
                if (bmp != null) BakeResult(bmp, "Manual: Emoji text")
                else BakeResult(null, "Manual: Emoji render failed")
            }
            null -> BakeResult(null, "Manual: no branch selected")
        }
    }

    /** Decode a file path OR content:// URI to Bitmap */
    private fun decodeFileOrUri(context: Context, path: String): Bitmap? {
        return if (path.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
        } else {
            BitmapFactory.decodeFile(path)
        }
    }

    private fun resolveAppBitmap(context: Context, pkg: String): Bitmap? {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val drawable = pm.getApplicationIcon(ai)
            val bmp = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) { TraceLogger.w(TAG, "resolveAppBitmap($pkg): ${e.message}"); null }
    }

    fun hasLibIcon(pkgName: String): Boolean = IconLibEngine.hasIcon(pkgName)

    /** Bake an icon pack drawable directly (for manual selection from icon grid). Used by ViewModel directly. */
    fun bakeIconPackViaDrawable(context: Context, targetPkg: String, iconPackPkg: String, drawableName: String, params: RedrawParams): BakeResult {
        return try {
            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val drawableId = packRes.getIdentifier(drawableName, "drawable", iconPackPkg)
            if (drawableId == 0) return BakeResult(null, "Icon pack: drawable not found")
            val drawable = packRes.getDrawable(drawableId, null) ?: return BakeResult(null, "Icon pack: drawable null")
            val foreground = if (drawable is android.graphics.drawable.AdaptiveIconDrawable) drawable.foreground else drawable
            val bitmap = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            foreground.setBounds(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
            foreground.draw(canvas)
            val white = IconTint.toWhite(bitmap) ?: return BakeResult(null, "Icon pack: tint failed")
            if (bitmap !== white) bitmap.recycle()
            val scaled = IconTint.scaleAndTintWhite(white, params.scale.coerceIn(0.5f, 1.0f), OUTPUT_SIZE) ?: white
            BakeResult(scaled, "Icon pack manual: $drawableName")
        } catch (e: Exception) { BakeResult(null, "Icon pack manual error: ${e.message}") }
    }

    /**
     * 拆解原生 AdaptiveIcon 为前景层 (Logo) 与背景层 (底色) 两个 Bitmap。
     * 非自适应图标返回 null。
     */
    fun decomposeAdaptiveIcon(context: Context, pkg: String): Pair<Bitmap, Bitmap>? {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon = pm.getApplicationIcon(ai)
            if (icon !is android.graphics.drawable.AdaptiveIconDrawable) return null
            val fg = drawableToBitmap(icon.foreground, OUTPUT_SIZE)
            try {
                val bg = drawableToBitmap(icon.background, OUTPUT_SIZE)
                Pair(fg, bg)
            } catch (e: Exception) {
                fg.recycle()
                null
            }
        } catch (e: Exception) { null }
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }
}
