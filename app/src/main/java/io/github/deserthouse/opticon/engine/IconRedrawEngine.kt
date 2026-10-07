package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.deserthouse.opticon.util.TraceLogger

/**
 * IconRedrawEngine — 色距过滤重绘算法（策略三"兼容重绘"核心）
 *
 * 完全自研算法，与 Howard / NotificationIconFix 项目的 Otsu 算法不存在任何代码继承关系。
 *
 * ## 算法原理（M1，2026-10-05 策略三黑盒化批次重写）
 * 1. **背景色估计（双模式）**：
 *    - `CORNER_SAMPLE`：≥3 个不透明角 → 不透明角 RGB 均值。修复旧版把透明角的
 *      (0,0,0) 一并均值进背景导致暗色前景被误滤的偏置（白色圆角矩形事故根因之一）；
 *    - `DOMINANT_COLOR`：不透明角不足（透明边距类图标）→ 全图不透明像素 32 级量化
 *      主色统计（#18 glyphKeyWhite 同款），不再依赖角落。
 * 2. **色距键控**：逐像素与 C_bg 的三维欧氏距离，≤阈值判背景（透明），否则刷白保留 Alpha。
 * 3. **覆盖率守卫（#18 移植）**：键控后前景占比 >90% = 背景根本没滤掉（白矩形垃圾）→
 *    拒绝输出；<1% = 前景被滤光（空图标）→ 拒绝输出。调用方收到 [RedrawOutcome.Rejected]
 *    必须按失败处理（诚实引导换源），禁止回退到未过滤原图。
 *
 * 注：#18 的 88% 安全圈裁剪是图标包合成图专属（glyph 居中于安全区），用户上传物前景
 * 可能触边，故**不**移植进本引擎。
 */
object IconRedrawEngine {

    private const val TAG = "OptIcon/RedrawEngine"
    const val OUTPUT_SIZE = 96

    /** 前景占比守卫上限：背景基本没被滤掉（白矩形垃圾） */
    internal const val FG_COVERAGE_MAX = 0.90f

    /** 前景占比守卫下限：前景被滤光（空图标） */
    internal const val FG_COVERAGE_MIN = 0.01f

    private const val OPAQUE_ALPHA = 128

    enum class BgEstimateMode { CORNER_SAMPLE, DOMINANT_COLOR }

    /** 重绘结果：Ok 携带输出与诊断量；Rejected = 守卫拒绝，调用方必须按失败处理 */
    sealed interface RedrawOutcome {
        data class Ok(val bitmap: Bitmap, val fgCoverage: Float, val bgMode: BgEstimateMode) : RedrawOutcome
        data class Rejected(val reason: String, val fgCoverage: Float) : RedrawOutcome
    }

    internal data class BgEstimate(val rgb: Int, val mode: BgEstimateMode)

    /**
     * 主入口：背景估计 → 色距键控 → 覆盖率守卫 → 缩放参数渲染。
     *
     * @return [RedrawOutcome.Ok]（96x96 标准通知图标）或 [RedrawOutcome.Rejected]（守卫拒绝）
     */
    fun redraw(source: Bitmap, params: RedrawParams): RedrawOutcome {
        val start = System.currentTimeMillis()
        val eff = params.clamped()

        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return RedrawOutcome.Rejected("empty source", 0f)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val bg = estimateBackground(pixels, w)
            ?: return RedrawOutcome.Rejected("source has no opaque pixels to estimate background", 0f)

        val keyed = keyBackground(pixels, bg.rgb, eff.threshold)
        val coverage = foregroundCoverage(keyed)

        if (coverage > FG_COVERAGE_MAX) {
            return RedrawOutcome.Rejected(
                "degenerate: background not removed (foreground ${(coverage * 100).toInt()}%) — source unsuited for redraw",
                coverage
            )
        }
        if (coverage < FG_COVERAGE_MIN) {
            return RedrawOutcome.Rejected("over-keyed: glyph keyed away entirely", coverage)
        }

        val filtered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        filtered.setPixels(keyed, 0, w, 0, 0, w, h)
        val output = renderToOutput(filtered, eff)
        if (filtered !== output) filtered.recycle()

        TraceLogger.d(
            TAG,
            "redraw ok in ${System.currentTimeMillis() - start}ms, mode=${bg.mode}, coverage=$coverage, threshold=${eff.threshold}"
        )
        return RedrawOutcome.Ok(output, coverage, bg.mode)
    }

    /**
     * 背景色估计：≥3 个不透明角 → 角均值（修复透明角偏置）；否则全图主色统计。
     * 全图无不透明像素（纯透明图）→ null（无法估计）。
     */
    internal fun estimateBackground(pixels: IntArray, width: Int): BgEstimate? {
        val h = if (width == 0) 0 else pixels.size / width
        if (width == 0 || h == 0) return null

        val corners = intArrayOf(
            pixels[0],
            pixels[width - 1],
            pixels[(h - 1) * width],
            pixels[pixels.size - 1]
        )
        val opaqueCorners = corners.filter { (it ushr 24) and 0xFF > OPAQUE_ALPHA }
        if (opaqueCorners.size >= 3) {
            val r = opaqueCorners.sumOf { (it shr 16) and 0xFF } / opaqueCorners.size
            val g = opaqueCorners.sumOf { (it shr 8) and 0xFF } / opaqueCorners.size
            val b = opaqueCorners.sumOf { it and 0xFF } / opaqueCorners.size
            return BgEstimate((r shl 16) or (g shl 8) or b, BgEstimateMode.CORNER_SAMPLE)
        }
        return dominantOpaqueColor(pixels)?.let { BgEstimate(it, BgEstimateMode.DOMINANT_COLOR) }
    }

    /** 全图不透明像素 32 级量化主色（#18 glyphKeyWhite 同款），返回桶中心代表色 */
    internal fun dominantOpaqueColor(pixels: IntArray): Int? {
        val buckets = HashMap<Int, Int>()
        for (p in pixels) {
            if ((p ushr 24) and 0xFF <= OPAQUE_ALPHA) continue
            val key = (((p shr 16) and 0xFF) / 32 shl 10) or (((p shr 8) and 0xFF) / 32 shl 5) or ((p and 0xFF) / 32)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val bgKey = buckets.maxByOrNull { it.value }?.key ?: return null
        val r = ((bgKey shr 10) and 0x1F) * 32 + 16
        val g = ((bgKey shr 5) and 0x1F) * 32 + 16
        val b = (bgKey and 0x1F) * 32 + 16
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * 色距键控：≤阈值判背景（透明），否则刷白保留原 Alpha。纯像素操作，JVM 可测。
     */
    internal fun keyBackground(pixels: IntArray, bgColor: Int, threshold: Int): IntArray {
        val br = (bgColor shr 16) and 0xFF
        val bg = (bgColor shr 8) and 0xFF
        val bb = bgColor and 0xFF
        val thresholdSq = threshold * threshold

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = (c ushr 24) and 0xFF
            if (alpha == 0) {
                out[i] = 0x00000000
                continue
            }
            val dr = ((c shr 16) and 0xFF) - br
            val dg = ((c shr 8) and 0xFF) - bg
            val db = (c and 0xFF) - bb
            val distSq = dr * dr + dg * dg + db * db
            out[i] = if (distSq <= thresholdSq) 0x00000000
            else (alpha shl 24) or 0x00FFFFFF
        }
        return out
    }

    /** 不透明（α≥128）像素占比 */
    internal fun foregroundCoverage(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var fg = 0
        for (p in pixels) if ((p ushr 24) and 0xFF >= OPAQUE_ALPHA) fg++
        return fg.toFloat() / pixels.size
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
