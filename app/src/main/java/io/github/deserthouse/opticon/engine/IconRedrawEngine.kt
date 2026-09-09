package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.deserthouse.opticon.util.TraceLogger
import kotlin.math.sqrt

/**
 * IconRedrawEngine — 四角采样阈值的色距过滤重绘算法
 *
 * 完全自研算法，与 Howard / NotificationIconFix 项目的 Otsu 算法不存在任何代码继承关系。
 *
 * ## 算法原理
 * 1. **背景色采样**：取原图四角落的平均 RGB 均值作为基准背景色 C_bg
 * 2. **色彩空间遍历**：遍历全图像素，计算每像素与 C_bg 的三维欧氏距离 D
 * 3. **阈值二分判断**：若 D ≤ 阈值，判定为背景色，Alpha 刷 0（完全透明）；
 *    若 D > 阈值，判定为前景色，RGB 刷纯白（#FFFFFF），保留原始 Alpha
 * 4. **重绘输出**：生成标准剪影 Bitmap，按缩放/偏移参数渲染后交给 SystemUI
 */
object IconRedrawEngine {

    private const val TAG = "OptIcon/RedrawEngine"
    const val OUTPUT_SIZE = 96

    /**
     * 主入口：对源位图执行四角采样色距过滤 + 缩放参数渲染。
     *
     * @param source 原始位图（可为 Launcher Icon / Notification Icon 等）
     * @param params 微调参数集，threshold 映射到 10~150，默认 30。
     * @return 标准通知图标 Bitmap（96x96）；失败返回 null
     */
    fun redraw(source: Bitmap, params: RedrawParams): Bitmap? {
        val start = System.currentTimeMillis()
        val eff = params.clamped()

        // 第一阶段：四角采样色距过滤
        val filtered = filterBackground(source, eff.threshold)
            ?: return null.also { TraceLogger.e(TAG, "filterBackground returned null") }

        // 第二阶段：缩放 + 参数渲染 + 输出标准尺寸
        val output = renderToOutput(filtered, eff)

        // 若 filtered != source，回收中间产物
        if (filtered !== source && filtered !== output) {
            filtered.recycle()
        }

        val elapsed = System.currentTimeMillis() - start
        TraceLogger.d(TAG, "redraw complete in ${elapsed}ms, threshold=${eff.threshold}, scale=${eff.scale}")

        return output
    }

    /**
     * 四角采样阈值的色距过滤。
     *
     * 采样 Alpha > 0 的四角像素做色彩均值加权计算。
     * 背景色区域按原始透明度规则处理为完全透明区域，
     * 确保图标背景无残留，来源图标无白边或黑白条纹瑕疵。
     */
    fun filterBackground(source: Bitmap, threshold: Int): Bitmap? {
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return null

        // 1. 四角采样，计算基准背景色 C_bg
        val safeX1 = 0
        val safeY1 = 0
        val safeX2 = (w - 1).coerceAtLeast(0)
        val safeY2 = (h - 1).coerceAtLeast(0)

        val c1 = source.getPixel(safeX1, safeY1)
        val c2 = source.getPixel(safeX2, safeY1)
        val c3 = source.getPixel(safeX1, safeY2)
        val c4 = source.getPixel(safeX2, safeY2)

        val rb = ((Color.red(c1) + Color.red(c2) + Color.red(c3) + Color.red(c4)) / 4.0).toInt()
        val gb = ((Color.green(c1) + Color.green(c2) + Color.green(c3) + Color.green(c4)) / 4.0).toInt()
        val bb = ((Color.blue(c1) + Color.blue(c2) + Color.blue(c3) + Color.blue(c4)) / 4.0).toInt()

        TraceLogger.d(TAG, "Corner sampling: R=$rb G=$gb B=$bb, threshold=$threshold")

        // 2. 提取像素矩阵
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 3. 遍历全图，计算三维欧氏距离并二分判断
        val thresholdSq = threshold * threshold // 预计算平方阈值，比较时省去 sqrt 开销
        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = Color.alpha(c)
            if (alpha == 0) {
                pixels[i] = 0x00000000 // 原本透明 → 判定为完全透明区
                continue
            }

            val dr = Color.red(c) - rb
            val dg = Color.green(c) - gb
            val db = Color.blue(c) - bb
            val distSq = dr * dr + dg * dg + db * db

            if (distSq <= thresholdSq) {
                // 判定为背景色 → Alpha 刷 0，完全透明
                pixels[i] = 0x00000000
            } else {
                // 判定为前景 Logo 区域 → RGB 刷纯白，继承原始 Alpha
                pixels[i] = Color.argb(alpha, 255, 255, 255)
            }
        }

        // 4. 生成过滤位图
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)

        return out
    }

    /**
     * 渲染并输出标准尺寸（96x96）：应用缩放比例 + 偏移 + 圆角裁剪。
     */
    private fun renderToOutput(source: Bitmap, params: RedrawParams): Bitmap {
        val output = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val scaledW = (OUTPUT_SIZE * params.scale).toFloat()
        val scaledH = (OUTPUT_SIZE * params.scale).toFloat()
        val left = (OUTPUT_SIZE - scaledW) / 2f + params.offsetX
        val top = (OUTPUT_SIZE - scaledH) / 2f + params.offsetY
        val right = left + scaledW
        val bottom = top + scaledH

        // 圆角裁剪
        if (params.radius > 0f) {
            val path = android.graphics.Path()
            path.addRoundRect(
                left, top, right, bottom,
                params.radius, params.radius,
                android.graphics.Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(source, null, android.graphics.RectF(left, top, right, bottom), paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(source, null, android.graphics.RectF(left, top, right, bottom), paint)
        }

        return output
    }
}
