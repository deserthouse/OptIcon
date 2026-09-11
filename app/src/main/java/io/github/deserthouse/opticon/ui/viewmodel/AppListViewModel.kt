package io.github.deserthouse.opticon.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.opticon.engine.AdaptiveIconExtractor
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.engine.SubscriptionManager
import io.github.deserthouse.opticon.ui.state.AppGroup
import io.github.deserthouse.opticon.ui.state.AppListUiState
import io.github.deserthouse.opticon.ui.state.AppUiEntry
import io.github.deserthouse.opticon.ui.state.FilterMode
import io.github.deserthouse.opticon.ui.state.ModificationSource
import io.github.deserthouse.opticon.util.AppInfoProvider
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.PreferenceManager.IconMethod
import io.github.deserthouse.opticon.util.TraceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

//                                                   
// AppListViewModel 
//                                                   
//
//          IO      ? ?Main       ?//                               ?
class AppListViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptIcon/AppListVM"
    }

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { initializeIconLibEngine() }
            refreshLsposedStatus()
            scanApps()
        }
    }

    /** Real LSPosed hook detection (shared with SettingsViewModel) — the hero
     *  status must reflect truth, not a hardcoded green dot. */
    fun refreshLsposedStatus() {
        viewModelScope.launch {
            val active = withContext(Dispatchers.IO) {
                io.github.deserthouse.opticon.ui.viewmodel.SettingsViewModel
                    .Companion.checkLsposed(getApplication())
            }
            _uiState.value = _uiState.value.copy(lsposedActive = active)
        }
    }

    //                                           
    //     
    //                                           

    fun scanApps() {
        if (_uiState.value.isScanning) return

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, apps = emptyList()) }

            val context = getApplication<Application>().applicationContext

            val installedApps = withContext(Dispatchers.IO) {
                AppInfoProvider.getInstalledApps(
                    context = context,
                    includeSystemApps = false,
                    requireNotificationCapable = true
                )
            }

            val total = installedApps.size
            _uiState.update { it.copy(scanProgress = 0 to total, installedCount = total) }
            TraceLogger.i(TAG, "Scan started: $total apps")

            // Build all entries in IO, then update once (H4 fix: avoid O(n²))
            val entries = withContext(Dispatchers.IO) {
                installedApps.mapIndexed { idx, app ->
                    if (idx % 20 == 0) {
                        _uiState.update { it.copy(scanProgress = idx to total) }
                    }
                    analyzeApp(context, app.packageName, app.appName, app.icon, app.isSystemApp)
                }
            }

            _uiState.update { it.copy(apps = entries, scanProgress = total to total, isScanning = false) }
            TraceLogger.i(TAG, "Scan complete: $total apps analyzed")
        }
    }

    //                                           
    //      ?    //                                           

    /** Re-evaluate ANIA/PICP/Adaptive status for all loaded apps without clearing the list.
     *  Call this when returning from Settings after syncing sources. */
    fun refreshIconStatus() {
        val currentApps = _uiState.value.apps
        if (currentApps.isEmpty() || _uiState.value.isScanning) return
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            refreshLsposedStatus()
            val updated = withContext(Dispatchers.IO) {
                currentApps.map { app ->
                    val fresh = analyzeApp(ctx, app.packageName, app.appName, app.icon, app.isSystemApp)
                    app.copy(
                        aniaAdapted = fresh.aniaAdapted,
                        picpAdapted = fresh.picpAdapted,
                        hasAdaptiveIcon = fresh.hasAdaptiveIcon,
                        isUserModified = fresh.isUserModified,
                        modificationSource = fresh.modificationSource,
                        iconCompliant = fresh.iconCompliant
                    )
                }
            }
            _uiState.update { it.copy(apps = updated) }
            TraceLogger.i(TAG, "Icon status refreshed: ${updated.count { it.isUserModified }} modified")
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterMode(mode: FilterMode) {
        _uiState.update { it.copy(filterMode = mode) }
    }

    fun toggleGroupExpanded(group: AppGroup) {
        _uiState.update { state ->
            when (group) {
                AppGroup.MODIFIED -> state.copy(modifiedGroupExpanded = !state.modifiedGroupExpanded)
                AppGroup.UNMODIFIED -> state.copy(unmodifiedGroupExpanded = !state.unmodifiedGroupExpanded)
            }
        }
    }

    //                                           
    //    /        ?    //                                           

    fun buildGroups(apps: List<AppUiEntry>): Map<AppGroup, List<AppUiEntry>> {
        val modified = mutableListOf<AppUiEntry>()
        val unmodified = mutableListOf<AppUiEntry>()

        for (app in apps) {
            if (app.isUserModified) modified.add(app) else unmodified.add(app)
        }

        return mapOf(AppGroup.MODIFIED to modified, AppGroup.UNMODIFIED to unmodified)
    }

    fun applySearchAndFilter(
        apps: List<AppUiEntry>,
        query: String,
        filter: FilterMode
    ): List<AppUiEntry> {
        return apps.filter { app ->
            val matchesQuery = query.isBlank() ||
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                FilterMode.MODIFIED -> app.isUserModified
                FilterMode.ANIA_ADAPTED -> app.aniaAdapted
                FilterMode.PICP_ADAPTED -> app.picpAdapted
                FilterMode.HAS_ADAPTIVE -> app.hasAdaptiveIcon
                FilterMode.ALL -> true
            }
            matchesQuery && matchesFilter
        }
    }

    //                                           
    //   
    //                                           

    private fun analyzeApp(
        context: Context,
        pkgName: String,
        appName: String,
        icon: Drawable?,
        isSystem: Boolean
    ): AppUiEntry {
        val hasLib = try { IconLibEngine.hasIcon(pkgName) } catch (e: Exception) { false }
        val picpAdapted = PicpEngine.hasIcon(pkgName, context)
        val hasAdaptive = AdaptiveIconExtractor.hasAdaptiveIcon(context, pkgName)
        //          isUserModified = isMethodEnabled     
        val enabled = PreferenceManager.isMethodEnabled(pkgName)
        val method = PreferenceManager.getMethod(pkgName)
        val modSource = if (!enabled) ModificationSource.NONE else when (method) {
            IconMethod.REMOTE_SUB -> ModificationSource.ICON_LIBRARY
            IconMethod.SELF_FILTER -> ModificationSource.ALGORITHM
            else -> ModificationSource.CUSTOM
        }

        return AppUiEntry(
            packageName = pkgName, appName = appName, icon = icon, isSystemApp = isSystem,
            aniaAdapted = hasLib, picpAdapted = picpAdapted, hasAdaptiveIcon = hasAdaptive,
            isUserModified = enabled, modificationSource = modSource,
            iconCompliant = io.github.deserthouse.opticon.hook.ComplianceDetector.readFlag(
                io.github.deserthouse.opticon.engine.SharedIconStore.publicDir(), pkgName)
        )
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int, targetSize: Int): Bitmap {
        drawable.setBounds(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 从下载的规则文件初始化图标库（需用户先同步） */
    private fun initializeIconLibEngine() {
        try {
            val context = getApplication<Application>().applicationContext
            val json = SubscriptionManager.readRulesJson(context)
            if (json != null) {
                IconLibEngine.ingestJson(context, json)
                TraceLogger.i(TAG, "Subscription rules loaded: ${IconLibEngine.getIndexedCount()} entries")
            } else {
                TraceLogger.i(TAG, "No rules downloaded yet — visit Settings to sync")
            }
        } catch (e: Exception) {
            TraceLogger.e(TAG, "IconLibEngine UI init failed", e)
        }
    }
}
