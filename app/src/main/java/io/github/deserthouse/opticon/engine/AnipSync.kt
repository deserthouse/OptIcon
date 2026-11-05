package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import org.json.JSONObject
import android.content.Context
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * AnipSync — ANIP (Android Notification Icon Project) rules sync.
 *
 * Upstream: https://github.com/BetterAndroid/android-notification-icon-project
 * (renamed from fankes/AndroidNotifyIconAdapt, license AGPL-3.0 -> Apache-2.0).
 * The old repository's json/ data was removed upstream — the legacy base64
 * subscription format is dead; this adapter speaks the new format instead:
 *
 *   icons/<category>/manifest.json — { "<pkg>": { label, format, color, overlay, contributors } }
 *   icons/<category>/res/<pkg>.png — 72px monochrome (LA) compliant glyph
 *
 * Transport: ONE zipball request (main branch archive, ~3MB) instead of ~870
 * individual raw fetches — direct GitHub connections from constrained networks
 * reset long bursts of small requests (empirically dies after ~50 in a row).
 *
 * Categories: app, game, system/common, system/coloros, system/mios.
 * Output layout is identical to IconLibEngine.ingestJson (dir/{pkg}.png +
 * meta.json), so every downstream consumer (IconLibEngine.lookup,
 * bakeRemote, hook-side cache) works unchanged.
 *
 * Batch SYNC V2: the sync is snapshot-atomic. The new version is built
 * completely inside a sibling staging dir, then swapped in via
 * FileSnapshotSwitch. Upstream revocations (a package absent from the new
 * bundle) now take effect — the old live dir is replaced wholesale — and an
 * interrupted sync can no longer leave a half-new/half-old mix (U02: the
 * previous write-in-place loop never deleted anything, so hasIcon/lookup,
 * which key on file existence, could never observe a revocation).
 * Contract unchanged: same signature, same count<=0 failure semantics for
 * callers (SubscriptionManager / HookLibSync self-sync).
 */
object AnipSync {

    private const val TAG = "OptIcon/AnipSync"
    const val BASE =
        "https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/"
    const val ZIP_URL =
        "https://github.com/BetterAndroid/android-notification-icon-project/archive/refs/heads/main.zip"
    private const val ZIP_ROOT = "android-notification-icon-project-main/"
    val CATEGORIES = listOf("app", "game", "system/common", "system/coloros", "system/mios")

    /** Internal legacy builtin source id (kept stable so stored prefs resolve). */
    const val BUILTIN_SOURCE_ID = "ania_raw"

    data class Entry(val pkg: String, val appName: String, val contributor: String, val category: String)

    private fun labelOf(labelRaw: Any?): String {
        val preferZh = Locale.getDefault().language == "zh"
        return when (labelRaw) {
            is JSONObject -> {
                val zh = labelRaw.optString("zh-CN", "")
                val en = labelRaw.optString("en", "")
                (if (preferZh) zh.ifBlank { en } else en.ifBlank { zh })
                    .ifBlank { if (labelRaw.length() > 0) labelRaw.names().getString(0) else "" }
            }
            else -> labelRaw?.toString() ?: ""
        }
    }

    // Context-free callers (hook self-sync) fall back to locale tables so the
    // progress strings stay single-language on either locale.
    private val zhProgress = mapOf(
        io.github.deserthouse.opticon.R.string.sync_anip_bundle to "正在下载 ANIP 资源包…",
        io.github.deserthouse.opticon.R.string.sync_unpacking to "正在解包 ANIP 资源…"
    )
    private val enProgress = mapOf(
        io.github.deserthouse.opticon.R.string.sync_anip_bundle to "Downloading ANIP bundle…",
        io.github.deserthouse.opticon.R.string.sync_unpacking to "Unpacking ANIP resources…"
    )

    private fun pkgKeyOk(pkg: String) =
        pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$"))

    /**
     * Sync ANIP resources into [dir] using the legacy cache layout
     * ({pkg}.png + meta.json). Single zipball download; the new version is
     * built in a staging dir and switched in atomically. Returns the number
     * of icons in the new snapshot; 0 = failure (live state untouched).
     */
    fun syncTo(dir: File, onProgress: ((String) -> Unit)? = null, context: Context? = null): Int {
        fun progress(resId: Int) = onProgress?.invoke(
            if (context != null) context.getString(resId)
            else if (Locale.getDefault().language == "zh") zhProgress[resId].orEmpty()
            else enProgress[resId].orEmpty()
        )
        val parent = dir.parentFile ?: return 0
        // Crash recovery first: converge a switch interrupted mid-steps so we
        // always build on a well-defined live state.
        FileSnapshotSwitch.recoverIfInterrupted(parent, dir.name)
        dir.mkdirs()
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        // Zip lands OUTSIDE dir — the whole dir is about to be replaced, and
        // this also sweeps legacy in-dir zips on the first snapshot switch.
        val zipFile = File(parent, "${dir.name}.zip.tmp")
        val staging = FileSnapshotSwitch.stagingOf(parent, dir.name)
        try {
            progress(io.github.deserthouse.opticon.R.string.sync_anip_bundle)
            if (!NetworkExecutor.fetchToFile(ZIP_URL, zipFile, null)) {
                TraceLogger.w(TAG, "ANIP zipball download failed")
                zipFile.delete()
                return 0
            }
            progress(io.github.deserthouse.opticon.R.string.sync_unpacking)
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            var ok = 0
            ZipFile(zipFile).use { zip ->
                val manifestEntries = CATEGORIES.associateWith { cat ->
                    zip.getEntry(ZIP_ROOT + "icons/" + cat + "/manifest.json")
                }.filterValues { it != null }

                // Pass 1: manifests -> pkg entries + resource path map
                val merged = LinkedHashMap<String, Pair<Entry, String>>()
                for ((cat, manEntry) in manifestEntries) {
                    try {
                        val obj = JSONObject(zip.getInputStream(manEntry!!).bufferedReader().readText())
                        for (key in obj.keys()) {
                            if (!pkgKeyOk(key) || merged.containsKey(key)) continue
                            val e = obj.getJSONObject(key)
                            merged[key] = Pair(
                                Entry(key, labelOf(e.opt("label")), e.optString("contributors", ""), cat),
                                ZIP_ROOT + "icons/" + cat + "/res/" + key + ".png"
                            )
                        }
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "manifest $cat failed: ${e.message}")
                    }
                }

                // Pass 2: extract res PNGs for known packages INTO STAGING
                val resEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".png") && it.name.contains("/res/") }
                    .associateBy { it.name }
                val metaArr = org.json.JSONArray()
                for ((pkg, pair) in merged) {
                    val ze = resEntries[pair.second] ?: continue
                    try {
                        val bytes = zip.getInputStream(ze).readBytes()
                        val pngFile = File(staging, "$pkg.png")
                        pngFile.writeBytes(bytes)
                        pngFile.setReadable(true, false)
                        ok++
                        metaArr.put(
                            JSONObject().apply {
                                put("packageName", pkg)
                                put("appName", pair.first.appName)
                                put("contributorName", pair.first.contributor)
                                put("isEnabled", true)
                            }
                        )
                    } catch (_: Exception) {
                    }
                }

                // meta.json — atomic-ish write INSIDE staging (travels with the
                // snapshot: no "new meta referencing old icons" window)
                val metaJson = JSONObject().apply { put("apps", metaArr) }
                val metaTmp = File(staging, "meta.json.tmp")
                metaTmp.writeText(metaJson.toString(), Charsets.UTF_8)
                val metaFile = File(staging, "meta.json")
                if (!metaTmp.renameTo(metaFile)) metaFile.writeText(metaJson.toString(), Charsets.UTF_8)
            }

            // V2 gate: empty snapshot = upstream unreachable/dead bundle.
            // Fail with live state untouched (callers keep their count<=0
            // failure semantics); staging is discarded.
            if (ok == 0) {
                TraceLogger.w(TAG, "ANIP snapshot empty — keeping live dir")
                staging.deleteRecursively()
                return 0
            }

            // Switch: staging validated, swap atomically. IconLibEngine's
            // cacheDir File is path-keyed (re-resolved per lookup) and the App
            // caller rebuilds metaIndex/l1Cache right after this returns, so
            // no extra invalidation hook is needed.
            try {
                FileSnapshotSwitch.switchIn(parent, dir.name)
                dir.setExecutable(true, false)
                dir.setReadable(true, false)
                TraceLogger.i(TAG, "ANIP sync: $ok icons switched into ${dir.absolutePath}")
                return ok
            } catch (e: Exception) {
                TraceLogger.w(TAG, "ANIP switch failed, rolling back: ${e.message}")
                FileSnapshotSwitch.rollback(parent, dir.name)
                return 0
            }
        } catch (e: Exception) {
            TraceLogger.w(TAG, "ANIP sync error: ${e.message}")
            staging.deleteRecursively()
            return 0
        } finally {
            zipFile.delete()
        }
    }
}
