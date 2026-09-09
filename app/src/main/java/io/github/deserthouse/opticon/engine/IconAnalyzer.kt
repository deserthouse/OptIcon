package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Color

/**
 * IconAnalyzer — Icon compliance analyzer 
 *
 * Analyzes notification icons against AOSP compliance rules:
 *   - Must be 24x24dp (96x96px at 4x density)
 *   - Must be monochrome (only white + transparent alpha, no colored pixels)
 *   - Must have sufficient foreground coverage
 */
data class ComplianceReport(
    val isCompliant: Boolean,
    val sizeOk: Boolean,
    val colorOk: Boolean,
    val score: Int,
    val foregroundRatio: Float,
    val issues: List<String>
) {
    val isNonCompliant: Boolean get() = !isCompliant
}

data class AutoSuggestion(
    val suggestedScale: Float,
    val needsBackgroundStrip: Boolean,
    val reasoning: String
)

object IconAnalyzer {

    private const val TARGET_SIZE = 96
    private const val THRESHOLD_ALPHA = 0
    private const val COLOR_TOLERANCE = 15

    fun analyze(bitmap: Bitmap): ComplianceReport {
        val issues = mutableListOf<String>()

        val sizeOk = bitmap.width == TARGET_SIZE && bitmap.height == TARGET_SIZE
        if (!sizeOk) issues.add("Size is ${bitmap.width}x${bitmap.height}, expected ${TARGET_SIZE}x${TARGET_SIZE}")

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var colorOk = true
        var foregroundPixels = 0
        var totalVisiblePixels = 0

        for (p in pixels) {
            val alpha = Color.alpha(p)
            if (alpha <= THRESHOLD_ALPHA) continue
            totalVisiblePixels++

            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            val isWhite = r > (255 - COLOR_TOLERANCE) && g > (255 - COLOR_TOLERANCE) && b > (255 - COLOR_TOLERANCE)
            val isBlack = r < COLOR_TOLERANCE && g < COLOR_TOLERANCE && b < COLOR_TOLERANCE

            if (isWhite || isBlack) {
                foregroundPixels++
            } else {
                colorOk = false
            }
        }

        if (!colorOk) issues.add("Contains colored pixels (not pure white/black)")

        val totalPixelCount = bitmap.width * bitmap.height
        val foregroundRatio = if (totalPixelCount > 0) foregroundPixels.toFloat() / totalPixelCount else 0f

        val score = when {
            sizeOk && colorOk -> 100
            sizeOk && !colorOk -> 50
            !sizeOk && colorOk -> 50
            else -> 0
        }

        return ComplianceReport(
            isCompliant = sizeOk && colorOk && foregroundRatio > 0.01f,
            sizeOk = sizeOk,
            colorOk = colorOk,
            score = score,
            foregroundRatio = foregroundRatio,
            issues = issues
        )
    }

    fun autoSuggestion(bitmap: Bitmap): AutoSuggestion {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var hasColoredPixels = false
        var looksLikeFilledIcon = false

        for (p in pixels) {
            if (Color.alpha(p) == 0) continue
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            if (r > 30 || g > 30 || b > 30) {
                if (r != g || g != b || r < 220) {
                    hasColoredPixels = true
                }
            }
        }

        // Check if icon appears to be a filled shape (background color present)
        val corners = listOf(
            bitmap.getPixel(0, 0),
            bitmap.getPixel(bitmap.width - 1, 0),
            bitmap.getPixel(0, bitmap.height - 1),
            bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
        )
        val cornerAlphas = corners.map { Color.alpha(it) }
        looksLikeFilledIcon = cornerAlphas.all { it > 128 }

        return AutoSuggestion(
            suggestedScale = if (looksLikeFilledIcon) 0.8f else 1.0f,
            needsBackgroundStrip = hasColoredPixels || looksLikeFilledIcon,
            reasoning = when {
                hasColoredPixels -> "Icon contains colored pixels beyond pure white/black"
                looksLikeFilledIcon -> "Icon appears to have a filled background"
                else -> "Icon appears clean, no processing needed"
            }
        )
    }
}
