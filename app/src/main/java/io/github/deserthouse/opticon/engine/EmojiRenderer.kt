package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * EmojiRenderer — 文本/Emoji 字符转纯白通知图标 (方案5-C 彩蛋)
 *
 * 在空白 Canvas 上用 TextPaint 渲染单字或 Emoji，将非透明像素 Tint 纯白。
 * 输出为纯白剪影（所有色彩丢失，符合"细节丢失"预期）。
 *
 * 彩蛋默认隐藏，需在设置页关于区连击版本号 7 次解锁。
 */
object EmojiRenderer {

    /** 输出尺寸 (px) */
    const val OUTPUT_SIZE = 96

    /**
     * 将任意字符 / Emoji 渲染为纯白剪影通知图标。
     *
     * @param text     单个字符或 Emoji（多字符自动取首字）
     * @param size     输出正方形边长 px
     * @return 纯白剪影位图
     */
    fun renderToWhiteBitmap(text: String, size: Int = OUTPUT_SIZE): Bitmap? {
        val ch = text.firstOrNull()?.toString() ?: return null

        val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()  // 先用纯黑绘制，后续再 Tint
            textSize = size * 0.8f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        // 垂直居中基线计算
        val metrics = paint.fontMetrics
        val centerY = size / 2f - (metrics.ascent + metrics.descent) / 2f

        val drawing = ch.ifEmpty { return null }
        c.drawText(drawing, size / 2f, centerY, paint)

        return IconTint.toWhite(canvas)
    }
}
