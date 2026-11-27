package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * SvgRasterizer — 把上游的 SVG 图标渲染成 PNG（批 UP）。
 *
 * 用的是 AndroidSVG，和上游 SDK 同一个库（com.caverock:androidsvg-aar:1.4）——上游的图标本来就是按它出的。
 * 渲染只在同步时做一次，落盘之后下游（镜像、hook 取图）看到的都是普通 PNG。
 *
 * 约定：
 *  - 尺寸取 SVG 自己写的宽高；没写宽高（只有 viewBox）的按 [DEFAULT_SIZE] 出图——上游最常见的 PNG 是 72px；
 *  - 任一边超过调用方给的上限（上游的约定是 150px）→ 不渲染，调用方按「读不出」处理；
 *  - 颜色照 SVG 原样，不做重新着色：上游的图标有白有黑，OptIcon 对规则库里的 PNG 也是原样使用；
 *  - AndroidSVG 在 Android 上用 XmlPullParser 解析，不会去取 DOCTYPE 指向的外部 DTD；
 *    也没有注册外部文件解析器，所以 <image> 的外部引用不会被加载。
 *
 * 图形部分（Bitmap / Canvas）在 JVM 单测里是桩，测不到；能单测的是尺寸判断 [renderSize]。
 * 渲染本身在设备上验证（设备套件 + 截图核对）。
 */
internal object SvgRasterizer : AnipSnapshotBuilder.IconRasterizer {

    /** 没写宽高的 SVG 的出图尺寸（像素）。 */
    const val DEFAULT_SIZE = 72

    override fun renderPng(svg: ByteArray, maxDimension: Int): ByteArray? {
        return try {
            val document = SVG.getFromString(String(svg, Charsets.UTF_8))
            val size = renderSize(document.documentWidth, document.documentHeight, maxDimension) ?: return null
            val (width, height) = size
            val picture = document.renderToPicture(width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPicture(picture)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        } catch (_: Throwable) {
            // 解析不了、画不出来：交给调用方按「读不出」处理（沿用旧版本里的图标，或记缺失）
            null
        }
    }

    /** 出图尺寸。SVG 没给宽高（NaN、无穷、非正数）→ 按正方形 [DEFAULT_SIZE]；任一边不在 1..[maxDimension] 内 → null。 */
    fun renderSize(documentWidth: Float, documentHeight: Float, maxDimension: Int): Pair<Int, Int>? {
        val width = when {
            documentWidth.isNaN() || documentWidth.isInfinite() || documentWidth <= 0f -> DEFAULT_SIZE
            else -> documentWidth.roundToInt()
        }
        val height = when {
            documentHeight.isNaN() || documentHeight.isInfinite() || documentHeight <= 0f -> DEFAULT_SIZE
            else -> documentHeight.roundToInt()
        }
        return if (width in 1..maxDimension && height in 1..maxDimension) width to height else null
    }
}
