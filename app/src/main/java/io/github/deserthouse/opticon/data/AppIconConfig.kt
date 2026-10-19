package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.engine.publish.jsonGetString
import io.github.deserthouse.opticon.engine.publish.jsonObj
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy

/**
 * AppIconConfig — 每应用完整配置快照（阶段 2，架构报告 §3.2）
 *
 * 与 UI 页面状态分离：不含 Bitmap、加载标记、弹窗状态。
 * 一条 [AppIconConfig] 即一次"已提交配置"的最小完整表示，仓库（[AppIconConfigRepository]）
 * 以它为唯一读写单位。
 *
 * 来源引用使用带类型的变体：strategy 决定哪些来源字段有意义（模型层 validate 校验，
 * 不只靠 UI 隐藏控件）。
 */
data class AppIconConfig(
    val pkg: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val enabled: Boolean = false,
    val strategy: IconStrategy = IconStrategy.FANKES,
    /** strategy=ASSET_IMPORT 时有意义 */
    val assetSubStrategy: AssetSubStrategy? = null,
    /** strategy=ALGORITHM 且 algoSource=SELF 时有意义 */
    val algoSource: AlgoSource? = null,
    /** strategy=ASSET_IMPORT 且 assetSubStrategy=ICON_PACK 时有意义 */
    val iconPackPkg: String? = null,
    /** 同上，与 iconPackPkg 成对 */
    val packDrawable: String? = null,
    /** strategy=ALGORITHM 且 algoSource=LOCAL_FILE 时有意义 */
    val customIconPath: String? = null,
    /** strategy=ALGORITHM 且 algoSource=MATERIAL_LIB 时有意义 */
    val materialIconName: String? = null,
    /** strategy=ALGORITHM 且 algoSource=EMOJI_TEXT 时有意义 */
    val emojiText: String? = null,
    val params: RedrawParams = RedrawParams()
) {

    /** 返回所有违反类型约束的描述；空列表=配置合法。
     *  规则来源：架构报告 §3.2「来源引用使用带类型的变体…不同输入允许什么参数由模型校验」。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (pkg.isBlank()) errors += "pkg is blank"
        if (strategy == IconStrategy.ASSET_IMPORT) {
            if (assetSubStrategy == null) errors += "ASSET_IMPORT requires assetSubStrategy"
            if (assetSubStrategy == AssetSubStrategy.ICON_PACK) {
                if (iconPackPkg.isNullOrBlank()) errors += "ICON_PACK requires iconPackPkg"
                if (packDrawable.isNullOrBlank()) errors += "ICON_PACK requires packDrawable"
            }
        }
        if (strategy == IconStrategy.ALGORITHM) {
            if (algoSource == null) errors += "ALGORITHM requires algoSource"
            if (algoSource == AlgoSource.LOCAL_FILE && customIconPath.isNullOrBlank())
                errors += "LOCAL_FILE requires customIconPath"
            if (algoSource == AlgoSource.MATERIAL_LIB && materialIconName.isNullOrBlank())
                errors += "MATERIAL_LIB requires materialIconName"
            if (algoSource == AlgoSource.EMOJI_TEXT && emojiText.isNullOrBlank())
                errors += "EMOJI_TEXT requires emojiText"
        }
        return errors
    }

    /** 序列化为单行 JSON（PublishTransaction 快照与恢复的载体；手写 JsonIo，org.json 在 JVM 测试为 stub） */
    fun toJson(): String = jsonObj(
        "pkg" to pkg,
        "sv" to schemaVersion.toString(),
        "enabled" to enabled.toString(),
        "strategy" to strategy.name,
        "sub" to assetSubStrategy?.name,
        "src" to algoSource?.name,
        "pack" to iconPackPkg,
        "drawable" to packDrawable,
        "path" to customIconPath,
        "mat" to materialIconName,
        "emoji" to emojiText,
        "scale" to params.scale.toString(),
        "thr" to params.threshold.toString(),
        "offx" to params.offsetX.toString(),
        "offy" to params.offsetY.toString(),
        "rad" to params.radius.toString()
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** 反序列化；pkg 缺失或结构非法返回 null（恢复路径按 Partial 保留记录，不猜） */
        fun fromJson(json: String): AppIconConfig? = try {
            val pkg = jsonGetString(json, "pkg")?.takeIf { it.isNotBlank() } ?: return null
            val d = RedrawParams()
            AppIconConfig(
                pkg = pkg,
                schemaVersion = jsonGetString(json, "sv")?.toIntOrNull() ?: CURRENT_SCHEMA_VERSION,
                enabled = jsonGetString(json, "enabled") == "true",
                strategy = jsonGetString(json, "strategy")
                    ?.let { v -> runCatching { IconStrategy.valueOf(v) }.getOrNull() }
                    ?: IconStrategy.FANKES,
                assetSubStrategy = jsonGetString(json, "sub")
                    ?.let { v -> runCatching { AssetSubStrategy.valueOf(v) }.getOrNull() },
                algoSource = jsonGetString(json, "src")
                    ?.let { v -> runCatching { AlgoSource.valueOf(v) }.getOrNull() },
                iconPackPkg = jsonGetString(json, "pack")?.takeIf { it.isNotBlank() },
                packDrawable = jsonGetString(json, "drawable")?.takeIf { it.isNotBlank() },
                customIconPath = jsonGetString(json, "path")?.takeIf { it.isNotBlank() },
                materialIconName = jsonGetString(json, "mat")?.takeIf { it.isNotBlank() },
                emojiText = jsonGetString(json, "emoji")?.takeIf { it.isNotBlank() },
                params = d.copy(
                    scale = jsonGetString(json, "scale")?.toFloatOrNull() ?: d.scale,
                    offsetX = jsonGetString(json, "offx")?.toFloatOrNull() ?: d.offsetX,
                    offsetY = jsonGetString(json, "offy")?.toFloatOrNull() ?: d.offsetY,
                    threshold = jsonGetString(json, "thr")?.toIntOrNull() ?: d.threshold,
                    radius = jsonGetString(json, "rad")?.toFloatOrNull() ?: d.radius
                )
            )
        } catch (_: Exception) {
            null
        }
    }
}
