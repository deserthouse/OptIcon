package io.github.deserthouse.opticon.ui.state

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
    val algoSource: AlgoSource = AlgoSource.LOCAL_FILE,
    val customIconPath: String? = null,
    val materialIconName: String? = null,
    val emojiText: String? = null,
    val emojiUnlocked: Boolean = false,

    // Algorithm parameters (all live, exposed as sliders)
    val redrawParams: RedrawParams = RedrawParams.DEFAULT,

    // Current hit level
    val hitLevel: String = "",

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
    LOCAL_FILE,
    MATERIAL_LIB,
    EMOJI_TEXT
}

enum class StatusBarMode(val label: String) {
    DARK("Dark status bar"),
    LIGHT("Light status bar")
}
