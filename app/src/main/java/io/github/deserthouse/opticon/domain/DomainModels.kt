package io.github.deserthouse.opticon.domain

/**
 * 领域枚举——策略/来源的规范定义（Z4 架构前置①：迁出 ui.state，
 * 消除 data/engine → ui 反向依赖）。ui.state 侧保留 typealias 兼容。
 */
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

enum class ModificationSource {
    NONE,
    ICON_LIBRARY,
    ALGORITHM,
    CUSTOM
}
