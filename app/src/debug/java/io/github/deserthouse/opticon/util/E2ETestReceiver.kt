package io.github.deserthouse.opticon.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.deserthouse.opticon.engine.IconEngine
import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.engine.SharedIconStore
import io.github.deserthouse.opticon.util.PreferenceManager.IconMethod
import io.github.deserthouse.opticon.util.PreferenceManager.ManualBranch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * E2ETestReceiver — DEBUG-ONLY end-to-end test driver.
 *
 * Drives the REAL baking pipeline (IconEngine.bake → filesDir →
 * SharedIconStore mirror) from adb, no UI interaction needed:
 *
 *   adb shell am broadcast \
 *     -a io.github.deserthouse.opticon.E2E_BAKE \
 *     -n io.github.deserthouse.opticon/.util.E2ETestReceiver \
 *     --es pkg com.example.app --es method adaptive
 *
 * method: adaptive (adaptive-icon extraction) | selffilter (algorithm redraw)
 *
 * Also verifies the full delivery chain and reports each stage to logcat
 * (tag OptIcon/E2E) so the test harness can grep the results.
 */
class E2ETestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OptIcon/E2E"
        private const val ACTION_BAKE = "io.github.deserthouse.opticon.E2E_BAKE"
        private const val ACTION_CHECK = "io.github.deserthouse.opticon.E2E_CHECK"
        private const val ACTION_COLOR_NOTIF = "io.github.deserthouse.opticon.E2E_COLOR_NOTIF"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ACTION_BAKE -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val method = intent.getStringExtra("method") ?: "adaptive"
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        TraceLogger.i(TAG, "E2E bake start: pkg=$pkg method=$method")
                        val m = when (method) {
            "selffilter" -> IconMethod.SELF_FILTER
            "iconpack" -> IconMethod.ICON_PACK
            "manual" -> IconMethod.MANUAL
            else -> IconMethod.ADAPTIVE
        }
        val branch = when (intent.getStringExtra("branch")) {
            "material" -> ManualBranch.MATERIAL_LIB
            "emoji" -> ManualBranch.EMOJI_TEXT
            "local" -> ManualBranch.LOCAL_FILE
            else -> null
        }
                        val result = IconEngine.bake(
                            context = ctx,
                            packageName = pkg,
                            method = m,
                            branch = branch,
                            params = RedrawParams.DEFAULT,
                            localPath = intent.getStringExtra("path"),
                            materialIconName = intent.getStringExtra("icon"),
                            emojiText = intent.getStringExtra("emoji"),
                            selectedIconPack = intent.getStringExtra("pack"),
                            selectedPackIconDrawable = intent.getStringExtra("drawable")
                        )
                        val bitmap = result.bitmap
                        if (bitmap == null) {
                            TraceLogger.i(TAG, "E2E RESULT bake=null reason=${result.source} (${result.detail})")
                            return@launch
                        }
                        // Real writeBaked path: filesDir/baked + SharedIconStore mirror
                        val bakedDir = File(ctx.filesDir, "baked").apply { mkdirs() }
                        val pngFile = File(bakedDir, "$pkg.png")
                        java.io.FileOutputStream(pngFile).use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        val shared = SharedIconStore.mirrorIconToShared(ctx, pngFile, pkg)
                        val sharedFile = SharedIconStore.iconFile(pkg)
                        TraceLogger.i(TAG, "E2E RESULT bake=ok hit=${result.source} (${result.detail}) " +
                            "legacy=${pngFile.exists()}(${pngFile.length()}B) " +
                            "shared=$shared sharedExists=${sharedFile.exists()}(${sharedFile.length()}B)")
                    } catch (e: Exception) {
                        TraceLogger.i(TAG, "E2E RESULT bake=error ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_COLOR_NOTIF -> {
                // Post a notification whose smallIcon is a COLORFUL BITMAP —
                // the classic non-compliant pattern (e.g. marketing icons).
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                        val ch = android.app.NotificationChannel("e2e_color", "E2E Color", android.app.NotificationManager.IMPORTANCE_LOW)
                        nm.createNotificationChannel(ch)
                        // colorful bitmap: red-to-blue gradient
                        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                        for (y in 0 until 48) for (x in 0 until 48) {
                            bmp.setPixel(x, y, android.graphics.Color.rgb(x * 255 / 47, 0, y * 255 / 47))
                        }
                        val n = android.app.Notification.Builder(ctx, "e2e_color")
                            .setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
                            .setContentTitle("ColorIcon")
                            .setContentText("non-compliant sample")
                            .build()
                        nm.notify(9901, n)
                        TraceLogger.i(TAG, "E2E color notif posted (BITMAP smallIcon)")
                    } catch (e: Exception) {
                        TraceLogger.i(TAG, "E2E color notif error: ${e.message}")
                    } finally { pending.finish() }
                }
            }
            ACTION_CHECK -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val sharedFile = SharedIconStore.iconFile(pkg)
                val legacy = File(File(ctx.filesDir, "baked"), "$pkg.png")
                TraceLogger.i(TAG, "E2E CHECK pkg=$pkg shared=${sharedFile.exists()} " +
                    "(${if (sharedFile.exists()) sharedFile.length() else 0}B) " +
                    "legacy=${legacy.exists()} masterSwitch=${SharedIconStore.masterSwitchFile().exists()}")
            }
        }
    }
}
