package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * IconTint —— Pure white monochrome Tint utility 
 *
 * Forces all visible pixels of any icon (preserving Alpha) to pure white #FFFFFF.
 * Output strictly complies with AOSP notification bar monochrome spec.
 */
object IconTint {

    const val OUTPUT_SIZE = 96
    private const val WHITE_RGB = 0x00FFFFFF

    fun toWhite(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in 0 until total) {
            val a = (pixels[i] ushr 24) and 0xFF
            pixels[i] = if (a > 0) (a shl 24) or WHITE_RGB else 0
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun scaleAndTintWhite(src: Bitmap, factor: Float, outSize: Int = OUTPUT_SIZE): Bitmap? {
        if (factor <= 0f || factor > 2f) return toWhite(src)

        val dstSize = (outSize * factor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, dstSize, dstSize, true)
        val white = toWhite(scaled)
        if (scaled !== white) scaled.recycle()
        if (white == null) return null

        val output = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val cx = (outSize - dstSize) / 2
        val cy = (outSize - dstSize) / 2
        Canvas(output).drawBitmap(white, cx.toFloat(), cy.toFloat(), Paint())
        white.recycle()
        return output
    }
}
