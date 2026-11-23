package io.github.deserthouse.opticon.util

import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.engine.AnipSync
import org.json.JSONArray

/**
 * SourceMigration — 自定义订阅源的一次性格式迁移与选中指针校正（纯 JVM）。
 *
 * 修复批（X-01 / X-02 / X-14）之前迁移内联在 PreferenceManager 里，规则是
 * 「所有记录按地址推断：ANIP 基址下 = ANIP，其余一律 Legacy JSON」，带来两个问题：
 *  - 升级前已有的自定义应用图标源被归成 Legacy JSON，从应用图标源列表消失、
 *    选中被重置（地址推断认不出 zipball）；
 *  - 迁移由「第一次读到自定义源」触发，把用户**刚添加**的源所选的格式也覆盖掉。
 *
 * 现在的规则：
 *  - 只迁移**缺格式字段**的旧记录；带格式字段的（包括已经是「待确认」的）原样保留。
 *  - 旧记录的格式按证据推断，证据不足就标「待确认」，不猜：
 *      升级前被选为当前应用图标源   → PICP 整库包
 *      地址在 ANIP 基址下           → ANIP 资源包（旧版就是按这个前缀路由的）
 *      升级前被选为当前通知图标源   → 旧版 JSON（同上）
 *      其余                         → 待确认（两个列表都显示，等用户选定）
 *  - 选中指针校正：选中的源必须仍在对应当的列表里，否则回到内置源。
 */
internal object SourceMigration {

    data class Pointers(val activeAnia: String, val activePicp: String)

    data class Outcome(
        /** 迁移后的自定义源 JSON；没有记录被改写时就是输入原串。 */
        val rawJson: String?,
        val pointers: Pointers,
        /** 是否有东西被改写（源记录或指针）。 */
        val changed: Boolean
    )

    /** 通知图标源列表接受的格式；待确认的源两个列表都显示。 */
    val ANIA_FORMATS = setOf(SourceFormat.ANIP_BUNDLE, SourceFormat.LEGACY_JSON, SourceFormat.UNSPECIFIED)

    /** 应用图标源列表接受的格式。 */
    val PICP_FORMATS = setOf(SourceFormat.PICP_ZIPBALL, SourceFormat.UNSPECIFIED)

    /**
     * 对落盘的自定义源做一次迁移并校正指针。输入输出都是原始 JSON，不依赖
     * SharedPreferences——落盘由调用方负责。
     */
    fun migrate(
        rawJson: String?,
        pointers: Pointers,
        anipBase: String = AnipSync.BASE,
        builtinAnia: String = "ania_raw",
        builtinPicp: String = "picp_github"
    ): Outcome {
        if (rawJson == null) return Outcome(null, pointers, changed = false)
        val arr = try {
            JSONArray(rawJson)
        } catch (_: Exception) {
            // 落盘内容损坏：原样交回，由读取方按「没有自定义源」处理
            return Outcome(rawJson, pointers, changed = false)
        }
        val formats = HashMap<String, SourceFormat>()
        var sourcesChanged = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isEmpty()) continue
            val format = if (obj.has("format")) {
                // 带格式字段：认识的格式原样保留；不认识的（损坏 / 将来新增）记为待确认
                val stored = obj.optString("format", "")
                val parsed = parseFormat(stored)
                if (parsed.name != stored) {
                    obj.put("format", parsed.name)
                    sourcesChanged = true
                }
                parsed
            } else {
                val inferred = inferFormat(id, obj.optString("url", ""), pointers, anipBase)
                obj.put("format", inferred.name)
                sourcesChanged = true
                inferred
            }
            formats[id] = format
        }
        val fixed = reconcilePointers(pointers, formats, builtinAnia, builtinPicp)
        return Outcome(
            rawJson = if (sourcesChanged) arr.toString() else rawJson,
            pointers = fixed,
            changed = sourcesChanged || fixed != pointers
        )
    }

    /** 落盘的格式名 → 枚举；不认识的记为待确认（不猜成某一种）。 */
    fun parseFormat(name: String): SourceFormat =
        SourceFormat.values().firstOrNull { it.name == name } ?: SourceFormat.UNSPECIFIED

    private fun inferFormat(id: String, url: String, pointers: Pointers, anipBase: String): SourceFormat =
        when {
            pointers.activePicp == id -> SourceFormat.PICP_ZIPBALL
            url.startsWith(anipBase) -> SourceFormat.ANIP_BUNDLE
            pointers.activeAnia == id -> SourceFormat.LEGACY_JSON
            else -> SourceFormat.UNSPECIFIED
        }

    /**
     * 选中指针校正。[formats] 是各自定义源的格式（内置源不在其中，恒视为有效）。
     */
    fun reconcilePointers(
        pointers: Pointers,
        formats: Map<String, SourceFormat>,
        builtinAnia: String = "ania_raw",
        builtinPicp: String = "picp_github"
    ): Pointers = Pointers(
        activeAnia = fix(pointers.activeAnia, builtinAnia, formats) { it in ANIA_FORMATS },
        activePicp = fix(pointers.activePicp, builtinPicp, formats) { it in PICP_FORMATS }
    )

    private inline fun fix(
        active: String,
        builtin: String,
        formats: Map<String, SourceFormat>,
        accepts: (SourceFormat) -> Boolean
    ): String {
        if (active == builtin) return builtin
        val format = formats[active] ?: return builtin // 悬空：选中的源已经不存在
        return if (accepts(format)) active else builtin // 源还在，但它的格式不再属于这个列表
    }
}
