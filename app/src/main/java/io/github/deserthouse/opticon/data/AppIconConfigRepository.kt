package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.state.ModificationSource
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger

/**
 * AppIconConfigRepository — 完整配置的单一读写出口（阶段 2，架构报告 §3.2；F1-2 接线生产）
 *
 * 职责：
 * - [load]：一次读全键构造快照；legacy method_ 键自动迁移（幂等）
 * - [save]：validate 通过后单 Editor 事务写入，commit() 返回值即成败
 * - [saveJson]：恢复路径的 JSON 提交入口（PublishTransaction recover 回调）
 * - [loadModificationSource]：列表徽标派生（OI-11 语义经仓库出口）
 *
 * 不承担生成 Bitmap、操作页面或宣布运行端生效的职责（架构报告职责表）。
 */
object AppIconConfigRepository {
    private const val TAG = "OptIcon/ConfigRepo"

    /** 完整快照读取：一次构造 [AppIconConfig]；进入前先做 legacy 迁移（幂等）。 */
    fun load(pkg: String): AppIconConfig {
        migrateLegacyIfNeeded(pkg)
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

    /**
     * 完整快照写入：validate 通过后单 Editor 事务（commit 同步返回值即成败）。
     * 返回 false = 校验拒绝或持久化失败——调用方必须视为保存失败，不得继续。
     */
    fun save(config: AppIconConfig): Boolean {
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            TraceLogger.e(TAG, "save rejected for ${config.pkg}: $errors")
            return false
        }
        return PreferenceManager.editConfig { e ->
            val pkg = config.pkg
            e.putBoolean(PreferenceManager.keyMethodEnabled(pkg), config.enabled)
            e.putString(PreferenceManager.keyStrategy(pkg), config.strategy.name)
            // 可空来源字段与既有逐键 setter 同语义：null → remove 键
            config.assetSubStrategy?.name?.let { e.putString(PreferenceManager.keyAssetSub(pkg), it) }
                ?: e.remove(PreferenceManager.keyAssetSub(pkg))
            config.algoSource?.name?.let { e.putString(PreferenceManager.keyAlgoSource(pkg), it) }
                ?: e.remove(PreferenceManager.keyAlgoSource(pkg))
            if (config.iconPackPkg == null) {
                e.remove(PreferenceManager.keyIconPack(pkg))
                e.remove(PreferenceManager.keyPackDrawable(pkg))
            } else {
                e.putString(PreferenceManager.keyIconPack(pkg), config.iconPackPkg)
                e.putString(PreferenceManager.keyPackDrawable(pkg), config.packDrawable ?: "")
            }
            e.putString(PreferenceManager.keyCustomPath(pkg), config.customIconPath)
            e.putString(PreferenceManager.keyMaterialIcon(pkg), config.materialIconName)
            e.putString(PreferenceManager.keyEmojiText(pkg), config.emojiText)
            e.putFloat(PreferenceManager.keyScale(pkg), config.params.scale)
            e.putInt(PreferenceManager.keyThreshold(pkg), config.params.threshold)
            e.putFloat(PreferenceManager.keyOffsetX(pkg), config.params.offsetX)
            e.putFloat(PreferenceManager.keyOffsetY(pkg), config.params.offsetY)
            e.putFloat(PreferenceManager.keyRadius(pkg), config.params.radius)
        }
    }

    /** 恢复路径的 JSON 提交入口；解析失败返回 false（recover 侧保留记录为 Partial）。 */
    fun saveJson(json: String): Boolean =
        AppIconConfig.fromJson(json)?.let { save(it) } ?: false

    /** 列表徽标派生（OI-11：当前策略模型派生，经仓库出口；A06 接线点之三）。 */
    fun loadModificationSource(pkg: String): ModificationSource {
        migrateLegacyIfNeeded(pkg)
        if (!PreferenceManager.isMethodEnabled(pkg)) return ModificationSource.NONE
        return when (PreferenceManager.getStrategy(pkg)) {
            IconStrategy.FANKES -> ModificationSource.ICON_LIBRARY
            IconStrategy.ALGORITHM -> ModificationSource.ALGORITHM
            IconStrategy.ASSET_IMPORT -> ModificationSource.CUSTOM
        }
    }

    /**
     * legacy method_ 键 → 新模型迁移（幂等：写回 strategy 键后自然短路）。
     * REMOTE_SUB→FANKES / SELF_FILTER→ALGORITHM(SELF) / ICON_PACK→ASSET_IMPORT
     * +asset_sub=ICON_PACK（icon_pack 键留空走既有 auto-scan——保留用户来源
     * 意图，不默认改自适应分层，C10）/ AUTO(或其他)→FANKES+off。
     * 显式 method_enabled 键优先于映射默认（C10：不覆盖用户明确关闭）。
     * 无 strategy 键且无 method_ 键 = 全新安装，不写任何键。
     */
    private fun migrateLegacyIfNeeded(pkg: String) {
        if (PreferenceManager.hasStrategyKey(pkg)) return
        val legacy = PreferenceManager.getLegacyMethodRaw(pkg) ?: return
        val mapped = when (legacy) {
            "REMOTE_SUB" -> IconStrategy.FANKES to true
            "SELF_FILTER" -> IconStrategy.ALGORITHM to true
            "ICON_PACK" -> IconStrategy.ASSET_IMPORT to true
            else -> IconStrategy.FANKES to false
        }
        val explicitEnabled = PreferenceManager.hasExplicitEnabledKey(pkg)
        val enabled = if (explicitEnabled) PreferenceManager.isMethodEnabled(pkg) else mapped.second
        val wrote = PreferenceManager.editConfig { e ->
            e.putString(PreferenceManager.keyStrategy(pkg), mapped.first.name)
            e.putBoolean(PreferenceManager.keyMethodEnabled(pkg), enabled)
            if (mapped.first == IconStrategy.ALGORITHM) {
                e.putString(PreferenceManager.keyAlgoSource(pkg), AlgoSource.SELF.name)
            }
            if (legacy == "ICON_PACK") {
                e.putString(PreferenceManager.keyAssetSub(pkg), AssetSubStrategy.ICON_PACK.name)
            }
        }
        TraceLogger.i(TAG, "legacy config migrated for $pkg: $legacy → ${mapped.first} (explicitEnabled=$explicitEnabled, commit=$wrote)")
    }
}
