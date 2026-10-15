package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy

/**
 * AppIconConfigRepository — 完整配置的单一读写出口（阶段 2，架构报告 §3.2）
 *
 * 薄封装：底层仍走 PreferenceManager 逐键方法（SharedPreferences），但调用方
 * 只经由本仓库的 [load]/[save] 以 [AppIconConfig] 快照为单位读写。
 * 不承担生成 Bitmap、操作页面或宣布运行端生效的职责（架构报告职责表）。
 *
 * OI-05/OI-11：旧键（method_/manual_branch_）通过 legacy 映射进入新模型；
 * 新写入只使用新键。
 */
object AppIconConfigRepository {

    /** 完整快照读取：一次构造 [AppIconConfig]。
     *  旧键兼容：无 strategy 键时 getStrategy 已回落 FANKES（与既有行为一致）。 */
    fun load(pkg: String): AppIconConfig {
        val params = RedrawParams(
            PreferenceManager.getScale(pkg),
            PreferenceManager.getOffsetX(pkg),
            PreferenceManager.getOffsetY(pkg),
            PreferenceManager.getThreshold(pkg),
            PreferenceManager.getRadius(pkg)
        )
        return AppIconConfig(
            pkg = pkg,
            enabled = PreferenceManager.isMethodEnabled(pkg),
            strategy = PreferenceManager.getStrategy(pkg),
            assetSubStrategy = PreferenceManager.getAssetSubStrategy(pkg)
                ?.let { v -> runCatching { AssetSubStrategy.valueOf(v) }.getOrNull() },
            algoSource = PreferenceManager.getAlgoSource(pkg)
                ?.let { v -> runCatching { AlgoSource.valueOf(v) }.getOrNull() },
            iconPackPkg = PreferenceManager.getSelectedIconPack(pkg),
            packDrawable = PreferenceManager.getSelectedPackDrawable(pkg),
            customIconPath = PreferenceManager.getCustomIconPath(pkg),
            materialIconName = PreferenceManager.getMaterialIconName(pkg),
            emojiText = PreferenceManager.getEmojiText(pkg),
            params = params,
        )
    }

    /** 完整快照写入：逐键写入（SharedPreferences 单键已原子）。
     *  返回 true=全部键写入成功。 */
    fun save(config: AppIconConfig): Boolean {
        PreferenceManager.setMethodEnabled(config.pkg, config.enabled)
        PreferenceManager.setStrategy(config.pkg, config.strategy)
        PreferenceManager.setAssetSubStrategy(config.pkg, config.assetSubStrategy?.name)
        PreferenceManager.setAlgoSource(config.pkg, config.algoSource?.name)
        PreferenceManager.setSelectedIconPack(config.pkg, config.iconPackPkg, config.packDrawable)
        PreferenceManager.setCustomIconPath(config.pkg, config.customIconPath)
        PreferenceManager.setMaterialIconName(config.pkg, config.materialIconName)
        PreferenceManager.setEmojiText(config.pkg, config.emojiText)
        PreferenceManager.setScale(config.pkg, config.params.scale)
        PreferenceManager.setThreshold(config.pkg, config.params.threshold)
        PreferenceManager.setOffsetX(config.pkg, config.params.offsetX)
        PreferenceManager.setOffsetY(config.pkg, config.params.offsetY)
        PreferenceManager.setRadius(config.pkg, config.params.radius)
        return true
    }
}
