package io.github.deserthouse.opticon.engine

/**
 * IconLibEntry —— JSON deserialization target for NotifyIconsSupportConfig.json
 *
 * Only stores metadata + Base64 string (not decoded immediately).
 * Bitmap decoding is done lazily by IconLibEngine.
 */
data class IconLibEntry(
    val appName: String,
    val packageName: String,
    val iconBase64: String,
    val iconColor: String,
    val contributorName: String,
    val isEnabled: Boolean
) {
    fun estimatedBitmapSize(): Int {
        val pngBytes = (iconBase64.length * 3) / 4
        return maxOf(pngBytes * 4, 2304)
    }
}
