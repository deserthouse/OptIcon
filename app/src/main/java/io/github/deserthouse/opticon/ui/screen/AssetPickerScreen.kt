package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.engine.IconPackEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.ui.state.AppDetailState
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.theme.OptShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ME-03 (B2): full-screen asset picker.
// Browsing thousands of pack icons no longer lives in a 180dp nested
// window. The picker holds a DRAFT selection — cancel changes nothing,
// Apply commits atomically via ViewModel.applyAssetSelection.
// Perf measures kept: stable keys, 60ms decode debounce, LruCache,
// launcher-style ordering (sortPackIcons), distinctBy dedup upstream.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPickerScreen(
    state: AppDetailState,
    onDismiss: () -> Unit,
    onConfirm: (AssetSubStrategy, String?, String?, Bitmap?) -> Unit
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler { onDismiss() }

    // ── Draft selection (cancel-safe) ──
    // V03（RB-3c）：可保存草稿态——旋转/回收后选择保留。
    // Bitmap(draftPicp)与图标列表不进 Bundle（按 ID 重载），保持 remember。
    var draftSubName by rememberSaveable { mutableStateOf(state.assetSubStrategy.name) }
    var draftPack by rememberSaveable { mutableStateOf(state.selectedIconPack) }
    var draftDrawable by rememberSaveable { mutableStateOf(state.selectedPackIconDrawable) }
    var draftPicp by remember { mutableStateOf(state.perfectIconsBitmap) }
    var picpLoading by remember { mutableStateOf(false) }
    var picpFailed by rememberSaveable { mutableStateOf(false) } // C11：下载失败可判读+可重试
    val scope = rememberCoroutineScope()
    // R2-13：loadApp 的 PICP 自动预取晚于选择器打开时补灌草稿（避免重复下载）
    LaunchedEffect(state.perfectIconsBitmap) {
        if (draftPicp == null) draftPicp = state.perfectIconsBitmap
    }

    // U-A1（ME-03 尾巴）：Apply 前草稿校验——各子策略必须有可产物的素材，
    // 否则禁用并说明；防止"应用一个空来源"把详情带回无效配置
    val draftValid = when (subOf(draftSubName)) {
        AssetSubStrategy.ADAPTIVE_DECOMPOSE -> state.isAdaptiveIcon == true
        AssetSubStrategy.PERFECT_ICONS -> draftPicp != null
        AssetSubStrategy.ICON_PACK -> draftPack != null && draftDrawable != null
    }

    // Pack icon list, scoped to the draft pack
    var packIcons by remember { mutableStateOf<List<IconPackEngine.PackIconEntry>>(emptyList()) }
    // Q9（C11 模式补全）：加载中/确无图标二分——网格区不再静默空白
    var packLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(draftPack) {
        packIcons = emptyList()
        packLoaded = false
        draftPack?.let { pkg ->
            packIcons = withContext(Dispatchers.IO) { IconPackEngine.listAllIcons(context, pkg) }
        }
        packLoaded = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.asset_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.asset_picker_close))
                    }
                },
                actions = {
                    TextButton(
                        enabled = draftValid,
                        onClick = { onConfirm(subOf(draftSubName), draftPack, draftDrawable, draftPicp) }
                    ) {
                        Text(
                            stringResource(R.string.asset_picker_apply),
                            fontWeight = FontWeight.Medium,
                            color = if (draftValid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding).padding(horizontal = 16.dp)
        ) {
            // ── Sub-strategy selector ──
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = subOf(draftSubName) == AssetSubStrategy.ADAPTIVE_DECOMPOSE,
                    onClick = { draftSubName = AssetSubStrategy.ADAPTIVE_DECOMPOSE.name },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) { Text(stringResource(R.string.asset_sub_adaptive), maxLines = 1) }
                SegmentedButton(
                    selected = subOf(draftSubName) == AssetSubStrategy.PERFECT_ICONS,
                    onClick = { draftSubName = AssetSubStrategy.PERFECT_ICONS.name },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) { Text(stringResource(R.string.asset_sub_picp), maxLines = 1) }
                SegmentedButton(
                    selected = subOf(draftSubName) == AssetSubStrategy.ICON_PACK,
                    onClick = { draftSubName = AssetSubStrategy.ICON_PACK.name },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) { Text(stringResource(R.string.asset_sub_pack), maxLines = 1) }
            }
            Spacer(Modifier.height(12.dp))

            if (!draftValid) {
                Text(
                    stringResource(R.string.asset_picker_apply_blocked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            when (subOf(draftSubName)) {
                AssetSubStrategy.ADAPTIVE_DECOMPOSE -> AdaptivePickerSection(state)
                AssetSubStrategy.PERFECT_ICONS -> PicpPickerSection(
                    state, draftPicp, picpLoading, picpFailed,
                    onDownload = {
                        if (picpLoading) return@PicpPickerSection
                        picpLoading = true
                        picpFailed = false
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) { PicpEngine.downloadIcon(context, state.packageName) }
                            draftPicp = bmp
                            picpFailed = bmp == null
                            picpLoading = false
                        }
                    }
                )
                AssetSubStrategy.ICON_PACK -> IconPackPickerSection(
                    state, draftPack, draftDrawable, packIcons, packLoaded,
                    onPickPack = { pkg -> draftPack = pkg; draftDrawable = null },
                    onPickDrawable = { draftDrawable = it }
                )
            }
        }
    }
}

// ━━━ Adaptive decompose (draft view of fg/bg layers) ━━━
@Composable
private fun AdaptivePickerSection(state: AppDetailState) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.isAdaptiveIcon == null) {
            Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.isAdaptiveIcon == false) {
            Text(stringResource(R.string.strategy_asset_b_not_adaptive), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        } else {
            Text(stringResource(R.string.strategy_asset_b_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(stringResource(R.string.strategy_asset_b_foreground), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (state.adaptiveForeground != null) {
                        Image(state.adaptiveForeground!!.asImageBitmap(), "fg", Modifier.size(72.dp).clip(OptShapes.small))
                    }
                }
                Column {
                    Text(stringResource(R.string.strategy_asset_b_background), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (state.adaptiveBackground != null) {
                        Image(state.adaptiveBackground!!.asImageBitmap(), "bg", Modifier.size(72.dp).clip(OptShapes.small))
                    }
                }
            }
        }
    }
}

// ━━━ PICP (download to draft — commit only via Apply) ━━━
@Composable
private fun PicpPickerSection(
    state: AppDetailState,
    draftPicp: Bitmap?,
    loading: Boolean,
    failed: Boolean,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    // Q2（K11）：首次 hasIcon() 会整读 PICP 索引文件——IO 移出组合；
    // null=索引加载中，渲染空白位而非闪现"未收录"
    val hasPicp by produceState<Boolean?>(initialValue = null, state.packageName) {
        value = withContext(Dispatchers.IO) { PicpEngine.hasIcon(state.packageName, context) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (hasPicp) {
            // S5：索引加载中给占位，不渲染空白段
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.packs_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            true -> {
            if (draftPicp != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(draftPicp.asImageBitmap(), "picp", Modifier.size(72.dp).clip(OptShapes.small))
                    Text(stringResource(R.string.asset_picp_ready), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            OutlinedButton(onClick = onDownload, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = OptShapes.medium) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (failed) R.string.picp_download_retry else R.string.asset_picp_download))
            }
            if (failed) {
                Text(
                    stringResource(R.string.picp_download_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            }
            else -> {
            Card(Modifier.fillMaxWidth(), shape = OptShapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.strategy_picp_not_downloaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    // T0：未同步死端引导——告诉用户去设置同步（此前只提"去项目提交"，
                    // 用户第一次进来会撞死端）
                    Text(stringResource(R.string.picp_go_to_settings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.strategy_picp_submit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PicpEngine.FEEDBACK_URL))) }, modifier = Modifier.fillMaxWidth(), shape = OptShapes.small) {
                        Text(stringResource(R.string.strategy_picp_submit_btn))
                    }
                }
            }
            }
        }
    }
}

// ━━━ Icon pack: selector → search → full-height adaptive grid ━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconPackPickerSection(
    state: AppDetailState,
    draftPack: String?,
    draftDrawable: String?,
    packIcons: List<IconPackEngine.PackIconEntry>,
    packLoaded: Boolean,
    onPickPack: (String) -> Unit,
    onPickDrawable: (String) -> Unit
) {
    val context = LocalContext.current
    if (state.iconPacks.isEmpty()) {
        // C11：空清单区分加载中与确无——不把加载态当"未安装"误报
        if (!state.iconPacksLoaded) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.packs_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(stringResource(R.string.no_icon_packs_installed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // Pack selector
        var expanded by remember { mutableStateOf(false) }
        val selLabel = state.iconPacks.find { it.packageName == draftPack }?.label
            ?: stringResource(R.string.select_icon_pack_hint)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(selLabel, {}, readOnly = true, label = { Text(stringResource(R.string.select_icon_pack)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.iconPacks.forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { onPickPack(p.packageName); expanded = false })
                }
            }
        }
        // Open the pack's own app when launchable
        // Q2（K11）：pm 查询移出组合条件分支
        val packLaunchable by produceState(initialValue = false, draftPack) {
            val pkg = draftPack
            value = pkg != null &&
                withContext(Dispatchers.IO) { IconPackEngine.hasLaunchableActivity(context, pkg) }
        }
        val launchablePkg = draftPack
        if (packLaunchable && launchablePkg != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { context.packageManager.getLaunchIntentForPackage(launchablePkg)?.let { context.startActivity(it) } }) {
                Text(stringResource(R.string.strategy_asset_open_pack_app))
            }
        }

        // Q9（C11 模式补全）：加载中/确无图标二分，网格区不再静默空白
        if (packIcons.isEmpty()) {
            if (draftPack != null) {
                if (!packLoaded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.packs_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(stringResource(R.string.no_icons_in_pack), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        // Q1+A2(R2-01)：过滤+排序移出主线程。produceState 的 key 变化会把值
        // 重置回 initialValue（Q 批误用=每次击键闪全量网格）——改自管状态：
        // 防抖层与排序层都不重置，重算期间保留上次结果，在飞计算随 key 取消。
        var queryInput by rememberSaveable(draftPack) { mutableStateOf("") }
        var query by remember(draftPack) { mutableStateOf("") }
        LaunchedEffect(queryInput) {
            if (queryInput == query) return@LaunchedEffect
            kotlinx.coroutines.delay(200)
            query = queryInput
        }
        OutlinedTextField(queryInput, { queryInput = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.icon_pack_search_hint)) },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        var displayIcons by remember(packIcons, state.packageName, state.appName) {
            mutableStateOf(packIcons)
        }
        LaunchedEffect(packIcons, query, state.packageName, state.appName) {
            displayIcons = withContext(Dispatchers.Default) {
                IconPackEngine.sortPackIcons(packIcons, state.packageName, state.appName, query)
            }
        }
        Text("${displayIcons.size}/${packIcons.size} ${stringResource(R.string.icons_available)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        // Q16：列数交给 Adaptive 按实际约束（含 padding/间距）计算——窄窗自动
        // 降列数保住 120dp 每格下限，宽窗不再有整除误差导致的挤格
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(displayIcons, key = { "${it.componentRaw}_${it.drawableName}" }) { entry ->
                PickerIconCell(
                    entry = entry,
                    packPkg = draftPack ?: "",
                    selected = draftDrawable == entry.drawableName,
                    onSelect = { onPickDrawable(entry.drawableName) }
                )
            }
        }
    }
}

/** V03（RB-3c）：saveable 字符串 → 子策略枚举（损坏值回落默认档） */
private fun subOf(name: String): AssetSubStrategy =
    runCatching { AssetSubStrategy.valueOf(name) }.getOrDefault(AssetSubStrategy.ADAPTIVE_DECOMPOSE)

// ━━━ Grid cell: ≥48dp touch, full name (2-line ellipsis), check + border
//     selection marks, neutral placeholder (no decorative fallback icon) ━━━
@Composable
private fun PickerIconCell(
    entry: IconPackEngine.PackIconEntry,
    packPkg: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    var preview by remember(entry.drawableName) { mutableStateOf<Bitmap?>(null) }
    // 60ms debounce: cells flying past during a fling never cost a decode
    LaunchedEffect(entry.drawableName) {
        delay(60)
        preview = IconPackEngine.loadIconPreviewCached(context, packPkg, entry.drawableName, 96)
    }
    Column(
        Modifier.fillMaxWidth().clip(OptShapes.medium).clickable(onClick = onSelect).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .clip(OptShapes.small)
                // V08（Y5）+T-B2①：素材极性与主题解耦——固定浅画布上叠加
                // 微弱棋盘格纹理，白色/浅色素材的透明区域与画布底可辨
                .background(androidx.compose.ui.graphics.Color(0xFFF7F5FA))
                .drawBehind {
                    // 8px 棋盘格（与 AOSP 透明指示一致）；alpha 极低只提供纹理提示
                    val sq = 8.dp.toPx()
                    val light = androidx.compose.ui.graphics.Color(0xFFE8E6EF)
                    for (row in 0 until (size.height / sq).toInt() + 1) {
                        for (col in 0 until (size.width / sq).toInt() + 1) {
                            if ((row + col) % 2 == 0) {
                                drawRect(light, topLeft = Offset(col * sq, row * sq), size = Size(sq, sq))
                            }
                        }
                    }
                }
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, OptShapes.small) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(preview!!.asImageBitmap(), entry.drawableName, Modifier.size(56.dp))
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.drawableName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
