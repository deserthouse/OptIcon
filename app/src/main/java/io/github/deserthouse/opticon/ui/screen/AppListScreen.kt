package io.github.deserthouse.opticon.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toPath
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.graphics.shapes.Morph
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.ui.component.ModernAppCard
import io.github.deserthouse.opticon.ui.state.AppGroup
import io.github.deserthouse.opticon.ui.state.FilterMode
import io.github.deserthouse.opticon.ui.viewmodel.AppListViewModel
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * AppListScreen v0.4.0 — UI redesign
 *
 * Material 3 Expressive with layered surfaces (LSPosed Manager / SukiSU influence):
 *  - Hero header: module name + live status dot + description
 *  - Layered background: surfaceContainer base, cards (High) float on top
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

    // Refresh statuses on every resume — returning from a detail page after
    // saving picks up new pills/compliance flags without a manual refresh.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshIconStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Compact pinned title bar (serif wordmark + version badge). The slogan
    // lives INSIDE the scrolling content (below the hero card) so it hides
    // itself while browsing. The search bar + chips sit under the title bar
    // and auto-hide on scroll down / pop back on ANY pull-up.
    val scrollUp by listState.isScrollingUp()
    val headerVisible by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 || scrollUp }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    // Tap the title bar to jump back to the top.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(OptShapes.medium).clickable {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    ) {
                        Text(
                            "OptIcon",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.width(10.dp))
                        Surface(shape = OptShapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                "v${io.github.deserthouse.opticon.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
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
            Column(Modifier.fillMaxSize()) {
                // Search + chips pop back on any pull-up and hide when the
                // user scrolls down into the list.
                AnimatedVisibility(
                    visible = headerVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        SearchField(
                            query = state.searchQuery,
                            onQueryChange = viewModel::setSearchQuery
                        )
                        FilterChipRow(
                            current = state.filterMode,
                            onSelect = viewModel::setFilterMode
                        )
                    }
                }
                val filtered = viewModel.applySearchAndFilter(
                    state.apps, state.searchQuery, state.filterMode
                )
                val grouped = viewModel.buildGroups(filtered)
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "hero") {
                        Column {
                            HeroStatusCard(
                                active = state.lsposedActive,
                                modifiedCount = state.apps.count { it.isUserModified },
                                onClick = onNavigateToSettings
                            )
                            Text(
                                text = stringResource(R.string.about_slogan),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (state.isScanning) {
                        item(key = "progress") {
                            ScanProgressBar(
                                progress = state.scanProgress,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (filtered.isEmpty() && !state.isScanning) {
                        item(key = "empty") {
                            EmptyState(
                                searchQuery = state.searchQuery,
                                visibilityBlocked = state.installedCount in 1..2,
                                modifier = Modifier.fillParentMaxWidth()
                            )
                        }
                    } else {
                        grouped.forEach { (group, apps) ->
                            item(key = "header_${group.name}") {
                                GroupHeader(group = group, count = apps.size)
                            }
                            items(apps, key = { it.packageName }) { entry ->
                                ModernAppCard(
                                    entry = entry,
                                    onClick = { onNavigateToDetail(entry.packageName) },
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(entry.packageName))
                                        Toast.makeText(context, packageNameCopiedText, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** True while the user is pulling the list UP (towards earlier content). */
@Composable
private fun LazyListState.isScrollingUp(): androidx.compose.runtime.State<Boolean> {
    var previousIndex by remember(this) { mutableStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousOffset = firstVisibleItemScrollOffset
            }
        }
    }
}

/**
 * Hero runtime status card (SukiSU-style): the first thing you see.
 * Active = primaryContainer + check badge; inactive = errorContainer +
 * alert badge, tap-through to Settings. The leading badge shape gently
 * morphs between two M3 Expressive shapes (MaterialShapes.morph).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroStatusCard(active: Boolean?, modifiedCount: Int, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = when (active) {
            true -> MaterialTheme.colorScheme.primaryContainer
            false -> MaterialTheme.colorScheme.errorContainer
            null -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "heroContainer"
    )
    val contentColor = when (active) {
        true -> MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val morphTransition = rememberInfiniteTransition(label = "heroShape")
    val morphProgress by morphTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "morph"
    )
    val fromShape = when (active) {
        true -> MaterialShapes.Pill
        false -> MaterialShapes.Square
        null -> MaterialShapes.Oval
    }
    val toShape = when (active) {
        true -> MaterialShapes.Cookie9Sided
        false -> MaterialShapes.SoftBurst
        null -> MaterialShapes.Square
    }
    val morph = remember(active) { Morph(fromShape, toShape) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(32.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                // M3 Expressive shape morph, drawn as a fitted path (bounds-normalized)
                Canvas(Modifier.size(56.dp)) {
                    val ap = morph.toPath(morphProgress).asAndroidPath()
                    val b = android.graphics.RectF()
                    ap.computeBounds(b, true)
                    val s = if (b.width() > 1e-6f && b.height() > 1e-6f)
                        minOf(size.width / b.width(), size.height / b.height()) else 1f
                    val m = android.graphics.Matrix()
                    m.setScale(s, s, b.centerX(), b.centerY())
                    m.postTranslate(size.width / 2f - b.centerX(), size.height / 2f - b.centerY())
                    ap.transform(m)
                    drawPath(ap.asComposePath(), contentColor.copy(alpha = 0.14f))
                }
                Icon(
                    when (active) {
                        true -> Icons.Rounded.Verified
                        false -> Icons.Rounded.ErrorOutline
                        null -> Icons.Rounded.HourglassEmpty
                    },
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = contentColor
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (active) {
                            true -> R.string.status_active
                            false -> R.string.status_inactive
                            null -> R.string.status_checking
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = when {
                        active == null -> stringResource(R.string.hero_checking_subtitle)
                        active && modifiedCount > 0 -> stringResource(R.string.hero_active_subtitle, modifiedCount)
                        active -> stringResource(R.string.hero_active_generic)
                        else -> stringResource(R.string.hero_inactive_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.hero_status_cd),
                tint = contentColor.copy(alpha = 0.6f)
            )
        }
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
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 28.dp)
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
                shape = OptShapes.large,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
        }
        // Right-edge fade so horizontally scrolling chips melt into the
        // background instead of clipping hard at the screen edge.
        Box(
            Modifier
                // matchParentSize: does NOT influence the parent's size —
                // fillMaxHeight here would balloon the row to full height
                // and push the LazyColumn off-screen.
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
        )
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
            shape = OptShapes.medium,
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
private fun EmptyState(searchQuery: String, visibilityBlocked: Boolean = false, modifier: Modifier = Modifier) {
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
            text = stringResource(if (visibilityBlocked) R.string.empty_visibility_title else R.string.empty_no_apps_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (visibilityBlocked) R.string.empty_visibility_body else R.string.empty_no_apps_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
