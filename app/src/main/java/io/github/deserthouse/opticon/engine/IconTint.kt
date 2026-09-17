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

    /**
     * Glyph keying for FLAT (composed) pack icons (#18): a full-bleed icon is
     * opaque everywhere, so a plain alpha silhouette degenerates into a solid
     * disc. Derive the glyph from color contrast against the icon's DOMINANT
     * fill color (mode of quantized opaque pixels), then drop components
     * connected to the border (the decorative outline ring). Soft edges via a
     * distance ramp. Returns null when keying is unreliable (glyph coverage
     * outside 3%..70%) — callers fall back rather than shipping a wrong disc.
     */
    fun glyphKeyWhite(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Dominant fill = mode of opaque colors quantized to 32-step buckets
        val buckets = HashMap<Int, Int>()
        for (p in pixels) {
            if ((p ushr 24) and 0xFF <= 128) continue
            val key = (((p shr 16) and 0xFF) / 32 shl 10) or (((p shr 8) and 0xFF) / 32 shl 5) or ((p and 0xFF) / 32)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        if (buckets.isEmpty()) return null
        val bgKey = buckets.maxByOrNull { it.value }!!.key
        val bgr = ((bgKey shr 10) and 0x1F) * 32 + 16
        val bgg = ((bgKey shr 5) and 0x1F) * 32 + 16
        val bgb = (bgKey and 0x1F) * 32 + 16

        // Distance ramp: <=T0 transparent, >=T1 opaque glyph
        val t0 = 40; val t1 = 100
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            if ((p ushr 24) and 0xFF == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val d = Math.sqrt(
                (r - bgr).toDouble() * (r - bgr) + (g - bgg).toDouble() * (g - bgg) + (b - bgb).toDouble() * (b - bgb)
            ).toFloat()
            val ga = when {
                d <= t0 -> 0
                d >= t1 -> 255
                else -> ((d - t0) / (t1 - t0) * 255f).toInt().coerceIn(0, 255)
            }
            out[i] = (ga shl 24) or WHITE_RGB
        }

        // Drop the decorative outline ring: it hugs the icon edge (inside a
        // small transparent margin, so border BFS can't reach it). Clear any
        // keyed-opaque pixel outside 88% of the half-diagonal — real glyphs
        // sit within the safe zone of composed pack icons.
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val maxR = Math.min(w, h) * 0.88f / 2f
        for (y in 0 until h) {
            val dy = (y - cy) * (y - cy)
            for (x in 0 until w) {
                val i = y * w + x
                val dx = (x - cx) * (x - cx)
                if (dx + dy > maxR * maxR) out[i] = 0
            }
        }

        val coverage = out.count { (it ushr 24) and 0xFF >= 128 }.toFloat() / (w * h)
        if (coverage < 0.03f || coverage > 0.70f) return null
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
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
