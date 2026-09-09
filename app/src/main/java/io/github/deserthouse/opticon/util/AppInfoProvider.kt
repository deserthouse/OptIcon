package io.github.deserthouse.opticon.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * AppInfoProvider —— installed app scanner (HMAL-optimized)
 *
 * Key optimization: uses getInstalledPackages() instead of per-app IPC calls.
 * HideMyApplist does the same —— one bulk call gets all PackageInfo with
 * permissions, install time, etc. in a single Binder transaction.
 * No per-app queryIntentActivities / getPackageInfo overhead.
 */
object AppInfoProvider {

    fun getInstalledApps(
        context: Context,
        includeSystemApps: Boolean = false,
        requireNotificationCapable: Boolean = true
    ): List<AppEntry> {
        val pm = context.packageManager

        // One IPC call with GET_ACTIVITIES + GET_PERMISSIONS (H5+H6 fix)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
        val allPackages = pm.getInstalledPackages(flags)

        // Pre-compute launcher activity packages in one query (H5 fix)
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPkgs = if (requireNotificationCapable) {
            pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()
        } else emptySet()

        return allPackages
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { includeSystemApps || !isSystemApp(it.applicationInfo) }
            .filter { pkg ->
                if (!requireNotificationCapable) true
                else launcherPkgs.contains(pkg.packageName) ||
                     hasNotificationPermission(pkg)
            }
            .map { pkg ->
                val appInfo = pkg.applicationInfo ?: return@map null
                AppEntry(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isSystemApp = isSystemApp(appInfo)
                )
            }
            .filterNotNull()
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    private fun isSystemApp(appInfo: ApplicationInfo?): Boolean {
        return appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun hasNotificationPermission(pkg: android.content.pm.PackageInfo): Boolean {
        return pkg.requestedPermissions?.any {
            it == android.Manifest.permission.POST_NOTIFICATIONS
        } == true
    }
}

data class AppEntry(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val isSystemApp: Boolean
)
