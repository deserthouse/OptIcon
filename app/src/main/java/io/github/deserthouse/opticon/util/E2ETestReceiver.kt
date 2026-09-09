package io.github.deserthouse.opticon.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.deserthouse.opticon.engine.IconEngine
import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.engine.SharedIconStore
import io.github.deserthouse.opticon.util.PreferenceManager.IconMethod
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
                        val m = if (method == "selffilter") IconMethod.SELF_FILTER else IconMethod.ADAPTIVE
                        val result = IconEngine.bake(
                            context = ctx,
                            packageName = pkg,
                            method = m,
                            branch = null,
                            params = RedrawParams.DEFAULT,
                            localPath = null,
                            materialIconName = null,
                            emojiText = null
                        )
                        val bitmap = result.bitmap
                        if (bitmap == null) {
                            TraceLogger.i(TAG, "E2E RESULT bake=null reason=${result.hitLevel}")
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
                        TraceLogger.i(TAG, "E2E RESULT bake=ok hit=${result.hitLevel} " +
                            "legacy=${pngFile.exists()}(${pngFile.length()}B) " +
                            "shared=$shared sharedExists=${sharedFile.exists()}(${sharedFile.length()}B)")
                    } catch (e: Exception) {
                        TraceLogger.i(TAG, "E2E RESULT bake=error ${e.message}")
                    } finally {
                        pending.finish()
                    }
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
