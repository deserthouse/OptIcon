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

/**
 * 订阅源载荷格式（批 SYNC V3）——驱动同步路由分派，替代 URL 前缀启发式。
 * 同一自定义源列表中的源按此字段分流到兼容解析器，不再互送。
 */
enum class SourceFormat {
    /** ANIP 资源包（manifest.json + res PNG，zipball 传输） */
    ANIP_BUNDLE,
    /** 旧版 base64 JSON 订阅格式 */
    LEGACY_JSON,
    /** PICP 整仓 zipball（themes 主题目录下的 per-app 1.png 结构） */
    PICP_ZIPBALL
}
