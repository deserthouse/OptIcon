package io.github.deserthouse.opticon.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.app.Notification
import android.content.Context
import java.io.File

/**
 * ComplianceDetector — runtime notification-icon compliance evaluation.
 *
 * Runs INSIDE SystemUI (hook context): every notification passing through
 * createIcons is evaluated — is its original smallIcon a compliant
 * monochrome/alpha-mask glyph (native Android style), or a colorful bitmap
 * (non-compliant, what OptIcon fixes)?
 *
 * Results are written as simple flag files to the shared public dir:
 *   Download/OptIcon/compliance/<pkg>   content: "1" (compliant) | "0" (not)
 *
 * The App-side list reads these files to show the 符合规范 badge.
 * Written via plain File IO — SystemUI is privileged and the public
 * Downloads dir is world-writable for it (Iconify-verified pattern).
 */
object ComplianceDetector {

    private const val DIR = "compliance"

    /** Verdict per pkg: frequent notifiers re-enter createIcons on every
     *  post; this cache spares the 64×64 render + pixel scan (the flag-file
     *  read stays as the cross-boot truth). */
    private val verdictCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun dir(base: File): File = File(base, DIR)

    /** Evaluate + persist compliance for a notification's original smallIcon.
     *  Called BEFORE replacement so we judge the app's own icon. */
    fun evaluateAndReport(sharedBase: File, sbn: Notification, pkg: String) {
        try {
            val icon = sbn.smallIcon ?: return
            verdictCache[pkg]?.let { cached ->
                archiveOriginal(sharedBase, icon, pkg)
                writeFlag(sharedBase, pkg, cached)
                return
            }
            // TYPE_RESOURCE/URI = vector template → compliant by definition.
            // TYPE_BITMAP → rasterize and check monochrome.
            val compliant = when (icon.type) {
                1 -> loadDrawable(icon)?.let { isMonochrome(it) } ?: return
                else -> true
            }
            verdictCache[pkg] = compliant
            archiveOriginal(sharedBase, icon, pkg)
            writeFlag(sharedBase, pkg, compliant)
        } catch (_: Exception) {
            // never crash SystemUI for a badge
        }
    }

    private fun writeFlag(sharedBase: File, pkg: String, compliant: Boolean) {
        val file = File(dir(sharedBase), pkg)
        val flag = if (compliant) "1" else "0"
        // skip disk IO when the verdict is unchanged (frequent notifiers)
        if (file.exists() && file.readText() == flag) return
        file.parentFile?.mkdirs()
        file.writeText(flag)
    }

    /** Load the drawable behind an Icon without loading resources twice. */
    private fun loadDrawable(icon: android.graphics.drawable.Icon): Drawable? = try {
        if (icon.type == 1) {  // TYPE_BITMAP — the only type that can carry a colorful payload
            val f = android.graphics.drawable.Icon::class.java.getDeclaredField("mBitmap")
            f.isAccessible = true
            (f.get(icon) as? Bitmap)?.let { b -> BitmapDrawable(null, b) }
        } else {
            null  // RESOURCE/URI icons are vector templates → compliant by definition
        }
    } catch (_: Exception) { null }

    /** Extract the raw bitmap behind a TYPE_BITMAP Icon (no copy). */
    fun extractBitmap(icon: android.graphics.drawable.Icon): Bitmap? = try {
        if (icon.type == 1) {
            val f = android.graphics.drawable.Icon::class.java.getDeclaredField("mBitmap")
            f.isAccessible = true
            f.get(icon) as? Bitmap
        } else null
    } catch (_: Exception) { null }

    /**
     * #14 原始通知图标抓取：passively archive the app's own smallIcon PNG so
     * the App can offer "use the app's original notification icon" as a
     * source. Write-on-change (size differs) to bound disk IO for frequent
     * notifiers. Never throws into the caller's notification path.
     */
    private fun archiveOriginal(sharedBase: File, icon: android.graphics.drawable.Icon, pkg: String) {
        try {
            if (icon.type != 1) return
            val bmp = extractBitmap(icon) ?: return
            val out = java.io.ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return
            val bytes = out.toByteArray()
            val dir = File(sharedBase, "originals")
            val f = File(dir, "$pkg.png")
            if (f.exists() && f.length() == bytes.size.toLong()) return
            dir.mkdirs()
            f.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    /**
     * #13 强制单色：saturation-0 render of any drawable, as a bitmap ready
     * for Icon.createWithBitmap. Used by the color strategy matrix to
     * gray out violating colorful icons when policy asks for it.
     */
    fun toGrayscaleBitmap(src: Drawable, size: Int = 96): Bitmap? = try {
        val w = src.intrinsicWidth.takeIf { it > 0 } ?: size
        val h = src.intrinsicHeight.takeIf { it > 0 } ?: size
        val sw = w.coerceAtMost(192); val sh = h.coerceAtMost(192)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(0f) }
            )
        }
        src.setBounds(0, 0, sw, sh)
        src.draw(canvas)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        bmp
    } catch (_: Exception) { null }

    /** A compliant notification icon is a single-color (usually white) glyph
     *  on transparency. Test: every opaque pixel shares (nearly) the same RGB. */
    private fun isMonochrome(d: Drawable): Boolean {
        val w = 64; val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        var refR = -1; var refG = -1; var refB = -1
        var opaque = 0
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (refR < 0) { refR = r; refG = g; refB = b; opaque++; continue }
                if (Math.abs(r - refR) > 24 || Math.abs(g - refG) > 24 || Math.abs(b - refB) > 24) {
                    bmp.recycle()
                    return false  // multiple distinct colors → colorful icon
                }
                opaque++
            }
        }
        bmp.recycle()
        return opaque >= 4  // need a few samples to judge
    }

    /** App-side: read the recorded compliance flag for a package (null = no data yet). */
    fun readFlag(sharedBase: File, pkg: String): Boolean? = try {
        val f = File(dir(sharedBase), pkg)
        if (f.exists()) f.readText().trim() == "1" else null
    } catch (_: Exception) { null }
}
