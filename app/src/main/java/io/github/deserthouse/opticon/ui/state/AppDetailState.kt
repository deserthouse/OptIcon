package io.github.deserthouse.opticon.ui.state

import io.github.deserthouse.opticon.engine.HitSource
import io.github.deserthouse.opticon.R

import android.graphics.Bitmap
import io.github.deserthouse.opticon.engine.IconPackEngine
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
    val selectedIconPack: String? = null,
    val selectedPackIconDrawable: String? = null,
    val iconPackIcons: List<IconPackEngine.PackIconEntry> = emptyList(),
    val adaptiveForeground: Bitmap? = null,
    val adaptiveBackground: Bitmap? = null,
    val isAdaptiveIcon: Boolean? = null,
    val perfectIconsLoading: Boolean = false,
    val perfectIconsBitmap: Bitmap? = null,

    // Strategy 2: Algorithm Overdrive
    val algoSource: AlgoSource = AlgoSource.SELF,
    val colorOverride: String? = null,
    val hasOriginalCaptured: Boolean = false,
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
    val isRedrawing: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val previewMode: StatusBarMode = StatusBarMode.DARK
)

/** 2 offline strategies. */
enum class IconStrategy {
    FANKES,
    ASSET_IMPORT,
    ALGORITHM
}

enum class AssetSubStrategy {
    ICON_PACK,
    ADAPTIVE_DECOMPOSE,
    PERFECT_ICONS
}

enum class AlgoSource {
    SELF,
    LOCAL_FILE,
    MATERIAL_LIB,
    EMOJI_TEXT
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
