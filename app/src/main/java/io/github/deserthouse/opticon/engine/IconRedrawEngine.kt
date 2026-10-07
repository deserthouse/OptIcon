package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.deserthouse.opticon.util.TraceLogger
import kotlin.math.abs
import kotlin.math.max

/**
 * IconRedrawEngine — 色距过滤重绘算法（策略三"兼容重绘"核心）
 *
 * 完全自研算法，与 Howard / NotificationIconFix 项目的 Otsu 算法不存在任何代码继承关系。
 *
 * ## 算法原理（M1 主色键控 + M3 分级路由，2026-10-05 策略三黑盒化批次）
 * 1. **输入分级路由**（实验台 135 对真实语料实证，见 tests/redraw_harness.py）：
 *    - `TRANSPARENT_MARGIN`（不透明角 ≤1，画风即 alpha 定义）：**alpha 白化**通路——
 *      保 alpha 刷白，不做色彩键控。主色键控对该类方向性错误（dominant=glyph 自身，
 *      键控=毁图，靠守卫才拦住）；
 *    - `FLAT` / `MASK_LIKE`（实色/双色底，角色平滑）：**角采样键控**——≥3 不透明角
 *      RGB 均值（修复旧版透明角 (0,0,0) 偏置）；
 *    - `GRADIENT`（环端差大 & 相邻差小）：**双线性角点背景模型键控**——四角色双线性
 *      插值出逐像素背景，线性渐变精确命中；残余复杂渐变由守卫诚实拒绝。
 * 2. **色距键控**：逐像素与背景模型的欧氏距离，≤阈值判背景（透明），否则刷白保留 Alpha。
 * 3. **覆盖率守卫（#18 移植）**：前景占比 >90%（白矩形垃圾）或 <1%（滤空）→ 拒绝输出。
 *    调用方收到 [RedrawOutcome.Rejected] 必须按失败处理（诚实引导换源）。
 *
 * 注：#18 的 88% 安全圈裁剪是图标包合成图专属，用户上传物前景可能触边，故不移植。
 */
object IconRedrawEngine {

    private const val TAG = "OptIcon/RedrawEngine"
    const val OUTPUT_SIZE = 96

    /** 前景占比守卫上限：背景基本没被滤掉（白矩形垃圾） */
    internal const val FG_COVERAGE_MAX = 0.90f

    /** 前景占比守卫下限：前景被滤光（空图标） */
    internal const val FG_COVERAGE_MIN = 0.01f

    private const val OPAQUE_ALPHA = 128

    enum class BgEstimateMode { CORNER_SAMPLE, DOMINANT_COLOR, BILINEAR_GRADIENT, ALPHA_SILHOUETTE }

    enum class InputClass { FLAT, MASK_LIKE, GRADIENT, TRANSPARENT_MARGIN }

    /** 重绘结果：Ok 携带输出与诊断量；Rejected = 守卫拒绝，调用方必须按失败处理 */
    sealed interface RedrawOutcome {
        data class Ok(val bitmap: Bitmap, val fgCoverage: Float, val bgMode: BgEstimateMode) : RedrawOutcome
        data class Rejected(val reason: String, val fgCoverage: Float) : RedrawOutcome
    }

    internal data class BgEstimate(val rgb: Int, val mode: BgEstimateMode)

    /**
     * 主入口：输入分类 → 分级路由（alpha 白化 / 角采样键控 / 双线性键控）→ 覆盖率守卫 → 渲染。
     */
    fun redraw(source: Bitmap, params: RedrawParams): RedrawOutcome {
        val start = System.currentTimeMillis()
        val eff = params.clamped()

        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return RedrawOutcome.Rejected("empty source", 0f)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val cls = classifyInput(pixels, w)
        val keyed: IntArray = when (cls) {
            InputClass.TRANSPARENT_MARGIN -> alphaWhiten(pixels)
            InputClass.GRADIENT -> keyBilinearGradient(pixels, w, eff.threshold)
                ?: return RedrawOutcome.Rejected("gradient corners not opaque enough for background model", 0f)
            else -> {
                val bg = estimateBackground(pixels, w)
                    ?: return RedrawOutcome.Rejected("source has no opaque pixels to estimate background", 0f)
                keyBackground(pixels, bg.rgb, eff.threshold)
            }
        }

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

        val mode = when (cls) {
            InputClass.TRANSPARENT_MARGIN -> BgEstimateMode.ALPHA_SILHOUETTE
            InputClass.GRADIENT -> BgEstimateMode.BILINEAR_GRADIENT
            else -> BgEstimateMode.CORNER_SAMPLE
        }

        val filtered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        filtered.setPixels(keyed, 0, w, 0, 0, w, h)
        val output = renderToOutput(filtered, eff)
        if (filtered !== output) filtered.recycle()

        TraceLogger.d(
            TAG,
            "redraw ok in ${System.currentTimeMillis() - start}ms, cls=$cls, mode=$mode, coverage=$coverage, threshold=${eff.threshold}"
        )
        return RedrawOutcome.Ok(output, coverage, mode)
    }

    /**
     * 输入分级（实验台同款判据，逐条对应 tests/redraw_harness.py classify_input）：
     * 透明角 ≤1 → TRANSPARENT_MARGIN；边缘环端差大且逐边平滑 → GRADIENT；
     * 不透明 >95% 且 ≤2 色 → MASK_LIKE；其余 → FLAT。
     */
    internal fun classifyInput(pixels: IntArray, width: Int): InputClass {
        val h = if (width == 0) 0 else pixels.size / width
        if (width == 0 || h == 0) return InputClass.FLAT

        val corners = intArrayOf(pixels[0], pixels[width - 1], pixels[(h - 1) * width], pixels[pixels.size - 1])
        val opaqueCorners = corners.count { (it ushr 24) and 0xFF > OPAQUE_ALPHA }
        if (opaqueCorners <= 1) return InputClass.TRANSPARENT_MARGIN

        fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

        // 边缘环：每边 24 点、逐边连续排列（交错排列会让边间接缝污染平滑度）
        val step = max(1, width / 24)
        val stepH = max(1, h / 24)
        val edges = listOf(
            (0 until width step step).map { it to 0 },
            (0 until width step step).map { it to (h - 1) },
            (0 until h step stepH).map { 0 to it },
            (0 until h step stepH).map { (width - 1) to it }
        )
        var spread = 0
        var smooth = 0
        val ring = ArrayList<Triple<Int, Int, Int>>(96)
        for (edge in edges) {
            var prev: Triple<Int, Int, Int>? = null
            for ((x, y) in edge) {
                val p = pixels[y * width + x]
                if ((p ushr 24) and 0xFF <= OPAQUE_ALPHA) continue
                val c = rgb(p)
                for (q in ring) {
                    spread = maxOf(spread, abs(c.first - q.first) + abs(c.second - q.second) + abs(c.third - q.third))
                }
                prev?.let {
                    smooth = maxOf(smooth, abs(c.first - it.first) + abs(c.second - it.second) + abs(c.third - it.third))
                }
                ring.add(c)
                prev = c
            }
        }
        if (ring.size >= 8 && spread > 60 && smooth < 30) return InputClass.GRADIENT

        var opaque = 0
        val colors = HashSet<Int>()
        for (p in pixels) {
            if ((p ushr 24) and 0xFF > OPAQUE_ALPHA) {
                opaque++
                colors.add(p and 0x00FFFFFF)
            }
        }
        if (opaque.toFloat() / pixels.size > 0.95f && colors.size <= 2) return InputClass.MASK_LIKE
        return InputClass.FLAT
    }

    /**
     * 背景色估计（FLAT/MASK 用）：≥3 个不透明角 → 角均值；否则全图主色统计；
     * 全图无不透明像素 → null。
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

    /** alpha 白化（TRANSPARENT_MARGIN 通路）：保 alpha 刷白，无色彩键控 */
    internal fun alphaWhiten(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            out[i] = if (a == 0) 0 else (a shl 24) or 0x00FFFFFF
        }
        return out
    }

    /**
     * 双线性角点背景模型键控（GRADIENT 通路）：四角色双线性插值出逐像素背景。
     * 任一角不透明则可行；全透明角则无法建模返回 null。
     */
    internal fun keyBilinearGradient(pixels: IntArray, width: Int, threshold: Int): IntArray? {
        val h = pixels.size / width
        val w1 = width - 1
        val h1 = h - 1
        val c00 = pixels[0]; val c10 = pixels[w1]
        val c01 = pixels[h1 * width]; val c11 = pixels[pixels.size - 1]
        val anyOpaque = listOf(c00, c10, c01, c11).any { (it ushr 24) and 0xFF > OPAQUE_ALPHA }
        if (!anyOpaque) return null

        fun chan(c: Int, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        val thresholdSq = threshold * threshold.toFloat()
        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            val v = if (h1 == 0) 0f else y.toFloat() / h1
            for (x in 0 until width) {
                val i = y * width + x
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                val u = if (w1 == 0) 0f else x.toFloat() / w1
                val br = chan(c00, 16) * (1 - u) * (1 - v) + chan(c10, 16) * u * (1 - v) +
                    chan(c01, 16) * (1 - u) * v + chan(c11, 16) * u * v
                val bgc = chan(c00, 8) * (1 - u) * (1 - v) + chan(c10, 8) * u * (1 - v) +
                    chan(c01, 8) * (1 - u) * v + chan(c11, 8) * u * v
                val bb = chan(c00, 0) * (1 - u) * (1 - v) + chan(c10, 0) * u * (1 - v) +
                    chan(c01, 0) * (1 - u) * v + chan(c11, 0) * u * v
                val dr = ((c shr 16) and 0xFF) - br
                val dg = ((c shr 8) and 0xFF) - bgc
                val db = (c and 0xFF) - bb
                out[i] = if (dr * dr + dg * dg + db * db <= thresholdSq) 0
                else (a shl 24) or 0x00FFFFFF
            }
        }
        return out
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
