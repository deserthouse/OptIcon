package io.github.deserthouse.opticon.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import io.github.deserthouse.opticon.util.TraceLogger

/**
 * AdaptiveIconExtractor — 本机自适应图标提取 (方案3)
 *
 * 获取目标应用自身的 Launcher Icon，若支持 AdaptiveIconDrawable：
 * 剥离 foreground → Tint 纯白 → 居中缩放。
 *
 * 原理：Android 8+ 应用常使用 AdaptiveIcon（含 background + foreground 两层），
 * foreground 层即核心 Logo 形状，剥离后 Tint 为纯白通知图标。
 */
object AdaptiveIconExtractor {

    private const val TAG = "OptIcon/AdaptiveIcon"

    /**
     * 提取目标应用自适应图标的前景层 → 纯白通知图标。
     *
     * @param context    任意 context（用于 PackageManager）
     * @param targetPkg  目标应用包名
     * @return 纯白合规通知图标 (96px)；若目标应用无 AdaptiveIcon 或无 launcher icon 返回 null
     */
    fun extractForegroundWhite(context: Context, targetPkg: String): Bitmap? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(targetPkg, 0)
            val icon = pm.getApplicationIcon(appInfo)

            if (icon !is AdaptiveIconDrawable) {
                TraceLogger.d(TAG, "$targetPkg has non-adaptive icon, fallback")
                return null
            }

            val foreground = icon.foreground
            val bitmap = drawableToBitmap(foreground, 96)
            val white = IconTint.toWhite(bitmap)
            if (bitmap !== white) bitmap.recycle()
            if (white == null) return null

            // 默认缩放因子 0.60~0.70（AdaptiveIcon foreground 通常是镂空 Logo，贴边显示尚可）
            val result = IconTint.scaleAndTintWhite(white, 0.65f, 96)
            if (result !== white) white.recycle()
            TraceLogger.d(TAG, "Extracted adaptive foreground for $targetPkg")
            result
        } catch (e: Exception) {
            TraceLogger.w(TAG, "extractForegroundWhite($targetPkg): ${e.message}")
            null
        }
    }

    private fun drawableToBitmap(
        drawable: android.graphics.drawable.Drawable,
        size: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** Quick check: does the app have an AdaptiveIcon? */
    fun hasAdaptiveIcon(context: Context, pkg: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationIcon(appInfo) is AdaptiveIconDrawable
        } catch (e: Exception) { false }
    }

}
