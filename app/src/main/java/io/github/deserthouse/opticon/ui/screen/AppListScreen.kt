package io.github.deserthouse.opticon.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
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
import io.github.deserthouse.opticon.ui.state.FilterMode
import io.github.deserthouse.opticon.ui.viewmodel.AppListViewModel

/**
 * AppListScreen v0.4.0 — UI redesign
 *
 * Material 3 Expressive with layered surfaces (LSPosed Manager / SukiSU influence):
 *  - Hero header: module name + live status dot + description
 *  - Layered background: surfaceContainerLowest base, cards float on top
 *  - 24dp large-radius card groups with count badges
 *  - Full i18n (no hardcoded strings)
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "OptIcon",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            StatusDot()
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.status_active),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
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
                Text(
                    text = stringResource(R.string.compliance_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
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

/** Live status dot — green pulse when module active (SukiSU style) */
@Composable
private fun StatusDot() {
    val color = MaterialTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.size(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.5.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_apps)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = MaterialTheme.colorScheme.primary
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
        FilterMode.ALL to stringResource(R.string.filter_all),
        FilterMode.MODIFIED to stringResource(R.string.filter_modified),
        FilterMode.ANIA_ADAPTED to stringResource(R.string.filter_ania),
        FilterMode.PICP_ADAPTED to stringResource(R.string.filter_picp),
        FilterMode.HAS_ADAPTIVE to stringResource(R.string.filter_adaptive)
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
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}

@Composable
private fun ScanProgressBar(
    progress: Pair<Int, Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = { if (progress.second > 0) progress.first.toFloat() / progress.second else 0f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${progress.first} / ${progress.second}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun AppList(
    grouped: Map<AppGroup, List<io.github.deserthouse.opticon.ui.state.AppUiEntry>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onAppClick: (String) -> Unit,
    onAppLongClick: (io.github.deserthouse.opticon.ui.state.AppUiEntry) -> Unit
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        grouped.forEach { (group, apps) ->
            item(key = "header_${group.name}") {
                GroupHeader(group = group, count = apps.size)
            }
            items(apps, key = { it.packageName }) { entry ->
                ModernAppCard(
                    entry = entry,
                    onClick = { onAppClick(entry.packageName) },
                    onLongClick = { onAppLongClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AppGroup, count: Int) {
    val label = when (group) {
        AppGroup.MODIFIED -> stringResource(R.string.group_modified)
        AppGroup.UNMODIFIED -> stringResource(R.string.group_unmodified)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (group == AppGroup.MODIFIED)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(8.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (group == AppGroup.MODIFIED)
                MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(searchQuery: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(32.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Search,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_no_apps_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_no_apps_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
