package io.github.deserthouse.opticon.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File
import java.io.FileNotFoundException

/**
 * IconContentProvider — cross-process bridge (status flag + icon file fallback)
 *
 * Supports:
 *   - openFile: read baked/fankes PNG icons per package (fallback path;
 *     the SystemUI hook normally reads files directly, not via this provider)
 *   - call("get_flag"/"set_flag"): hook installed flag + PID
 *
 * Security: only SystemUI (UID=1000) and the module's own process.
 */
class IconContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "OptIcon/IconProvider"
        const val AUTHORITY = "io.github.deserthouse.opticon.icons"
        private const val CODE_ICON_BAKED = 1
        private const val CODE_ICON_FANKES = 2
        private const val CODE_FLAG = 3

        /** Same package-name rule as IconLibEngine.ingestJson() */
        private val PKG_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "baked/*", CODE_ICON_BAKED)
            addURI(AUTHORITY, "fankes/*", CODE_ICON_FANKES)
            addURI(AUTHORITY, "__flag__", CODE_FLAG)
        }
    }

    override fun onCreate(): Boolean {
        TraceLogger.i(TAG, "IconContentProvider created")
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_ICON_BAKED, CODE_ICON_FANKES -> "image/png"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val callerUid = Binder.getCallingUid()
        // Allow SystemUI (com.android.systemui, e.g. uid 10169) and the module
        // itself. We resolve uid → package because SystemUI's numeric uid is
        // NOT 1000 (that's system_server); checking raw uid 1000 would deny
        // the very hook that needs to read these icons.
        val callerPkg = context?.packageManager?.getNameForUid(callerUid)
        val systemuiUid = try {
            context?.packageManager?.getPackageUid("com.android.systemui", 0)
        } catch (_: Exception) { -1 }
        val isSystemUI = callerPkg == "com.android.systemui" || callerUid == systemuiUid
        val isSelf = callerUid == android.os.Process.myUid()
        if (!isSystemUI && !isSelf) {
            throw SecurityException(
                "Only SystemUI or self may read icon files (caller=$callerPkg uid=$callerUid)")
        }
        val dirName = when (uriMatcher.match(uri)) {
            CODE_ICON_BAKED -> "baked"
            CODE_ICON_FANKES -> "fankes_cache"
            else -> return null
        }
        val packageName = (uri.lastPathSegment ?: return null).removeSuffix(".png") // Reject path traversal / malformed names before touching the filesystem
        if (!PKG_NAME.matches(packageName)) {
            throw SecurityException("Invalid package name")
        }
        val context = context ?: return null
        val pngFile = File(File(context.filesDir, dirName), "$packageName.png")
        if (!pngFile.exists()) throw FileNotFoundException("Icon not baked for $packageName")
        return ParcelFileDescriptor.open(pngFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerUid = Binder.getCallingUid()
        // set_flag: only SystemUI may write
        // get_flag: allow SystemUI + module's own process (for UI status check)
        val callerPkg = context?.packageManager?.getNameForUid(callerUid)
        val systemuiUid = try {
            context?.packageManager?.getPackageUid("com.android.systemui", 0)
        } catch (_: Exception) { -1 }
        val isSystemUI = callerPkg == "com.android.systemui" || callerUid == systemuiUid
        val isSelf = callerUid == android.os.Process.myUid()
        when (method) {
            "set_flag" -> {
                if (!isSystemUI) {
                    TraceLogger.w(TAG, "set_flag denied for UID=$callerUid")
                    throw SecurityException("Only SystemUI may set flag")
                }
            }
            "get_flag" -> {
                if (!isSystemUI && !isSelf) {
                    TraceLogger.w(TAG, "get_flag denied for UID=$callerUid")
                    return null
                }
            }
            else -> {
                TraceLogger.w(TAG, "call($method) denied for UID=$callerUid")
                throw SecurityException("Unknown method")
            }
        }
        val ctx = context ?: return null

        return when (method) {
            "set_flag" -> {
                val flagFile = File(ctx.filesDir, "hook_installed")
                val value = extras?.getBoolean("hook_installed", false) ?: false
                val pid = extras?.getInt("hook_pid", 0) ?: 0
                if (value && pid > 0) {
                    val tmp = File(ctx.filesDir, "hook_installed.tmp")
                    tmp.writeText("$pid")
                    if (!tmp.renameTo(flagFile)) {
                        flagFile.writeText("$pid")
                    }
                } else if (!value) {
                    flagFile.delete()
                }
                null
            }
            "get_flag" -> {
                val flagFile = File(ctx.filesDir, "hook_installed")
                Bundle().apply {
                    putBoolean("hook_installed", flagFile.exists())
                    putInt("hook_pid", try { flagFile.readText().toInt() } catch (_: Exception) { 0 })
                }
            }
            else -> null
        }
    }
}
