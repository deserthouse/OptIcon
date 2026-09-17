package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.network.NetworkExecutor
import io.github.deserthouse.opticon.util.TraceLogger
import org.json.JSONObject
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

    private fun pkgKeyOk(pkg: String) =
        pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$"))

    /**
     * Sync ANIP resources into [dir] using the legacy cache layout
     * ({pkg}.png + meta.json). Single zipball download, then local unpack.
     * Returns the number of icons written.
     */
    fun syncTo(dir: File, onProgress: ((String) -> Unit)? = null): Int {
        dir.mkdirs()
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        val zipFile = File(dir, ".anip_main.zip")
        try {
            onProgress?.invoke("Downloading ANIP bundle...")
            if (!NetworkExecutor.fetchToFile(ZIP_URL, zipFile, null)) {
                TraceLogger.w(TAG, "ANIP zipball download failed")
                zipFile.delete()
                return 0
            }
            onProgress?.invoke("Unpacking ANIP resources...")
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

                // Pass 2: extract res PNGs for known packages
                val resEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".png") && it.name.contains("/res/") }
                    .associateBy { it.name }
                var ok = 0
                val metaArr = org.json.JSONArray()
                for ((pkg, pair) in merged) {
                    val ze = resEntries[pair.second] ?: continue
                    try {
                        val bytes = zip.getInputStream(ze).readBytes()
                        val pngFile = File(dir, "$pkg.png")
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

                // meta.json — atomic-ish write
                val metaJson = JSONObject().apply { put("apps", metaArr) }
                val metaTmp = File(dir, "meta.json.tmp")
                metaTmp.writeText(metaJson.toString(), Charsets.UTF_8)
                val metaFile = File(dir, "meta.json")
                if (!metaTmp.renameTo(metaFile)) metaFile.writeText(metaJson.toString(), Charsets.UTF_8)

                TraceLogger.i(TAG, "ANIP sync: $ok/${merged.size} icons into ${dir.absolutePath}")
                return ok
            }
        } catch (e: Exception) {
            TraceLogger.w(TAG, "ANIP sync error: ${e.message}")
            return 0
        } finally {
            zipFile.delete()
        }
    }
}
