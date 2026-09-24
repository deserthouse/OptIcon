package io.github.deserthouse.opticon.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.engine.SharedIconStore

/**
 * PreferenceManager — 模块配置持久化 (5 方案并列 + 云端订阅)
 *
 * 每应用配置：策略方法 / Master Switch / 算法参数 / 自定义路径 / 方案6分支
 * 全局配置：日志 / 预测性返回 / Emoji彩蛋 / 订阅池
 */
object PreferenceManager {

    private const val PREFS_NAME = "optcon_prefs"

    // per-app keys
    private const val KEY_METHOD_PREFIX = "method_"
    private const val KEY_METHOD_ENABLED_PREFIX = "method_enabled_"
    private const val KEY_MANUAL_BRANCH_PREFIX = "manual_branch_"
    private const val KEY_CUSTOM_PATH_PREFIX = "custom_path_"
    private const val KEY_MATERIAL_ICON_PREFIX = "material_icon_"
    private const val KEY_EMOJI_TEXT_PREFIX = "emoji_text_"
    private const val KEY_SCALE_PREFIX = "scale_"
    private const val KEY_THRESHOLD_PREFIX = "threshold_"
    private const val KEY_OFFSET_X_PREFIX = "offset_x_"
    private const val KEY_OFFSET_Y_PREFIX = "offset_y_"
    private const val KEY_RADIUS_PREFIX = "radius_"

    // global keys
    private const val KEY_GLOBAL_VERBOSE_LOG = "global_verbose_log"
    private const val KEY_GLOBAL_PREDICTIVE_BACK = "global_predictive_back"
    private const val KEY_EMOJI_UNLOCKED = "emoji_unlocked"
    private const val KEY_SHADE_ICON_MODE = "shade_icon_mode"
    private const val KEY_RAMBLE_EXTRA_SHOWN = "ramble_extra_shown"
    private const val KEY_AI_CONFIG = "ai_config"
    private const val KEY_SUBSCRIPTION_URLS = "subscription_urls"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        modulePrefs = context.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
        ctxRef = java.lang.ref.WeakReference(context.applicationContext)
        // Ensure master_switch file exists and is world-readable
        try {
            val file = java.io.File(context.filesDir, "master_switch")
            if (!file.exists()) {
                file.writeText(if (isModuleEnabled()) "true" else "false")
            }
            file.setReadable(true, false)
            context.filesDir.setExecutable(true, false)
            context.filesDir.setReadable(true, false)
        } catch (_: Exception) {}
        // Production channel bootstrap: mirror master_switch into the shared
        // Downloads dir on first launch (idempotent, off main thread) so the
        // SystemUI hook can read it without touching filesDir.
        Thread {
            try {
                val ctx = context.applicationContext
                val shared = io.github.deserthouse.opticon.engine.SharedIconStore.masterSwitchFile()
                val needWrite = !shared.exists() ||
                    (shared.readText().trim() != if (isModuleEnabled()) "true" else "false")
                if (needWrite) {
                    io.github.deserthouse.opticon.engine.SharedIconStore.writeMasterSwitch(ctx, isModuleEnabled())
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ━━━ 3 strategies ━━━

    private const val KEY_STRATEGY_PREFIX = "strategy_"

    fun getStrategy(pkgName: String): io.github.deserthouse.opticon.ui.state.IconStrategy {
        val value = prefs?.getString("$KEY_STRATEGY_PREFIX$pkgName", null) ?: return io.github.deserthouse.opticon.ui.state.IconStrategy.FANKES
        return runCatching { io.github.deserthouse.opticon.ui.state.IconStrategy.valueOf(value) }
            .getOrDefault(io.github.deserthouse.opticon.ui.state.IconStrategy.FANKES)
    }

    fun setStrategy(pkgName: String, strategy: io.github.deserthouse.opticon.ui.state.IconStrategy) {
        prefs?.edit { putString("$KEY_STRATEGY_PREFIX$pkgName", strategy.name) }
    }

    // ━━━ 图标策略方法 (5 方案 + 智能全自动) ━━━

    fun getMethod(pkgName: String): IconMethod {
        val value = prefs?.getString("$KEY_METHOD_PREFIX$pkgName", null) ?: return IconMethod.AUTO
        return runCatching { IconMethod.valueOf(value) }.getOrDefault(IconMethod.AUTO)
    }

    fun setMethod(pkgName: String, method: IconMethod) {
        prefs?.edit { putString("$KEY_METHOD_PREFIX$pkgName", method.name) }
    }

    fun isMethodEnabled(pkgName: String): Boolean =
        prefs?.getBoolean("$KEY_METHOD_ENABLED_PREFIX$pkgName", false) ?: false

    fun setMethodEnabled(pkgName: String, enabled: Boolean) {
        prefs?.edit { putBoolean("$KEY_METHOD_ENABLED_PREFIX$pkgName", enabled) }
    }

    // ━━━ 方案 6 子分支 ━━━

    fun getManualBranch(pkgName: String): ManualBranch {
        val value = prefs?.getString("$KEY_MANUAL_BRANCH_PREFIX$pkgName", null)
            ?: return ManualBranch.LOCAL_FILE
        return runCatching { ManualBranch.valueOf(value) }.getOrDefault(ManualBranch.LOCAL_FILE)
    }

    fun setManualBranch(pkgName: String, branch: ManualBranch) {
        prefs?.edit { putString("$KEY_MANUAL_BRANCH_PREFIX$pkgName", branch.name) }
    }

    fun getCustomIconPath(pkgName: String): String? =
        prefs?.getString("$KEY_CUSTOM_PATH_PREFIX$pkgName", null)

    fun setCustomIconPath(pkgName: String, path: String?) {
        prefs?.edit { putString("$KEY_CUSTOM_PATH_PREFIX$pkgName", path) }
    }

    fun getMaterialIconName(pkgName: String): String? =
        prefs?.getString("$KEY_MATERIAL_ICON_PREFIX$pkgName", null)

    fun setMaterialIconName(pkgName: String, name: String?) {
        prefs?.edit { putString("$KEY_MATERIAL_ICON_PREFIX$pkgName", name) }
    }

    fun getEmojiText(pkgName: String): String? =
        prefs?.getString("$KEY_EMOJI_TEXT_PREFIX$pkgName", null)

    fun setEmojiText(pkgName: String, text: String?) {
        prefs?.edit { putString("$KEY_EMOJI_TEXT_PREFIX$pkgName", text) }
    }

    // ━━━ 算法参数（保留 scale/threshold，其余隐藏）━━━

    fun getScale(pkgName: String): Float =
        prefs?.getFloat("$KEY_SCALE_PREFIX$pkgName", RedrawParams.DEFAULT_SCALE)
            ?: RedrawParams.DEFAULT_SCALE

    fun setScale(pkgName: String, value: Float) {
        prefs?.edit { putFloat("$KEY_SCALE_PREFIX$pkgName", value) }
    }

    fun getThreshold(pkgName: String): Int =
        prefs?.getInt("$KEY_THRESHOLD_PREFIX$pkgName", RedrawParams.DEFAULT_THRESHOLD)
            ?: RedrawParams.DEFAULT_THRESHOLD

    fun setThreshold(pkgName: String, value: Int) {
        prefs?.edit { putInt("$KEY_THRESHOLD_PREFIX$pkgName", value) }
    }

    fun getOffsetX(pkgName: String): Float =
        prefs?.getFloat("$KEY_OFFSET_X_PREFIX$pkgName", 0f) ?: 0f

    fun getOffsetY(pkgName: String): Float =
        prefs?.getFloat("$KEY_OFFSET_Y_PREFIX$pkgName", 0f) ?: 0f

    fun getRadius(pkgName: String): Float =
        prefs?.getFloat("$KEY_RADIUS_PREFIX$pkgName", 0f) ?: 0f

    fun setOffsetX(pkgName: String, value: Float) {
        prefs?.edit { putFloat("$KEY_OFFSET_X_PREFIX$pkgName", value) }
    }

    fun setOffsetY(pkgName: String, value: Float) {
        prefs?.edit { putFloat("$KEY_OFFSET_Y_PREFIX$pkgName", value) }
    }

    fun setRadius(pkgName: String, value: Float) {
        prefs?.edit { putFloat("$KEY_RADIUS_PREFIX$pkgName", value) }
    }

    // ━━━ AI 配置 ━━━

    data class AIConfig(
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash-image",
        val prompt: String = ""
    )

    fun getAIConfig(): AIConfig {
        val raw = prefs?.getString(KEY_AI_CONFIG, null) ?: return AIConfig()
        return try {
            val obj = org.json.JSONObject(raw)
            AIConfig(
                baseUrl = obj.optString("baseUrl", ""),
                apiKey = obj.optString("apiKey", ""),
                model = obj.optString("model", "gemini-2.5-flash-image"),
                prompt = obj.optString("prompt", "")
            )
        } catch (_: Exception) { AIConfig() }
    }

    fun setAIConfig(config: AIConfig) {
        val obj = org.json.JSONObject().apply {
            put("baseUrl", config.baseUrl); put("apiKey", config.apiKey)
            put("model", config.model); put("prompt", config.prompt)
        }
        prefs?.edit { putString(KEY_AI_CONFIG, obj.toString()) }
    }

    // ━━━ 模块总开关（独立文件 module_settings，供 XSharedPreferences 跨进程读取）━━━

    private const val MODULE_PREFS_NAME = "module_settings"
    private var modulePrefs: SharedPreferences? = null
    private const val KEY_MODULE_ENABLED = "is_enabled"

    fun isModuleEnabled(): Boolean =
        modulePrefs?.getBoolean(KEY_MODULE_ENABLED, true) ?: true

    /** @return the shared-dir path on success, null when the cross-process
     *  write failed — the caller MUST surface that (a silently lost "off"
     *  leaves the hook replacing icons the user asked to stop). */
    fun setModuleEnabled(enabled: Boolean): Boolean {
        modulePrefs?.edit { putBoolean(KEY_MODULE_ENABLED, enabled) }
        // Also write a world-readable file for cross-process read by SystemUI hook
        return try {
            val ctx = ctxRef?.get() ?: return false
            val file = java.io.File(ctx.filesDir, "master_switch")
            file.writeText(if (enabled) "true" else "false")
            file.setReadable(true, false)
            // Production channel: mirror to shared Downloads dir (readable by SystemUI)
            io.github.deserthouse.opticon.engine.SharedIconStore.writeMasterSwitch(ctx, enabled) != null
        } catch (_: Exception) {
            false
        }
    }

    private var ctxRef: java.lang.ref.WeakReference<Context>? = null

    // ━━━ 全局配置 ━━━

    fun isVerboseLogging(): Boolean =
        prefs?.getBoolean(KEY_GLOBAL_VERBOSE_LOG, false) ?: false

    fun setVerboseLogging(enabled: Boolean) {
        prefs?.edit { putBoolean(KEY_GLOBAL_VERBOSE_LOG, enabled) }
    }

    fun isPredictiveBackEnabled(): Boolean =
        prefs?.getBoolean(KEY_GLOBAL_PREDICTIVE_BACK, true) ?: true

    fun setPredictiveBackEnabled(enabled: Boolean) {
        prefs?.edit { putBoolean(KEY_GLOBAL_PREDICTIVE_BACK, enabled) }
    }

    // ━━━ 彩蛋 ━━━

    fun isEmojiUnlocked(): Boolean =
        prefs?.getBoolean(KEY_EMOJI_UNLOCKED, false) ?: false

    fun setEmojiUnlocked(unlocked: Boolean) {
        prefs?.edit { putBoolean(KEY_EMOJI_UNLOCKED, unlocked) }
    }

    // ━━━ Shade row icon mode (Android 17+ dropdown area) ━━━
    // "app" = force app icon (AOSP default) · "notif" = force small icon ·
    // "pref" = respect the app's own preferSmallIcon extra.
    // Mirrored to the shared dir so the SystemUI hook can read it.

    fun getShadeAppIconMode(): String =
        prefs?.getString(KEY_SHADE_ICON_MODE, "app") ?: "app"

    fun setShadeAppIconMode(context: Context, mode: String) {
        prefs?.edit { putString(KEY_SHADE_ICON_MODE, mode) }
        try {
            SharedIconStore.writeShadeIconMode(context, mode)
        } catch (_: Exception) {}
    }

    fun isRambleExtraShown(): Boolean =
        prefs?.getBoolean(KEY_RAMBLE_EXTRA_SHOWN, false) ?: false

    fun setRambleExtraShown(shown: Boolean) {
        prefs?.edit { putBoolean(KEY_RAMBLE_EXTRA_SHOWN, shown) }
    }

    // ━━━ 订阅源管理 ━━━

    /** 订阅源数据类 */
    data class SubscriptionSource(
        val id: String,
        val name: String,
        val url: String,
        val description: String = "",
        val isBuiltin: Boolean = false
    )

    /** 内置源 — 只保留 GitHub Raw 直连（无需授权）。
     *  id "ania_raw" 为历史遗留内部标识（老用户已存偏好），实际已指向 ANIP。 */
    val BUILTIN_ANIA_SOURCES = listOf(
        SubscriptionSource("ania_raw", "Android 通知图标项目（ANIP，原 AndroidNotifyIconAdapt）",
            io.github.deserthouse.opticon.engine.AnipSync.BASE,
            "", true),
    )

    /** PICP 完美图标计划源 — zipball endpoint */
    val BUILTIN_PICP_SOURCES = listOf(
        SubscriptionSource("picp_github", "完美图标补全计划（Perfect-Icons-Completion-Project）",
            "https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/zipball/main",
            "", true),
    )

    private const val KEY_CUSTOM_SOURCES = "custom_sub_sources"
    private const val KEY_ACTIVE_ANIA_SOURCE = "active_ania_source"
    private const val KEY_ACTIVE_PICP_SOURCE = "active_picp_source"
    private const val KEY_HIDDEN_BUILTINS = "hidden_builtin_sources"

    private fun getHiddenBuiltins(): Set<String> {
        val raw = prefs?.getString(KEY_HIDDEN_BUILTINS, null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun addHiddenBuiltin(id: String) {
        val current = getHiddenBuiltins().toMutableSet(); current.add(id)
        prefs?.edit { putString(KEY_HIDDEN_BUILTINS, current.joinToString(",")) }
    }

    /** 获取 ANIA 源列表（过滤已隐藏的内置源） */
    fun getAniaSources(): List<SubscriptionSource> {
        val hidden = getHiddenBuiltins()
        return BUILTIN_ANIA_SOURCES.filter { it.id !in hidden } + getCustomSources()
    }
    fun getPicpSources(): List<SubscriptionSource> {
        val hidden = getHiddenBuiltins()
        return BUILTIN_PICP_SOURCES.filter { it.id !in hidden } + getCustomSources()
    }

    fun getActiveAniaSourceId(): String =
        prefs?.getString(KEY_ACTIVE_ANIA_SOURCE, "ania_raw") ?: "ania_raw"
    fun getActivePicpSourceId(): String =
        prefs?.getString(KEY_ACTIVE_PICP_SOURCE, "picp_github") ?: "picp_github"

    fun setActiveAniaSourceId(id: String) { prefs?.edit { putString(KEY_ACTIVE_ANIA_SOURCE, id) } }
    fun setActivePicpSourceId(id: String) { prefs?.edit { putString(KEY_ACTIVE_PICP_SOURCE, id) } }

    fun getSourceUrl(id: String): String? {
        return (BUILTIN_ANIA_SOURCES + BUILTIN_PICP_SOURCES + getCustomSources())
            .find { it.id == id }?.url
    }

    // ━━━ 自定义源 ━━━

    private fun getCustomSources(): List<SubscriptionSource> {
        val raw = prefs?.getString(KEY_CUSTOM_SOURCES, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SubscriptionSource(
                    obj.getString("id"), obj.getString("name"), obj.getString("url"),
                    obj.optString("description", ""), false
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCustomSources(sources: List<SubscriptionSource>) {
        val arr = org.json.JSONArray()
        sources.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url); put("description", s.description)
            })
        }
        prefs?.edit { putString(KEY_CUSTOM_SOURCES, arr.toString()) }
    }

    fun addCustomSource(name: String, url: String, description: String = ""): SubscriptionSource {
        val id = "custom_${System.currentTimeMillis()}"
        val source = SubscriptionSource(id, name, url, description)
        val current = getCustomSources().toMutableList()
        current.add(source)
        saveCustomSources(current)
        return source
    }

    fun updateCustomSource(id: String, name: String, url: String, description: String = "") {
        val current = getCustomSources().map {
            if (it.id == id) it.copy(name = name, url = url, description = description) else it
        }
        saveCustomSources(current)
    }

    fun removeCustomSource(id: String) {
        // Handle builtin sources
        if (BUILTIN_ANIA_SOURCES.any { it.id == id } || BUILTIN_PICP_SOURCES.any { it.id == id }) {
            addHiddenBuiltin(id)
        } else {
            saveCustomSources(getCustomSources().filter { it.id != id })
        }
        if (getActiveAniaSourceId() == id) setActiveAniaSourceId("ania_raw")
        if (getActivePicpSourceId() == id) setActivePicpSourceId("picp_github")
    }

    /** Restore a hidden builtin source (e.g. user clicked "Restore default") */
    fun restoreBuiltinSource(id: String) {
        val current = getHiddenBuiltins().toMutableSet(); current.remove(id)
        prefs?.edit { putString(KEY_HIDDEN_BUILTINS, current.joinToString(",")) }
    }

    // ━━━ 枚举：5 方案 + 方案 6 分支 ━━━

    /**
     * 图标策略方法（并列可选，智能全自动为默认）。
     */
    enum class IconMethod {
        /** 智能全自动配置 — 引擎按责任链自动择优（远程→图标包→自适应→null） */
        AUTO,
        /** 方案 2：远程订阅源（Fankes JSON） */
        REMOTE_SUB,
        /** 方案 3：第三方分层图标包提取 */
        ICON_PACK,
        /** 方案 4：本机自适应图标提取 */
        ADAPTIVE,
        /** 方案 5：自研四角采样色彩距离过滤 */
        SELF_FILTER,
        /** 方案 6：用户手动重写（多分支） */
        MANUAL
    }

    /**
     * 方案 6 手动重写的子分支。
     */
    enum class ManualBranch {
        /** A 本地上传 (PNG/WEBP) */
        LOCAL_FILE,
        /** B Material 设计图标库兜底 */
        MATERIAL_LIB,
        /** C 文本/Emoji 剪影转换（彩蛋，需解锁） */
        EMOJI_TEXT
    }

    // ━━━ 旧枚举保留兼容（不推荐新代码使用）━━━

    @Deprecated("Use IconMethod instead", ReplaceWith("IconMethod.REMOTE_SUB"))
    enum class IconSource { NONE, ICON_LIB, CUSTOM }

    @Deprecated("Use IconMethod + ManualBranch instead")
    enum class BaseSource { APP_ICON, NOTIFICATION, LOCAL }

    @Deprecated("Use getMethod/setMethod")
    fun getIconSource(pkgName: String): IconSource = when (getMethod(pkgName)) {
        IconMethod.AUTO, IconMethod.REMOTE_SUB -> IconSource.ICON_LIB
        IconMethod.MANUAL -> IconSource.CUSTOM
        else -> IconSource.NONE
    }
}
