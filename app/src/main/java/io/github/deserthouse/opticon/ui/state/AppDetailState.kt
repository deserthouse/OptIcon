package io.github.deserthouse.opticon.ui.state

import io.github.deserthouse.opticon.engine.HitSource
import io.github.deserthouse.opticon.R

import android.graphics.Bitmap
import io.github.deserthouse.opticon.engine.IconPackEngine.IconPackInfo
import io.github.deserthouse.opticon.engine.RedrawParams

/**
 * AppDetailState — Detail page UI state model (2-strategy offline)
 */
data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    val isSystemApp: Boolean = false,

    // Master Switch
    val methodEnabled: Boolean = false,

    // 2 strategies (offline only)
    val strategy: IconStrategy = IconStrategy.FANKES,

    // Strategy 1: Asset Import
    val assetSubStrategy: AssetSubStrategy = AssetSubStrategy.ADAPTIVE_DECOMPOSE,
    val iconPacks: List<IconPackInfo> = emptyList(),
    /** C11（X8）：包清单是否已加载完成——空列表时区分"加载中"与"确无图标包" */
    val iconPacksLoaded: Boolean = false,
    val selectedIconPack: String? = null,
    val selectedPackIconDrawable: String? = null,
    val adaptiveForeground: Bitmap? = null,
    val adaptiveBackground: Bitmap? = null,
    val isAdaptiveIcon: Boolean? = null,
    val perfectIconsLoading: Boolean = false,
    val perfectIconsBitmap: Bitmap? = null,

    // Strategy 2: Algorithm Overdrive
    val algoSource: AlgoSource = AlgoSource.SELF,
    val colorOverride: String? = null,
    val hasOriginalCaptured: Boolean = false,
    /** T-B2：LOCAL_FILE 的细分归属——true=抓取原图转存，false=用户上传。
     *  不入配置持久化；loadApp 以资产字节与原图文件比对自描述重建。 */
    val capturedOriginalInUse: Boolean = false,
    val customIconPath: String? = null,
    val materialIconName: String? = null,
    val emojiText: String? = null,
    val emojiUnlocked: Boolean = false,

    // Algorithm parameters (all live, exposed as sliders)
    val redrawParams: RedrawParams = RedrawParams.DEFAULT,

    // Current hit level
    val hitSource: HitSource = HitSource.OTHER,

    // Bottom sheet (strategy-2 only)
    val sheetVisible: Boolean = false,

    // Preview
    val previewBitmap: Bitmap? = null,
    /** A5.2（R2-06）：预览分态单源——Current/Updating/Missing/Rejected 收敛到
     *  EditorSessionCore.PreviewState（此前布尔双参散布+sealed 声明零消费双轨）。 */
    val previewState: io.github.deserthouse.opticon.ui.session.EditorSessionCore.PreviewState =
        io.github.deserthouse.opticon.ui.session.EditorSessionCore.PreviewState.Missing,
    val previewMode: StatusBarMode = StatusBarMode.DARK
)

// Z4（架构前置①）：领域枚举迁至 domain 包，ui.state 保留 typealias 兼容层
typealias IconStrategy = io.github.deserthouse.opticon.domain.IconStrategy
typealias AssetSubStrategy = io.github.deserthouse.opticon.domain.AssetSubStrategy
typealias AlgoSource = io.github.deserthouse.opticon.domain.AlgoSource

/** T-B2：扁平来源动作模型——UI 单层列表，底层仍映射既有三策略（不改引擎）。
 *  Upload/Picp/IconPack/MaterialLib/EmojiText 由 Screen 本地分发（SAF launcher/
 *  素材选择器/输入弹层），其余经 VM applyFlatSource 组合落稿。 */
sealed interface FlatSource {
    data object RulesLib : FlatSource
    data object AdaptiveDecompose : FlatSource
    data object Picp : FlatSource
    data object IconPack : FlatSource
    data object SelfIcon : FlatSource
    data object CapturedOriginal : FlatSource
    data object Upload : FlatSource
    data object MaterialLib : FlatSource
    data object EmojiText : FlatSource
}

/** 选中态反推的唯一事实源——SourcePickerSheet 高亮与 SourceSummaryRow 标签
 *  共用此映射，杜绝两处 when 漂移。纯函数，JVM 可测。 */
fun selectedFlatSource(
    strategy: IconStrategy,
    assetSubStrategy: AssetSubStrategy,
    algoSource: AlgoSource,
    capturedOriginalInUse: Boolean
): FlatSource = when (strategy) {
    IconStrategy.FANKES -> FlatSource.RulesLib
    IconStrategy.ASSET_IMPORT -> when (assetSubStrategy) {
        AssetSubStrategy.ADAPTIVE_DECOMPOSE -> FlatSource.AdaptiveDecompose
        AssetSubStrategy.PERFECT_ICONS -> FlatSource.Picp
        AssetSubStrategy.ICON_PACK -> FlatSource.IconPack
    }
    IconStrategy.ALGORITHM -> when (algoSource) {
        AlgoSource.SELF -> FlatSource.SelfIcon
        AlgoSource.LOCAL_FILE -> if (capturedOriginalInUse) FlatSource.CapturedOriginal else FlatSource.Upload
        AlgoSource.MATERIAL_LIB -> FlatSource.MaterialLib
        AlgoSource.EMOJI_TEXT -> FlatSource.EmojiText
    }
}

enum class StatusBarMode(@androidx.annotation.StringRes val labelRes: Int) {
    DARK(R.string.preview_mode_dark),
    LIGHT(R.string.preview_mode_light)
}

/** Display resource per bake source — exhaustive over HitSource, so a new
 *  source without a mapping is a compile error (the old startsWith table
 *  silently degraded unknown keys to "other"). */
fun hitLevelRes(source: HitSource): Int = when (source) {
    HitSource.PICP -> R.string.hit_picp
    HitSource.PICP_FAILED -> R.string.hit_picp_failed
    HitSource.ICONLIB -> R.string.hit_iconlib
    HitSource.NETWORK_FAIL -> R.string.hit_network_fail
    HitSource.DISABLED -> R.string.hit_disabled
    HitSource.SELFFILTER -> R.string.hit_selffilter
    HitSource.ADAPTIVE -> R.string.hit_adaptive
    HitSource.ICONPACK -> R.string.hit_iconpack
    HitSource.ICONPACK_MANUAL -> R.string.hit_iconpack
    HitSource.REMOTE -> R.string.hit_remote
    HitSource.MANUAL -> R.string.hit_manual
    HitSource.AUTO_MISS -> R.string.hit_auto_miss
    HitSource.OTHER -> R.string.hit_other
}
