package io.github.deserthouse.opticon.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * AnipSnapshotBuilder — 从 ANIP 资源包构建一份新的规则库快照（纯 JVM）。
 *
 * 输入：归档、旧版本目录（可为空）、暂存目录（由本函数清空后构建）。
 * 输出：[Result]——构建完成（给出条目与统计）或被拒绝。本函数不做切换。
 *
 * 修复批（X-03 / X-15）的核心原则：**读取失败不等于上游撤销。**
 * 批 SYNC V2 把新版本目录整体换入，但构建时只装「成功写出的条目」，解析与读取
 * 失败的被静默跳过——上游某个类别的清单解析失败，或某个应用的图标读不出来，
 * 结果与「上游撤销了它」无法区分：同步显示成功，图标却从本地消失。
 *
 * 现在的规则：
 *  - 某类别的清单文件**存在但解析不了**（读不出、不是合法 JSON）→ 整体判失败，
 *    不构建。此时分不清哪些包是被撤销的，宁可不更新。
 *  - 清单条目不存在 → 上游没有这个类别，正常继续。
 *  - 清单**声明了**某个包，但它的资源缺失、读不出、或不是图片；或清单里这个
 *    条目本身读不出 → 沿用旧版本里该包的图标，不当作撤销；旧版本里也没有的，
 *    不进新快照，记为缺失。
 *  - 统计恢复「成功数 / 声明数」对比，并细分沿用与缺失（X-15）。
 */
internal object AnipSnapshotBuilder {

    /** 新快照里的一个条目。 */
    data class Item(val pkg: String, val appName: String, val contributor: String, val category: String)

    /** declared = 各类别清单声明的包总数（跨类别重复的只算一次）。 */
    data class Stats(val declared: Int, val written: Int, val carriedOver: Int, val missing: Int)

    sealed class Result {
        data class Built(val items: List<Item>, val stats: Stats) : Result()

        /** [reason] 供日志。暂存目录里不会留下半成品。 */
        data class Rejected(val reason: String) : Result()
    }

    private val PKG_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")

    /** 清单路径：可选的归档根目录前缀 + icons/<类别>/manifest.json。 */
    private val MANIFEST_PATH = Regex("^(?:(.*/))?icons/(" + AnipSync.CATEGORIES.joinToString("|") { Regex.escape(it) } + ")/manifest\\.json$")

    /**
     * 构建。归档根目录自动探测：官方归档是 android-notification-icon-project-main/，
     * 自定义归档（fork、别的分支名、或没有外层目录）前缀不同——修复批之前根目录
     * 名是写死的，自定义地址即使传进来也解析不出东西（X-17）。
     */
    fun build(
        zipFile: File,
        oldDir: File?,
        staging: File,
        preferZh: Boolean = Locale.getDefault().language == "zh"
    ): Result {
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) return Result.Rejected("cannot create staging dir")
        // 拒绝 = 不留下半成品（调用方拿到 Rejected 时暂存目录已清空）
        fun reject(reason: String): Result.Rejected {
            staging.deleteRecursively()
            return Result.Rejected(reason)
        }

        // ZipFile 打开时必须读到尾部的中央目录——被截断的归档在这里就被拒绝
        val zip = try {
            ZipFile(zipFile)
        } catch (e: IOException) {
            return reject("not a complete zip (${e.javaClass.simpleName}: ${e.message})")
        }
        try {
            val root = detectRoot(zip) ?: return reject("no ANIP category manifest found")
            val old = oldDir?.takeIf { it.isDirectory }

            val items = ArrayList<Item>()
            val seen = HashSet<String>()
            var declared = 0
            var written = 0
            var carriedOver = 0
            var missing = 0

            for (category in AnipSync.CATEGORIES) {
                val manifest = zip.getEntry(root + "icons/" + category + "/manifest.json")
                    ?: continue // 清单条目不存在 = 上游没有这个类别
                val text = try {
                    zip.getInputStream(manifest).use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: IOException) {
                    return reject("manifest $category unreadable (${e.message})")
                }
                val obj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return reject("manifest $category unparsable (${e.message})")
                }
                val keys = try {
                    obj.keys().asSequence().toList()
                } catch (e: Exception) {
                    return reject("manifest $category unparsable (${e.message})")
                }

                for (key in keys) {
                    if (!PKG_KEY.matches(key) || !seen.add(key)) continue
                    declared++
                    // 条目值读不出来（上游写坏了这一个条目）→ 按空标签处理，走下面的沿用/缺失规则
                    val entry = try {
                        obj.getJSONObject(key)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val resource = root + "icons/" + category + "/res/" + key + ".png"
                    val png = readImage(zip, resource)
                    val target = File(staging, "$key.png")
                    when {
                        png != null -> {
                            target.writeBytes(png)
                            target.setReadable(true, false)
                            written++
                        }
                        old != null && File(old, "$key.png").isFile -> {
                            File(old, "$key.png").copyTo(target, overwrite = true)
                            target.setReadable(true, false)
                            carriedOver++
                        }
                        else -> {
                            missing++
                            continue // 旧版本里也没有：不进新快照
                        }
                    }
                    items += Item(
                        pkg = key,
                        appName = labelOf(entry.opt("label"), preferZh),
                        contributor = entry.optString("contributors", ""),
                        category = category
                    )
                }
            }

            if (items.isEmpty()) {
                return reject(
                    "snapshot empty (declared $declared, written $written, carried over $carriedOver, missing $missing)"
                )
            }
            writeMeta(staging, items)
            return Result.Built(items, Stats(declared, written, carriedOver, missing))
        } finally {
            zip.close()
        }
    }

    /** 归档根目录前缀（含结尾斜杠，没有外层目录时为空串）。
     *  取类别清单最多的那个前缀；并列时取先出现的。 */
    private fun detectRoot(zip: ZipFile): String? {
        val countByRoot = LinkedHashMap<String, Int>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val match = MANIFEST_PATH.matchEntire(entries.nextElement().name) ?: continue
            val root = match.groupValues[1]
            countByRoot[root] = (countByRoot[root] ?: 0) + 1
        }
        return countByRoot.maxByOrNull { it.value }?.key
    }

    /** 读一个资源条目；缺失、读不出、或内容不是图片 → null。 */
    private fun readImage(zip: ZipFile, name: String): ByteArray? {
        val entry: ZipEntry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        val bytes = try {
            zip.getInputStream(entry).use { it.readBytes() }
        } catch (_: IOException) {
            return null
        }
        return if (looksLikeImage(bytes)) bytes else null
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        return png || jpeg || gif || webp
    }

    /**
     * 标签按语言取：偏好中文时取 zh-CN、没有再退到 en；否则反过来；两者都没有，
     * 取清单里第一个非空的其它语言名称。修复批之前最后的兜底返回的是语言**键名**
     * （应用名因此显示成 "en" 这样的代码）。
     */
    internal fun labelOf(labelRaw: Any?, preferZh: Boolean): String = when (labelRaw) {
        is JSONObject -> {
            val zh = labelRaw.optString("zh-CN", "")
            val en = labelRaw.optString("en", "")
            (if (preferZh) zh.ifBlank { en } else en.ifBlank { zh }).ifBlank {
                labelRaw.keys().asSequence()
                    .map { labelRaw.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                    ?: ""
            }
        }
        else -> labelRaw?.toString() ?: ""
    }

    private fun writeMeta(staging: File, items: List<Item>) {
        val metaArr = JSONArray()
        items.forEach {
            metaArr.put(
                JSONObject().apply {
                    put("packageName", it.pkg)
                    put("appName", it.appName)
                    put("contributorName", it.contributor)
                    put("isEnabled", true)
                }
            )
        }
        val metaJson = JSONObject().apply { put("apps", metaArr) }.toString()
        // meta 与图标同处暂存目录，随切换整体生效
        val tmp = File(staging, "meta.json.tmp")
        tmp.writeText(metaJson, Charsets.UTF_8)
        val metaFile = File(staging, RuleLibrary.META_FILE)
        if (!tmp.renameTo(metaFile)) metaFile.writeText(metaJson, Charsets.UTF_8)
    }
}
