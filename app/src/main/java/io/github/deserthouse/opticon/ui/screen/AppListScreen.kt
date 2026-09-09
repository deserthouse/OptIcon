package io.github.deserthouse.opticon.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.ui.component.ModernAppCard
import io.github.deserthouse.opticon.ui.state.AppGroup
import io.github.deserthouse.opticon.ui.state.AppUiEntry
import io.github.deserthouse.opticon.ui.state.FilterMode
import io.github.deserthouse.opticon.ui.viewmodel.AppListViewModel

/**
 * AppListScreen v0.3.0-alpha
 *
 * Redesigned for Material 3 Expressive:
 *  - Sticky TopAppBar with module description
 *  - Rounded search field with inline leading icon
 *  - Horizontal filter chip row (Material 3 FilterChip)
 *  - Pull-to-refresh (Material 3 PullToRefreshBox)
 *  - ModernAppCard with press feedback + status strip
 *  - Refined empty state with illustration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: AppListViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val packageNameCopiedText = stringResource(R.string.package_name_copied)

    LaunchedEffect(Unit) { viewModel.refreshIconStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "OptIcon",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.module_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.scanApps() }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isScanning,
            onRefresh = { viewModel.scanApps() },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchField(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery
                )
                Spacer(Modifier.height(8.dp))
                FilterChipRow(
                    current = state.filterMode,
                    onSelect = viewModel::setFilterMode
                )
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(
                    visible = state.isScanning,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ScanProgressBar(
                        progress = state.scanProgress,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                val filtered = viewModel.applySearchAndFilter(
                    state.apps, state.searchQuery, state.filterMode
                )
                val grouped = viewModel.buildGroups(filtered)
                if (filtered.isEmpty() && !state.isScanning) {
                    EmptyState(
                        searchQuery = state.searchQuery,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AppList(
                        grouped = grouped,
                        listState = listState,
                        onAppClick = onNavigateToDetail,
                        onAppLongClick = { entry ->
                            clipboard.setText(AnnotatedString(entry.packageName))
                            Toast.makeText(context, packageNameCopiedText, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(stringResource(R.string.search_apps), style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    current: FilterMode,
    onSelect: (FilterMode) -> Unit
) {
    val filters = listOf(
        FilterMode.ALL to "全部",
        FilterMode.MODIFIED to "已修改",
        FilterMode.ANIA_ADAPTED to "ANIA",
        FilterMode.PICP_ADAPTED to "PICP",
        FilterMode.HAS_ADAPTIVE to "Adaptive"
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(filters) { (mode, label) ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (current == mode) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

@Composable
private fun ScanProgressBar(
    progress: Pair<Int, Int>,
    modifier: Modifier = Modifier
) {
    val pct = if (progress.second > 0) progress.first.toFloat() / progress.second else 0f
    Column(modifier) {
        LinearProgressIndicator(
            progress = { pct.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${progress.first} / ${progress.second}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppList(
    grouped: Map<AppGroup, List<AppUiEntry>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onAppClick: (String) -> Unit,
    onAppLongClick: (AppUiEntry) -> Unit
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
    ) {
        grouped.forEach { (group, apps) ->
            if (apps.isNotEmpty()) {
                item(key = "h_${group.name}") {
                    GroupHeader(group, count = apps.size)
                }
                items(apps, key = { it.packageName }) { app ->
                    ModernAppCard(
                        entry = app,
                        onClick = { onAppClick(app.packageName) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AppGroup, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when (group) {
                AppGroup.MODIFIED -> "已修改"
                AppGroup.UNMODIFIED -> "未修改"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (searchQuery.isBlank())
                    "没有匹配的应用"
                else
                    "找不到 \"$searchQuery\" 相关应用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (searchQuery.isBlank())
                    "尝试调整筛选条件或刷新列表"
                else
                    "检查包名是否正确，或清除搜索",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}