package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap

/**
 * Shared icon normalization for asset-import strategies (PICP / adaptive decompose).
 *
 * Android adaptive/layered icons usually leave a lot of transparent padding around
 * the actual logo. Trimming that padding and scaling the content to a consistent
 * output size makes previews look uniform and avoids tiny foreground blobs.
 */
object IconNormalizer {

    const val OUTPUT_SIZE = 96

    /**
     * Trim transparent padding, then scale the content to fit inside
     * [targetSize] x [targetSize] while keeping the original aspect ratio.
     *
     * @param insetRatio final content is scaled to fill this ratio of the target
     *                   (default 0.9 = 10% margin on each axis), so the icon does
     *                   not touch the notification icon safe-zone edge.
     */
    fun normalize(bitmap: Bitmap, targetSize: Int = OUTPUT_SIZE, insetRatio: Float = 0.9f): Bitmap {
        val trimmed = trimTransparent(bitmap)
        val usable = (targetSize * insetRatio).toInt()
        val scale = minOf(usable.toFloat() / trimmed.width, usable.toFloat() / trimmed.height)
        val newW = (trimmed.width * scale).toInt().coerceAtLeast(1)
        val newH = (trimmed.height * scale).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(trimmed, newW, newH, true)
        if (trimmed !== bitmap) trimmed.recycle()
        return result
    }

    /** Crop bitmap to the bounding box of non-transparent pixels. */
    private fun trimTransparent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                val alpha = pixels[y * w + x] ushr 24
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX) return src // fully transparent
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
