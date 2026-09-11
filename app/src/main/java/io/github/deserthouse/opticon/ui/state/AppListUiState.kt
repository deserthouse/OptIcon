package io.github.deserthouse.opticon.ui.state

import android.graphics.drawable.Drawable

/**
 * AppListUiState — Application list UI state 
 */
data class AppListUiState(
    val apps: List<AppUiEntry> = emptyList(),
    /** 真实 LSPosed hook 存活检测 (心跳新鲜度) — null=首次检测进行中，UI 显示「检测中」 */
    val lsposedActive: Boolean? = null,
    val isScanning: Boolean = false,
    val scanProgress: Pair<Int, Int> = 0 to 0,
    val searchQuery: String = "",
    val filterMode: FilterMode = FilterMode.ALL,
    val modifiedGroupExpanded: Boolean = true,
    val unmodifiedGroupExpanded: Boolean = true
)

data class AppUiEntry(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    /** ANIA (AndroidNotifyIconAdapt) 是否已适配 */
    val aniaAdapted: Boolean = false,
    /** PICP (Perfect Icons Completion Project) 是否已适配 */
    val picpAdapted: Boolean = false,
    /** 是否有自适应图标 */
    val hasAdaptiveIcon: Boolean = false,
    val lsposedActive: Boolean = false,
    /** 运行时检测: 原生通知图标是否合规 (null=尚未观测到该 App 的通知) */
    val iconCompliant: Boolean? = null,
    val isUserModified: Boolean = false,
    val modificationSource: ModificationSource = ModificationSource.NONE
)

enum class ModificationSource {
    NONE,
    ICON_LIBRARY,
    ALGORITHM,
    CUSTOM
}

enum class FilterMode(val label: String) {
    ALL("All"),
    ANIA_ADAPTED("ANIA"),
    PICP_ADAPTED("PICP"),
    HAS_ADAPTIVE("Adaptive"),
    MODIFIED("Modified")
}

enum class AppGroup(val label: String) {
    MODIFIED("Modified"),
    UNMODIFIED("Unmodified")
}
