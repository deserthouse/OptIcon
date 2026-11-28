package io.github.deserthouse.opticon.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32
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
 *  - 某类别的清单文件**存在但解析不了**（读不出、不是合法 JSON、超过大小上限）→ 整体判失败，
 *    不构建。此时分不清哪些包是被撤销的，宁可不更新。
 *  - 清单条目不存在 → 上游没有这个类别，正常继续。
 *  - 清单**声明了**某个包，但它的资源缺失、读不出、或不是图片；或清单里这个
 *    条目本身读不出 → 沿用旧版本里该包的图标，不当作撤销；旧版本里也没有的，
 *    不进新快照，记为缺失。
 *  - 统计恢复「成功数 / 声明数」对比，并细分沿用与缺失（X-15）。
 *
 * 批 UP（上游对齐）补上的三种上游写法（2026-11-27 核查：上游清单 924 条里有 110 条
 * 进不到 OptIcon，就是它们）：
 *  - **别名**：清单条目写 `target` 指向同一个类别里的另一个包，自己没有图
 *    （如抖音极速版 → 抖音、各渠道服 → 官方服）。别名包得到自己的一份图（字节与
 *    目标相同）和一条 meta，省略的属性（标签、贡献者）取目标的。目标不存在、成环、
 *    目标的图取不到 → 这一条解析不了：不进新快照（旧版本里有则沿用），计入
 *    `unresolved`。上游 SDK 在这里是整体报错；本模块按「读取失败 ≠ 撤销」只放过这一条。
 *  - **SVG**：条目 `format` 是 `svg`，资源是 `.svg`。构建器只认字节：交给
 *    [IconRasterizer] 渲染成 PNG 再落盘（生产实现是 AndroidSVG，在 AnipSync 里注入；
 *    这里保持纯 JVM）。渲染不了按「读不出」处理。
 *  - **两种目录结构**：整库 zipball 是 `{root}icons/<类别>/…`，上游官方资源包没有
 *    `icons/` 这一层，是 `{root}<类别>/…`。
 * 同时加了上限（[Limits]）：恶意或损坏的自定义源不能靠超大条目把内存或磁盘吃满。
 */
internal object AnipSnapshotBuilder {

    /** 把 SVG 字节渲染成 PNG 字节。[maxDimension] 是文档尺寸的上限（上游的约定是 150px）。
     *  返回 null = 渲染不了，调用方按「读不出」处理。 */
    fun interface IconRasterizer {
        fun renderPng(svg: ByteArray, maxDimension: Int): ByteArray?
    }

    /** 不支持 SVG 的渲染器：一律返回 null。构建器的默认值，纯 JVM 单测也用它。 */
    val NO_RASTERIZER: IconRasterizer = IconRasterizer { _, _ -> null }

    /** 上限。生产用 [DEFAULT_LIMITS]；测试可以调小，免得为了越界造几十兆的字节。 */
    data class Limits(
        /** 单个清单文件解压后的大小。超过 = 整体拒绝。 */
        val manifestBytes: Long = 4L * 1024 * 1024,
        /** 单个图标文件解压后的大小。超过 = 这一条读不出。 */
        val resourceBytes: Long = 1024L * 1024,
        /** 整个归档读出来的字节总量。超过 = 整体拒绝。 */
        val totalUncompressedBytes: Long = 32L * 1024 * 1024,
        /** 归档文件本身的大小。超过 = 整体拒绝。 */
        val archiveBytes: Long = 64L * 1024 * 1024,
        /** 归档里的条目数。超过 = 整体拒绝——几十万个空条目能把中央目录和条目名吃掉几百兆内存。 */
        val archiveEntries: Int = 50_000,
        /** SVG 文档尺寸上限（像素）。 */
        val iconDimension: Int = 150
    )

    val DEFAULT_LIMITS = Limits()

    /** 新快照里的一个条目。 */
    data class Item(val pkg: String, val appName: String, val contributor: String, val category: String)

    /**
     * declared = 各类别清单声明的包总数（跨类别重复的只算一次）。
     * written / rendered / aliased 是「图从归档里取到」的三种来路：位图直接写出、SVG 渲染后写出、
     * 别名复用目标的图；取不到图时沿用旧版本（carriedOver）或记为缺失（missing）。
     * declared = written + rendered + aliased + carriedOver + missing。
     * unresolved 是解析不了的别名条数，是 carriedOver 与 missing 里的一部分，不单独加进上式。
     * renderFailed 是渲染不了的 SVG 条数（渲染器交回空、抛异常、入参是 gzip 包裹的），同样是
     * carriedOver 与 missing 里的一部分：按「读不出」处理，但在日志里与「归档里没有这个文件」分开。
     */
    data class Stats(
        val declared: Int,
        val written: Int,
        val carriedOver: Int,
        val missing: Int,
        val rendered: Int = 0,
        val aliased: Int = 0,
        val unresolved: Int = 0,
        val renderFailed: Int = 0
    )

    sealed class Result {
        data class Built(val items: List<Item>, val stats: Stats) : Result()

        /** [reason] 供日志。暂存目录里不会留下半成品。 */
        data class Rejected(val reason: String) : Result()
    }

    private val PKG_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")

    /** 读出来的大小超过了某条上限，[what] 是可以直接写进日志的原因——构建入口统一转成「拒绝」。 */
    private class TooLarge(val what: String) : RuntimeException(what)

    /** 本地写不进去不是归档的问题：转成 [StorageWriteException]，调用方按「存储空间不足」报（批 MT M3）。
     *  暂存目录留给调用方清（syncDetailed 的 finally）。 */
    private inline fun <T> written(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw StorageWriteException(null, e)
    }

    // ── 归档里的目录约定 ──

    private val CAT_ALT = AnipSync.CATEGORIES.joinToString("|") { Regex.escape(it) }
    private val ICONS_MANIFEST = Regex("^(?:(.*/))?icons/($CAT_ALT)/manifest\\.json$")
    private val DIRECT_MANIFEST = Regex("^(?:(.*/))?($CAT_ALT)/manifest\\.json$")

    /** [root] 是归档根目录前缀（含结尾斜杠，没有外层目录时为空串）。 */
    private class Layout(val root: String, val hasIconsDir: Boolean) {
        fun manifest(category: String): String =
            root + (if (hasIconsDir) "icons/" else "") + category + "/manifest.json"

        fun resource(category: String, name: String, ext: String): String =
            root + (if (hasIconsDir) "icons/" else "") + category + "/res/$name.$ext"
    }

    /** 清单里的一个条目，继承还没解析。[label] 是清单里的原值（字符串或 {语言: 名称} 对象）。 */
    private class RawRule(val label: Any?, val target: String?, val format: String?, val contributor: String?) {
        val isAlias: Boolean get() = target != null
    }

    /** 继承解析完的条目：[resourcePkg] 是真正拥有图片文件的那个包。 */
    private class Resolved(val resourcePkg: String, val format: String?, val label: Any?, val contributor: String?)

    private class Image(val png: ByteArray, val fromSvg: Boolean)

    /**
     * 构建。归档根目录自动探测：官方归档是 android-notification-icon-project-main/，
     * 自定义归档（fork、别的分支名、或没有外层目录）前缀不同——修复批之前根目录
     * 名是写死的，自定义地址即使传进来也解析不出东西（X-17）。
     *
     * @param rasterizer SVG → PNG。默认不支持（SVG 条目按读不出处理）；生产实现由 AnipSync 注入。
     */
    fun build(
        zipFile: File,
        oldDir: File?,
        staging: File,
        preferZh: Boolean = Locale.getDefault().language == "zh",
        rasterizer: IconRasterizer = NO_RASTERIZER,
        limits: Limits = DEFAULT_LIMITS
    ): Result {
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) throw StorageWriteException("cannot create staging dir")
        // 拒绝 = 不留下半成品（调用方拿到 Rejected 时暂存目录已清空）
        fun reject(reason: String): Result.Rejected {
            staging.deleteRecursively()
            return Result.Rejected(reason)
        }
        if (zipFile.length() > limits.archiveBytes) return reject("archive too large (${zipFile.length()} bytes)")

        // ZipFile 打开时必须读到尾部的中央目录——被截断的归档在这里就被拒绝
        val zip = try {
            ZipFile(zipFile)
        } catch (e: IOException) {
            return reject("not a complete zip (${e.javaClass.simpleName}: ${e.message})")
        }
        try {
            if (zip.size() > limits.archiveEntries) return reject("archive has too many entries (${zip.size()})")
            val layout = detectLayout(zip) ?: return reject("no ANIP category manifest found")
            val old = oldDir?.takeIf { it.isDirectory }
            val read = Reader(zip, layout, rasterizer, limits)

            val items = ArrayList<Item>()
            val seen = HashSet<String>()
            var declared = 0
            var written = 0
            var carriedOver = 0
            var missing = 0
            var rendered = 0
            var aliased = 0
            var unresolved = 0
            var renderFailed = 0
            val categoriesSeen = HashSet<String>()

            for (category in AnipSync.CATEGORIES) {
                val manifest = zip.getEntry(layout.manifest(category))
                    ?: continue // 清单条目不存在 = 上游没有这个类别（见下面的整类保护）
                categoriesSeen.add(category)
                val text = try {
                    read.text(manifest)
                } catch (e: TooLarge) {
                    return reject(e.what)
                } ?: return reject("manifest $category unreadable")
                val obj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return reject("manifest $category unparsable (${e.message})")
                } catch (_: StackOverflowError) {
                    // 超深嵌套的 JSON 把递归下降的解析器的栈打穿了。是这份清单的问题，不是进程的问题——
                    // 不接住的话同步线程整个崩掉（盲审 BU-08）。
                    return reject("manifest $category unparsable (nesting too deep)")
                }
                val keys = try {
                    obj.keys().asSequence().toList()
                } catch (e: Exception) {
                    return reject("manifest $category unparsable (${e.message})")
                }
                val rules = HashMap<String, RawRule>()
                for (key in keys) rules[key] = parseRule(obj, key)

                for (key in keys) {
                    if (!PKG_KEY.matches(key) || !seen.add(key)) continue
                    declared++
                    val rule = rules.getValue(key)
                    val resolved = resolve(key, rules, HashSet())
                    if (rule.isAlias && resolved == null) unresolved++
                    val image = try {
                        resolved?.let { read.image(category, it) }
                    } catch (e: TooLarge) {
                        return reject(e.what)
                    }
                    val target = File(staging, "$key.png")
                    if (image == null && resolved != null && read.svgFailed(category, resolved.resourcePkg)) renderFailed++
                    if (image != null) {
                        written { target.writeBytes(image.png) }
                        target.setReadable(true, false)
                        when {
                            rule.isAlias -> aliased++
                            image.fromSvg -> rendered++
                            else -> written++
                        }
                    } else if (old != null && File(old, "$key.png").isFile) {
                        written { File(old, "$key.png").copyTo(target, overwrite = true) }
                        target.setReadable(true, false)
                        carriedOver++
                    } else {
                        missing++
                        continue // 旧版本里也没有：不进新快照
                    }
                    items += Item(
                        pkg = key,
                        appName = labelOf(resolved?.label, preferZh),
                        contributor = resolved?.contributor ?: "",
                        category = category
                    )
                }
            }

            // 整类保护：上一个版本里有、这次归档里整个类别的清单都不见了。分不清是上游撤销了
            // 整个类别，还是归档不完整 / 上游改了目录。按「读取失败」处理——沿用旧版本里
            // 这一类的图标，不当作撤销（撤销会一路扩散到通知栏读取的共享目录）。
            // 只有上一个版本也记录了类别（本版写出的快照）才认得出来。
            val vanished = oldCategories(old).filterKeys { it in AnipSync.CATEGORIES && it !in categoriesSeen }
            for ((_, entries) in vanished) {
                for (meta in entries) {
                    val pkg = meta.getString("packageName")
                    if (!PKG_KEY.matches(pkg) || !seen.add(pkg)) continue
                    val source = old?.let { File(it, "$pkg.png") } ?: continue
                    if (!source.isFile) continue
                    val target = File(staging, "$pkg.png")
                    written { source.copyTo(target, overwrite = true) }
                    target.setReadable(true, false)
                    carriedOver++
                    items += Item(
                        pkg = pkg,
                        appName = meta.optString("appName", ""),
                        contributor = meta.optString("contributorName", ""),
                        category = meta.getString("category")
                    )
                }
            }

            if (items.isEmpty()) {
                return reject(
                    "snapshot empty (declared $declared, written $written, rendered $rendered, aliased $aliased, " +
                    "carried over $carriedOver, missing $missing, unresolved $unresolved, render failed $renderFailed)"
                )
            }
            writeMeta(staging, items)
            return Result.Built(
                items,
                Stats(declared, written, carriedOver, missing, rendered, aliased, unresolved, renderFailed)
            )
        } finally {
            zip.close()
        }
    }

    /** 整个归档范围内的读取器：按归档里的登记长度与 CRC-32 复核每个条目、累计读出的总量、缓存取到的图。 */
    private class Reader(
        val zip: ZipFile,
        val layout: Layout,
        val rasterizer: IconRasterizer,
        val limits: Limits
    ) {
        /** 读出来的字节总量。上限针对整个归档，所以跨类别累计。 */
        var total = 0L
            private set

        /** 缓存键是「类别 + 资源包名」：别名的目标只在本类别的清单里找，同名包在别的类别可以是另一张图。 */
        private val images = HashMap<String, Image?>()

        /** 渲染不了的 SVG 资源（同样的缓存键）。别名指向一个渲染不了的 SVG 时，别名条目也记在里头。 */
        private val failedSvg = HashSet<String>()

        fun svgFailed(category: String, resourcePkg: String): Boolean = "$category/$resourcePkg" in failedSvg

        /** 清单原文。缺失、读不出 → null；超过清单上限 → 抛 [TooLarge]（整体拒绝，不是「这一个包读不出」）。 */
        fun text(entry: ZipEntry): String? {
            if (entry.size > limits.manifestBytes) throw TooLarge("manifest too large (${entry.name}, ${entry.size} bytes)")
            return read(entry, limits.manifestBytes)?.toString(Charsets.UTF_8)
        }

        /**
         * [resolved] 指向的图（位图原样，SVG 渲染成 PNG）。同一个资源包只取一次——
         * 上百个别名指向十几个目标，不该各读各的。取不到 → null；总量超限 → 抛 [TooLarge]。
         */
        fun image(category: String, resolved: Resolved): Image? {
            val cacheKey = "$category/${resolved.resourcePkg}"
            if (images.containsKey(cacheKey)) return images[cacheKey]
            val got = obtain(category, resolved)
            images[cacheKey] = got
            return got
        }

        private fun obtain(category: String, resolved: Resolved): Image? {
            // 没写 format（老归档）或不认识的 format：两种扩展名都试，位图优先
            val exts = when (val f = resolved.format) {
                "svg" -> listOf("svg")
                "png", "jpg", "jpeg", "gif", "webp" -> listOf(f)
                else -> listOf("png", "svg")
            }
            for (ext in exts) {
                val entry = zip.getEntry(layout.resource(category, resolved.resourcePkg, ext)) ?: continue
                val bytes = read(entry, limits.resourceBytes) ?: return null // 条目在但读不出：不再试别的扩展名
                if (ext != "svg") return if (looksLikeImage(bytes)) Image(bytes, fromSvg = false) else null
                // gzip 包裹的 SVG 不交给渲染器：解压之后的 XML 不受「单个条目不超过上限」的约束。
                // 渲染器的约定是「渲染不了返回 null」；抛异常同样按渲染不了处理，不让它打断整次同步。
                failedSvg += "$category/${resolved.resourcePkg}"
                val png = if (isGzip(bytes)) null else try {
                    rasterizer.renderPng(bytes, limits.iconDimension)
                } catch (_: Exception) {
                    null
                }
                return png?.takeIf { looksLikeImage(it) }?.let { Image(it, fromSvg = true) }
            }
            return null
        }

        /** 读一个条目，最多读到上限为止。缺失、读不出、超过上限、数据与登记不符 → null。 */
        private fun read(entry: ZipEntry, maxBytes: Long): ByteArray? {
            if (entry.isDirectory) return null
            if (entry.size > maxBytes) return null
            val bytes = try {
                readAtMost(entry, maxBytes)
            } catch (_: IOException) {
                return null
            }
            if (bytes.size.toLong() > maxBytes) return null
            total += bytes.size
            if (total > limits.totalUncompressedBytes) throw TooLarge("archive uncompressed size exceeds limit ($total bytes)")
            if (entry.size >= 0 && bytes.size.toLong() != entry.size) return null
            if (entry.crc >= 0L && CRC32().apply { update(bytes) }.value != entry.crc) return null
            return bytes
        }

        /** 不用 InputStream.readNBytes：它要到 API 33 才有，本模块 minSdk 31。最多读 maxBytes + 1 个字节。 */
        private fun readAtMost(entry: ZipEntry, maxBytes: Long): ByteArray {
            val out = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (out.size().toLong() <= maxBytes) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    // ── 条目解析 ──

    private fun parseRule(obj: JSONObject, key: String): RawRule {
        // 条目值读不出来（上游写坏了这一个条目）→ 按空条目处理，走下面的沿用 / 缺失规则
        val entry = try {
            obj.optJSONObject(key)
        } catch (_: Exception) {
            null
        } ?: return RawRule(null, null, null, null)
        return try {
            RawRule(
                label = entry.opt("label")?.takeIf { it != JSONObject.NULL },
                target = text(entry, "target"),
                format = text(entry, "format")?.lowercase(Locale.ROOT),
                contributor = text(entry, "contributors")
            )
        } catch (_: Exception) {
            RawRule(null, null, null, null)
        }
    }

    /** 字符串字段：缺失、JSON null、空白 → null。 */
    private fun text(entry: JSONObject, name: String): String? {
        val value = entry.opt(name) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString().trim().ifEmpty { null }
    }

    /**
     * 解析继承：别名一路追到真正拥有图片文件的那个包，省略的属性取目标的。
     * 目标不存在、目标不是合法包名、成环、链太长 → null。只在本类别的清单里找（上游的约定）。
     * 递归有深度上限：一份恶意清单可以写十万个首尾相接的别名，不设限就把栈打穿（盲审 BU-08）。
     */
    private const val MAX_ALIAS_DEPTH = 16

    private fun resolve(key: String, rules: Map<String, RawRule>, stack: MutableSet<String>): Resolved? =
        resolve(key, rules, stack, 0)

    private fun resolve(key: String, rules: Map<String, RawRule>, stack: MutableSet<String>, depth: Int): Resolved? {
        val rule = rules[key] ?: return null
        if (!stack.add(key)) return null // 成环
        if (depth > MAX_ALIAS_DEPTH) return null // 链太长
        if (!rule.isAlias) return Resolved(key, rule.format, rule.label, rule.contributor)
        val target = rule.target ?: return null
        if (!PKG_KEY.matches(target)) return null
        val parent = resolve(target, rules, stack, depth + 1) ?: return null
        return Resolved(parent.resourcePkg, parent.format, rule.label ?: parent.label, rule.contributor ?: parent.contributor)
    }

    private fun isGzip(b: ByteArray): Boolean = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    /** 上一个版本里每个类别各有哪些包（取自旧目录的 meta.json）。旧快照没记类别时返回空。 */
    private fun oldCategories(old: File?): Map<String, List<JSONObject>> {
        old ?: return emptyMap()
        val meta = File(old, RuleLibrary.META_FILE)
        if (!meta.isFile) return emptyMap()
        return try {
            val arr = JSONObject(meta.readText(Charsets.UTF_8)).optJSONArray("apps") ?: return emptyMap()
            val out = HashMap<String, MutableList<JSONObject>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val category = obj.optString("category", "")
                if (category.isNotEmpty()) out.getOrPut(category) { ArrayList() }.add(obj)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 归档根目录前缀（含结尾斜杠，没有外层目录时为空串）与目录约定。
     * 两种目录约定一起比：取类别清单最多的那个「根前缀 + 约定」——整库 zipball 的 icons/… 与官方资源包的
     * 直接布局同时出现时也是这个规则。并列时 icons/ 这一层优先，再取先出现的。
     */
    private fun detectLayout(zip: ZipFile): Layout? {
        val entries = zip.entries()
        val names = ArrayList<String>()
        while (entries.hasMoreElements()) names += entries.nextElement().name
        var best: Layout? = null
        var bestCount = 0
        for ((pattern, hasIconsDir) in listOf(ICONS_MANIFEST to true, DIRECT_MANIFEST to false)) {
            val countByRoot = LinkedHashMap<String, Int>()
            for (name in names) {
                val root = pattern.matchEntire(name)?.groupValues?.get(1) ?: continue
                countByRoot[root] = (countByRoot[root] ?: 0) + 1
            }
            val (root, count) = countByRoot.maxByOrNull { it.value }?.toPair() ?: continue
            if (count > bestCount) {
                best = Layout(root, hasIconsDir)
                bestCount = count
            }
        }
        return best
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
                    // 记录类别：下一次同步时若整个类别的清单从归档里消失，据此沿用旧图标
                    put("category", it.category)
                }
            )
        }
        val metaJson = JSONObject().apply { put("apps", metaArr) }.toString()
        // meta 与图标同处暂存目录，随切换整体生效
        val tmp = File(staging, "meta.json.tmp")
        val metaFile = File(staging, RuleLibrary.META_FILE)
        written {
            tmp.writeText(metaJson, Charsets.UTF_8)
            if (!tmp.renameTo(metaFile)) metaFile.writeText(metaJson, Charsets.UTF_8)
        }
    }
}
