package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
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

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
