package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用 ANIP 资源包夹具：按上游的目录约定构造归档。
 *
 *   {root}icons/<类别>/manifest.json
 *   {root}icons/<类别>/res/<包名>.png
 */
internal object AnipZipFixtures {

    const val OFFICIAL_ROOT = "android-notification-icon-project-main/"

    /** 清单里声明的一个包。[png] 为 null = 清单声明了、归档里却没有这个资源。 */
    class Declared(
        val pkg: String,
        val zh: String = "",
        val en: String = "",
        val png: ByteArray? = pngOf(pkg)
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

    private fun manifestText(cat: Category): String {
        val entries = ArrayList<String>()
        cat.declared.forEach { d ->
            val value = JSONObject().apply {
                put("label", JSONObject().put("zh-CN", d.zh).put("en", d.en))
                put("contributors", "someone")
            }
            entries += JSONObject.quote(d.pkg) + ": " + value
        }
        cat.rawEntries.forEach { (key, raw) -> entries += JSONObject.quote(key) + ": " + raw }
        return entries.joinToString(separator = ",\n", prefix = "{", postfix = "}")
    }

    fun build(root: String = OFFICIAL_ROOT, categories: List<Category>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            categories.forEach { cat ->
                zos.putNextEntry(ZipEntry(root + "icons/" + cat.name + "/manifest.json"))
                zos.write((cat.manifestRaw ?: manifestText(cat)).toByteArray())
                zos.closeEntry()
                cat.declared.filter { it.png != null }.forEach { d ->
                    zos.putNextEntry(ZipEntry(root + "icons/" + cat.name + "/res/" + d.pkg + ".png"))
                    zos.write(d.png!!)
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
