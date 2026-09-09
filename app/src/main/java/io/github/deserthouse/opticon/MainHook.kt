package io.github.deserthouse.opticon

import io.github.deserthouse.opticon.hook.NotificationHook
import io.github.deserthouse.opticon.util.TraceLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * MainHook — LSPosed module entry point (libxposed API 102)
 *
 * Hooks into com.android.systemui to intercept notification icon rendering.
 * Does NOT hook system_server or other processes.
 *
 * Master switch: NotificationHook reads filesDir/master_switch file
 * (world-readable, written by UI process). No XSharedPreferences needed.
 */
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "OptIcon/MainHook"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return

        TraceLogger.i(TAG, "SystemUI package loaded — installing OptIcon hooks")

        try {
            NotificationHook.install(this, param.defaultClassLoader)
            TraceLogger.i(TAG, "Notification hooks installed successfully")
            // Off the SystemUI startup path: this is a synchronous binder call
            // that may cold-start the module process — never block SystemUI boot
            Thread({ writeHookInstalledFlag(true) }, "OptIconFlagWriter").start()
        } catch (e: Exception) {
            TraceLogger.e(TAG, "Failed to install notification hooks", e)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        TraceLogger.d(TAG, "SystemServer starting — OptIcon only hooks SystemUI, skipping")
    }

    /** Disable hot reload to prevent hook stacking */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        TraceLogger.i(TAG, "Hot reload requested — denying to prevent hook stacking")
        return false
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        TraceLogger.i(TAG, "Hot reload completed")
    }

    private fun writeHookInstalledFlag(value: Boolean) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val getSysUiCtx = atClass.getDeclaredMethod("getSystemUiContext")
            val ctx = getSysUiCtx.invoke(currentAT) as? android.content.Context ?: return
            val uri = android.net.Uri.parse("content://io.github.deserthouse.opticon.icons/__flag__")
            val bundle = android.os.Bundle().apply {
                putBoolean("hook_installed", value)
                putInt("hook_pid", android.os.Process.myPid())
            }
            ctx.contentResolver.call(uri, "set_flag", null, bundle)
            TraceLogger.i(TAG, "Hook installed flag + PID written")
        } catch (e: Exception) {
            TraceLogger.w(TAG, "Failed to set hook installed flag: ${e.message}")
        }
    }
}
