package io.github.deserthouse.opticon.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * SharedIconStore — production icon delivery channel (Iconify-proven pattern).
 *
 * Problem it solves: SystemUI (system_app domain) cannot read the module's
 * filesDir (app_data_file domain, SELinux-enforced). The legacy
 * ContentProvider fallback is rejected by LSPosed v2 injected processes.
 *
 * Solution (identical to Iconify's XPOSED_RESOURCE_DIR, battle-tested on
 * Android 12–16): deliver baked PNGs through the PUBLIC Downloads directory
 * `Download/OptIcon/`.
 *
 *   App side (writes): MediaStore.Downloads API — no storage permission
 *     needed for our own contributions on API 29+, survives FUSE/ scoped
 *     storage, replaces same-name files atomically (IS_PENDING dance).
 *
 *   Hook side (reads): plain File API on
 *     Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS) —
 *     SystemUI is a privileged process and reads public dirs natively.
 *
 * File naming: {pkg}.opticon — extensionless (no .png) so MediaScanner
 * apps from indexing the icons (trick used by Iconify's ".iconify" files).
 *
 * Hot reload: the hook already runs FileObserver on this directory; writes
 * via MediaStore generate MOVED_TO/CLOSE_WRITE inotify events there.
 */
object SharedIconStore {

    const val DIR_NAME = "OptIcon"
    const val EXTENSION = ".opticon"  // no image suffix — MediaScanner ignores it

    /** Canonical read path used by the SystemUI hook (File API). */
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME)

    fun iconFile(pkg: String): File = File(publicDir(), "$pkg$EXTENSION")

    /** Master switch also lives here so the hook never touches filesDir. */
    fun masterSwitchFile(): File = File(publicDir(), "master_switch.opticon")

    /** Ensure .nomedia exists so gallery apps never index this dir (belt & braces). */
    /** One-time cleanup: rename legacy pkg.opticon.png to pkg.opticon so the
     *  gallery stops indexing files written by old versions. */
    private fun migrateLegacyNames(context: Context) {
        try {
            val prefs = context.getSharedPreferences("opticon_store", Context.MODE_PRIVATE)
            if (prefs.getBoolean("legacy_migrated", false)) return
            val dir = publicDir()
            dir.listFiles { f -> f.name.endsWith(".opticon.png") }?.forEach { old ->
                val target = File(dir, old.name.removeSuffix(".png"))
                if (!target.exists()) old.renameTo(target) else old.delete()
            }
            val cr = context.contentResolver
            cr.delete(android.provider.MediaStore.Files.getContentUri("external"),
                android.provider.MediaStore.MediaColumns.DATA + " LIKE ?",
                arrayOf("%/Download/OptIcon/%"))
            prefs.edit().putBoolean("legacy_migrated", true).apply()
        } catch (_: Exception) { }
    }

    fun ensureNoMedia(context: Context): Boolean {
        val f = File(publicDir(), ".nomedia")
        if (f.exists()) return true
        return try { f.createNewFile() } catch (_: Exception) { false }
    }

    /**
     * Write (or atomically replace) a baked icon PNG into the shared dir.
     * Must be called from the App process with a valid Context.
     * Returns the absolute path on success, null on failure.
     */
    fun writeIcon(context: Context, pkg: String, pngBytes: ByteArray): String? =
        writeBytes(context, "$pkg$EXTENSION", pngBytes)

    /** Write the master switch flag; content is "true"/"false". */
    fun writeMasterSwitch(context: Context, enabled: Boolean): String? =
        writeBytes(context, "master_switch.opticon", if (enabled) "true".toByteArray() else "false".toByteArray())

    /** Write the shade icon mode flag; content is "app"/"notif"/"pref". */
    fun writeShadeIconMode(context: Context, mode: String): String? =
        writeBytes(context, "shade_icon_mode.opticon", mode.toByteArray())

    // ━━ #13 color strategy matrix: global mode + per-app override ━━

    /** Global color policy file; content "off" | "force_mono". */
    fun colorModeFile(): File = File(publicDir(), "color_mode.opticon")

    /** Per-app override file; content "mono" | "color". */
    fun colorOverrideFile(pkg: String): File = File(File(publicDir(), "color_override"), "$pkg.opticon")

    fun writeColorMode(context: Context, mode: String): String? =
        writeBytes(context, "color_mode.opticon", mode.toByteArray())

    fun readColorMode(): String? =
        colorModeFile().takeIf { it.exists() }?.readText()?.trim()

    fun writeColorOverride(context: Context, pkg: String, value: String): String? =
        writeBytes(context, "color_override/$pkg.opticon", value.toByteArray())

    fun deleteColorOverride(context: Context, pkg: String): Boolean =
        colorOverrideFile(pkg).delete()

    fun readColorOverride(pkg: String): String? =
        colorOverrideFile(pkg).takeIf { it.exists() }?.readText()?.trim()

    // ━━ #14 original notification icon archive (hook passive capture) ━━

    fun originalsDir(): File = File(publicDir(), "originals")

    fun originalIconFile(pkg: String): File = File(originalsDir(), "$pkg.png")

    /**
     * Delete an icon from the shared dir (user reverted an app).
     * Returns true if a row was actually removed.
     */
    fun deleteIcon(context: Context, pkg: String): Boolean =
        deleteByName(context, "$pkg$EXTENSION")

    /**
     * Mirror-write: keep the legacy filesDir copy (for App-side previews and
     * future migration) AND publish to the shared dir.
     */
    fun mirrorIconToShared(context: Context, legacyFile: File, pkg: String): String? {
        val bytes = try {
            legacyFile.readBytes()
        } catch (_: Exception) {
            return null
        }
        return writeIcon(context, pkg, bytes)
    }

    /**
     * Bulk mirror every {pkg}.png from a legacy dir (baked/ or fankes_cache/)
     * into the shared dir. Called after ANIA sync ingest. Returns the number
     * of icons published.
     */
    fun mirrorAllFromDir(context: Context, legacyDir: File): Int {
        if (!legacyDir.isDirectory) return 0
        val pngs = legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return 0
        var published = 0
        for (f in pngs) {
            val pkg = f.name.removeSuffix(".png")
            if (pkg.isNotEmpty() && mirrorIconToShared(context, f, pkg) != null) published++
        }
        return published
    }

    // ━━━ internals: MediaStore.Downloads CRUD ━━━

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun writeBytes(context: Context, fileName: String, bytes: ByteArray): String? {
        ensureNoMedia(context)
        migrateLegacyNames(context)
        return try {
            val resolver = context.contentResolver
            val relativePath = "Download/$DIR_NAME"

            // Remove any existing row with the same name in our sub-directory
            deleteByName(context, fileName)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(collection(), values) ?: return null
            resolver.openOutputStream(itemUri, "wt")?.use { it.write(bytes) } ?: run {
                resolver.delete(itemUri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            // Resolve the physical absolute path for logging/verification
            resolver.query(itemUri, arrayOf(MediaStore.Downloads.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteByName(context: Context, fileName: String): Boolean {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(fileName, "Download/$DIR_NAME%")
            var deleted = false
            resolver.query(collection(), projection, selection, selectionArgs, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = Uri.withAppendedPath(collection(), id.toString())
                    try {
                        resolver.delete(uri, null, null)
                        deleted = true
                    } catch (_: Exception) {
                    }
                }
            }
            deleted
        } catch (_: Exception) {
            false
        }
    }
}
