package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用 ANIP 资源包夹具：按上游的目录约定构造归档。
 *
 *   {root}icons/<类别>/manifest.json          整库 zipball（[iconsDir] = true，默认）
 *   {root}<类别>/manifest.json                上游官方资源包（[iconsDir] = false，没有 icons/ 这一层）
 *   …/<类别>/res/<包名>.<png | svg>
 *
 * 清单条目三种写法都能造（批 UP）：自有位图（不写 format 或写 png）、SVG（format = svg）、
 * 别名（target = 另一个包，自己没有图）。
 */
internal object AnipZipFixtures {

    const val OFFICIAL_ROOT = "android-notification-icon-project-main/"

    class Declared(
        val pkg: String,
        val zh: String = "",
        val en: String = "",
        /** 资源文件的内容；null = 清单声明了、归档里却没有这个资源。 */
        val png: ByteArray? = pngOf(pkg),
        /** 资源文件的扩展名（png 或 svg）；[png] 是它的内容。 */
        val ext: String = "png",
        /** 写进清单的 format；null = 不写这个字段。 */
        val format: String? = null,
        /** 别名：写进清单的 target，并且不写 format。 */
        val target: String? = null,
        /** 写进清单的 contributors；null = 不写（用来验证别名继承目标的贡献者）。 */
        val contributors: String? = "someone"
    )

    class Category(
        val name: String,
        val declared: List<Declared> = emptyList(),
        /** 直接给出清单原文（用来构造解析不了的清单）。 */
        val manifestRaw: String? = null,
        /** 清单里以原始 JSON 记号给出的条目（用来构造写坏了的单个条目）。 */
        val rawEntries: Map<String, String> = emptyMap()
    )

    fun pngOf(tag: String): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A
    ) + "png-of-$tag".toByteArray()

    /** 一份最小的 SVG：上游的图标是单色字形，测试里不需要真的能画。 */
    fun svgOf(tag: String): ByteArray =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"72\" height=\"72\"><title>$tag</title></svg>".toByteArray()

    private fun entryText(d: Declared): String {
        val fields = ArrayList<String>()
        // zh 与 en 都是空 = 这一条不带标签字段（别名省略标签时继承目标的）
        if (d.zh.isNotEmpty() || d.en.isNotEmpty()) {
            fields += "\"label\": " + JSONObject().put("zh-CN", d.zh).put("en", d.en)
        }
        if (d.target != null) fields += "\"target\": ${JSONObject.quote(d.target)}"
        if (d.format != null) fields += "\"format\": ${JSONObject.quote(d.format)}"
        d.contributors?.let { fields += "\"contributors\": ${JSONObject.quote(it)}" }
        return "{${fields.joinToString(", ")}}"
    }

    private fun manifestText(cat: Category): String {
        val entries = ArrayList<String>()
        cat.declared.forEach { d -> entries += JSONObject.quote(d.pkg) + ": " + entryText(d) }
        cat.rawEntries.forEach { (key, raw) -> entries += JSONObject.quote(key) + ": " + raw }
        return entries.joinToString(separator = ",\n", prefix = "{", postfix = "}")
    }

    fun build(root: String = OFFICIAL_ROOT, categories: List<Category>, iconsDir: Boolean = true): ByteArray {
        val prefix = root + if (iconsDir) "icons/" else ""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            categories.forEach { cat ->
                zos.putNextEntry(ZipEntry(prefix + cat.name + "/manifest.json"))
                zos.write((cat.manifestRaw ?: manifestText(cat)).toByteArray())
                zos.closeEntry()
                cat.declared.filter { it.png != null }.forEach { d ->
                    zos.putNextEntry(ZipEntry(prefix + cat.name + "/res/" + d.pkg + "." + d.ext))
                    zos.write(d.png!!)
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
