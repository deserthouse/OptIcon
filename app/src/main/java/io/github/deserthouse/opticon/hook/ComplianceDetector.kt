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

    fun dir(base: File): File = File(base, DIR)

    /** Evaluate + persist compliance for a notification's original smallIcon.
     *  Called BEFORE replacement so we judge the app's own icon. */
    fun evaluateAndReport(sharedBase: File, sbn: Notification, pkg: String) {
        try {
            val icon = sbn.smallIcon ?: return
            // TYPE_RESOURCE/URI = vector template → compliant by definition.
            // TYPE_BITMAP → rasterize and check monochrome.
            val compliant = when (icon.type) {
                1 -> loadDrawable(icon)?.let { isMonochrome(it) } ?: return
                else -> true
            }
            val file = File(dir(sharedBase), pkg)
            val flag = if (compliant) "1" else "0"
            // skip disk IO when the verdict is unchanged (frequent notifiers)
            if (file.exists() && file.readText() == flag) return
            file.parentFile?.mkdirs()
            file.writeText(flag)
        } catch (_: Exception) {
            // never crash SystemUI for a badge
        }
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
