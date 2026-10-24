package io.github.deserthouse.opticon.ui.screen


import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deserthouse.opticon.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.AlgoSource
import androidx.compose.ui.graphics.asImageBitmap
import io.github.deserthouse.opticon.engine.IconPackEngine
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.ui.state.FlatSource
import io.github.deserthouse.opticon.ui.component.PreviewCard
import io.github.deserthouse.opticon.ui.component.SourcePickerSheet
import io.github.deserthouse.opticon.ui.state.AppDetailState
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.state.hitLevelRes
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.ui.theme.OptSpacing
import io.github.deserthouse.opticon.ui.session.EditorSessionCore
import io.github.deserthouse.opticon.ui.viewmodel.AppDetailViewModel
import io.github.deserthouse.opticon.ui.viewmodel.AppDetailViewModel.SaveState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ME-01: Detail page restructured around "Result → Source → Adjust → Commit".
// Old layout stacked three strategy cards with nested sub-panels.
// New layout: preview first, source summary + change button, then only the
// controls relevant to the CURRENT source, then a clear commit action.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: AppDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val algoInputClass by viewModel.algoInputClass.collectAsState()
    // Q2（K11）：SharedPreferences 读移出组合——且设置页改开关后返回本页即时反映
    val tuningVisible by androidx.compose.runtime.produceState(initialValue = false) {
        value = io.github.deserthouse.opticon.util.PreferenceManager.isTuningOptionsVisible()
    }
    LaunchedEffect(packageName) { viewModel.loadApp(packageName) }

    val snackbarHostState = remember { SnackbarHostState() }
    val saveFeedback by viewModel.saveFeedback.collectAsState()
    LaunchedEffect(saveFeedback) {
        saveFeedback?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.consumeSaveFeedback()
        }
    }

    // ── Guarded leave (OI-09 + OI-03 + A05) ──
    val isDirty by viewModel.isDirty.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val scope = rememberCoroutineScope()
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val savingMsg = stringResource(R.string.save_in_progress)
    fun requestLeave() {
        when {
            // 保存中离开 = viewModelScope 随导航取消 = 保存丢失——拦下并说明
            saveState == SaveState.Saving -> scope.launch {
                snackbarHostState.showSnackbar(savingMsg)
            }
            isDirty -> showUnsavedDialog = true
            else -> onNavigateBack()
        }
    }
    // D05（RB-5）：干净页交还 NavHost 预测返回；脏/保存中才拦截
    if (isDirty || saveState == SaveState.Saving) {
        androidx.activity.compose.BackHandler { requestLeave() }
    }
    LaunchedEffect(saveState) {
        if (saveState == SaveState.SavedAndExit) onNavigateBack()
    }
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body)) },
            confirmButton = {
                TextButton(
                    enabled = saveState != SaveState.Saving,
                    onClick = { showUnsavedDialog = false; viewModel.saveConfig(requestLeaveAfter = true) }
                ) { Text(stringResource(R.string.unsaved_save), fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false; onNavigateBack() }) {
                    Text(stringResource(R.string.unsaved_discard), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // A04（F2-1）：解码成功才原子切源（LOCAL_FILE）；失败保持旧选择
        uri?.let { viewModel.applyUploadedIcon(it.toString()) }
    }

    // ── Source change sheet ──
    var showSourceSheet by remember { mutableStateOf(false) }
    // ── Full-screen asset picker (ME-03/B2) ──
    var showAssetPicker by rememberSaveable { mutableStateOf(false) } // V03（RB-3c）：重建不丢选择器打开态
    // T-B2：扁平来源列表的本地分发态——素材选择器预选段 + Material/Emoji 弹层
    // （自 AlgorithmAdjustments 上提：入口收敛到来源列表后，弹层与策略卡解耦）
    var pendingPickerSub by rememberSaveable { mutableStateOf<AssetSubStrategy?>(null) } // U-3：回收后预选段不丢
    var showMaterialPicker by remember { mutableStateOf(false) }
    var showEmojiInput by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(state.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { requestLeave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    // ME-01: text-labeled save button (was icon-only Check)
                    TextButton(
                        onClick = { viewModel.saveConfig() },
                        // A07（F2-3）：关闭也是一种需要提交的配置变化——按钮可用性
                        // 只看保存忙碌态，不看开关值
                        enabled = saveState != SaveState.Saving
                    ) {
                        if (saveState == SaveState.Saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.save), fontWeight = FontWeight.Medium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        // R3-3(D01)：fillMaxSize 会把最小约束锁成父宽精确值，widthIn 在其后
        // 无法收缩（报告 D01 探针实证）——wrapContentWidth 先放松最小约束，
        // widthIn 才能真正把内容收到 640dp 并居中
        Column(
            Modifier.fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ ① RESULT PREVIEW ═══
            PreviewCard(
                state.previewBitmap, state.previewMode, state.previewState, viewModel::togglePreviewMode,
                sourceTag = state.hitSource.name
            )
            Spacer(Modifier.height(OptSpacing.cardGap))

            // ═══ ② ENABLE + SOURCE SUMMARY ═══
            Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    // Enable switch — ME-10/11: one toggleable surface with
                    // Role.Switch; the trailing Switch is display-only.
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = state.methodEnabled, role = androidx.compose.ui.semantics.Role.Switch) { viewModel.setMethodEnabled(it) }
                            .padding(vertical = 4.dp),
                        Arrangement.SpaceBetween, Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.enable_custom_icon), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.source_prefix, stringResource(hitLevelRes(state.hitSource))),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(state.methodEnabled, onCheckedChange = null)
                    }
                    if (!state.methodEnabled) {
                        Text(stringResource(R.string.detail_enable_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    }

                    // C09（X4）：AnimatedVisibility 直接子项叠放在 top-start——
                    // 分隔线与来源行必须由 Column 顺排，否则占同一布局起点
                    AnimatedVisibility(visible = state.methodEnabled) {
                        Column {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            // Source summary row
                            SourceSummaryRow(state, onShowSheet = { showSourceSheet = true })
                        }
                    }
                }
            }
            Spacer(Modifier.height(OptSpacing.cardGap))

            // ═══ ③ CURRENT SOURCE ADJUSTMENTS ═══
            AnimatedVisibility(visible = state.methodEnabled) {
                Column {
                    when (state.strategy) {
                        IconStrategy.FANKES -> FankesAdjustments(state)
                        IconStrategy.ASSET_IMPORT -> AssetImportAdjustments(state, viewModel, tuningVisible)
                        IconStrategy.ALGORITHM -> AlgorithmAdjustments(viewModel, tuningVisible)
                    }
                }
            }

            // ═══ ④ ADVANCED: Color override ═══
            AnimatedVisibility(visible = state.methodEnabled) {
                Column {
                    Spacer(Modifier.height(OptSpacing.cardGap))
                    var showColorSheet by remember { mutableStateOf(false) }
                    val colorLabel = when (state.colorOverride) {
                        "mono" -> stringResource(R.string.color_override_mono)
                        "color" -> stringResource(R.string.color_override_color)
                        else -> stringResource(R.string.color_override_inherit)
                    }
                    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        SettingItem(
                            Icons.Rounded.Palette,
                            stringResource(R.string.color_policy_title),
                            stringResource(R.string.color_policy_desc),
                            onClick = { showColorSheet = true }
                        ) { Text(colorLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (showColorSheet) {
                        RadioSheet(
                            title = stringResource(R.string.color_policy_title),
                            selected = state.colorOverride ?: "",
                            options = listOf(
                                RadioOption("", stringResource(R.string.color_override_inherit), null),
                                RadioOption("mono", stringResource(R.string.color_override_mono), stringResource(R.string.color_override_mono_desc)),
                                RadioOption("color", stringResource(R.string.color_override_color), stringResource(R.string.color_override_color_desc))
                            ),
                            onSelect = { viewModel.setColorOverride(it.ifBlank { null }); showColorSheet = false },
                            onDismiss = { showColorSheet = false }
                        )
                    }
                }
            }

            Spacer(Modifier.height(OptSpacing.sectionGap))
        }
    }

    // ── Source selection: flat grouped sheet (T-B2，策略+来源两层并一步) ──
    if (showSourceSheet) {
        SourcePickerSheet(
            state = state,
            onDismiss = { showSourceSheet = false },
            onPick = { src ->
                showSourceSheet = false
                when (src) {
                    FlatSource.Upload -> pickLauncher.launch("image/*") // 取消选择器不改变任何状态
                    FlatSource.MaterialLib -> showMaterialPicker = true
                    FlatSource.EmojiText -> showEmojiInput = true
                    FlatSource.Picp -> { pendingPickerSub = AssetSubStrategy.PERFECT_ICONS; showAssetPicker = true }
                    FlatSource.IconPack -> { pendingPickerSub = AssetSubStrategy.ICON_PACK; showAssetPicker = true }
                    else -> viewModel.applyFlatSource(src)
                }
            }
        )
    }
    if (showMaterialPicker) {
        MaterialIconPickerSheet(
            onDismiss = { showMaterialPicker = false },
            onPick = { name, bmp ->
                viewModel.applyMaterialIcon(name, bmp)
                showMaterialPicker = false
            }
        )
    }
    if (showEmojiInput) {
        EmojiInputDialog(
            initial = state.emojiText ?: "",
            onConfirm = { text ->
                viewModel.applyEmojiText(text.ifBlank { null })
                showEmojiInput = false
            },
            onDismiss = { showEmojiInput = false }
        )
    }

    // ── Fine-tune sheet (kept from old layout) ──
    if (state.sheetVisible) {
        AlgoWipSheet(
            onDismiss = viewModel::hideSheet,
            scale = state.redrawParams.scale,
            offsetX = state.redrawParams.offsetX,
            offsetY = state.redrawParams.offsetY,
            threshold = state.redrawParams.threshold.toFloat(),
            inputClass = algoInputClass,
            previewBitmap = state.previewBitmap,
            isRedrawing = state.previewState is io.github.deserthouse.opticon.ui.session.EditorSessionCore.PreviewState.Updating,
            onScaleChange = viewModel::setScale,
            onOffsetXChange = viewModel::setOffsetX,
            onOffsetYChange = viewModel::setOffsetY,
            onThresholdChange = viewModel::setThreshold,
            onRestoreAuto = viewModel::restoreAutoParams
        )
    }

    // ── Full-screen asset picker (B2/ME-03): draft state, atomic apply ──
    if (showAssetPicker) {
        AssetPickerScreen(
            state = state,
            initialSub = pendingPickerSub,
            onDismiss = { showAssetPicker = false; pendingPickerSub = null },
            onConfirm = { sub, pack, drawable, picp ->
                viewModel.applyAssetSelection(sub, pack, drawable, picp)
                showAssetPicker = false
                pendingPickerSub = null
            }
        )
    }
}

// ━━━ Source summary row (② in the new layout) ━━
@Composable
private fun SourceSummaryRow(state: AppDetailState, onShowSheet: () -> Unit) {
    val (icon, label) = when (state.strategy) {
        IconStrategy.FANKES -> Icons.Rounded.Public to stringResource(R.string.strategy_fankes_label_short)
        IconStrategy.ASSET_IMPORT -> Icons.Rounded.ImageSearch to when (state.assetSubStrategy) {
            AssetSubStrategy.ADAPTIVE_DECOMPOSE -> stringResource(R.string.asset_sub_adaptive)
            AssetSubStrategy.PERFECT_ICONS -> "PICP"
            AssetSubStrategy.ICON_PACK -> state.selectedIconPack ?: stringResource(R.string.source_iconpack_short)
        }
        // T-B2：显示具体输入而非内部策略名（Material 显示图标名、Emoji 显示字面）
        IconStrategy.ALGORITHM -> Icons.Rounded.AutoFixHigh to when (state.algoSource) {
            AlgoSource.SELF -> stringResource(R.string.algo_source_self)
            AlgoSource.LOCAL_FILE -> if (state.capturedOriginalInUse) stringResource(R.string.source_captured_short)
            else stringResource(R.string.algo_source_upload)
            AlgoSource.MATERIAL_LIB -> state.materialIconName ?: stringResource(R.string.algo_source_material)
            AlgoSource.EMOJI_TEXT -> state.emojiText ?: stringResource(R.string.algo_source_emoji)
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onShowSheet).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(io.github.deserthouse.opticon.ui.theme.OptSpacing.inlineGap))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.current_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ━━━ ③a: FANKES adjustments ━━━
@Composable
private fun FankesAdjustments(state: AppDetailState) {
    val ctx = LocalContext.current
    // Q2（K11）：规则库索引读取移出组合
    val fankesHasIcon by androidx.compose.runtime.produceState(initialValue = false, state.packageName) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { IconLibEngine.hasIcon(state.packageName) }
    }
    val fankesMeta = remember(state.packageName) { IconLibEngine.getMeta(state.packageName) }
    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (fankesHasIcon) {
                Text(stringResource(R.string.strategy_fankes_covered, fankesMeta?.contributorName ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.strategy_fankes_not_covered), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://betterandroid.github.io/android-notification-icon-project/en/contribute/request"))) },
                    modifier = Modifier.fillMaxWidth(), shape = OptShapes.medium) {
                    Text(stringResource(R.string.strategy_fankes_submit_btn), fontSize = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.compactButton)
                }
            }
        }
    }
}

// ━━━ ③b: ASSET_IMPORT adjustments — current-asset display + scale slider ━━━
// T-B2：选择器入口收敛到来源列表（更换 = 来源列表点 PICP/图标包）；本卡只读展示
@Composable
private fun AssetImportAdjustments(
    state: AppDetailState,
    viewModel: AppDetailViewModel,
    tuningVisible: Boolean
) {
    val context = LocalContext.current
    // Current asset thumbnail (per sub-strategy)
    // C05（X4）：keys 补 adaptiveForeground——素材晚到时缩略图同步刷新
    var thumb by remember(state.assetSubStrategy, state.selectedIconPack, state.selectedPackIconDrawable, state.perfectIconsBitmap, state.adaptiveForeground) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(state.assetSubStrategy, state.selectedIconPack, state.selectedPackIconDrawable, state.perfectIconsBitmap, state.adaptiveForeground) {
        thumb = when (state.assetSubStrategy) {
            io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ICON_PACK ->
                if (state.selectedIconPack != null && state.selectedPackIconDrawable != null)
                    IconPackEngine.loadIconPreviewCached(context, state.selectedIconPack!!, state.selectedPackIconDrawable!!, 48)
                else null
            io.github.deserthouse.opticon.ui.state.AssetSubStrategy.PERFECT_ICONS -> state.perfectIconsBitmap
            io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ADAPTIVE_DECOMPOSE -> state.adaptiveForeground
        }
    }
    val selectionLabel = when (state.assetSubStrategy) {
        io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ICON_PACK ->
            state.selectedPackIconDrawable ?: stringResource(R.string.select_icon_pack_hint)
        io.github.deserthouse.opticon.ui.state.AssetSubStrategy.PERFECT_ICONS ->
            if (state.perfectIconsBitmap != null) stringResource(R.string.asset_picp_ready)
            else stringResource(R.string.strategy_picp_not_downloaded)
        io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ADAPTIVE_DECOMPOSE ->
            if (state.isAdaptiveIcon == false) stringResource(R.string.strategy_asset_b_not_adaptive)
            else stringResource(R.string.strategy_asset_b_label)
    }
    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                // Neutral thumbnail slot (surfaceVariant) — no decorative fallback
                Box(
                    Modifier.size(48.dp).clip(OptShapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) Image(thumb!!.asImageBitmap(), null, Modifier.size(40.dp))
                }
                Spacer(Modifier.width(io.github.deserthouse.opticon.ui.theme.OptSpacing.inlineGap))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.current_asset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selectionLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            // Scale slider (when tuning enabled) — independent action zone,
            // visually divided from the picker row, own 16dp inset
            if (tuningVisible && state.assetSubStrategy == io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ADAPTIVE_DECOMPOSE) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)) {
                    SliderLabel(stringResource(R.string.algo_slider_scale), "%.2f".format(state.redrawParams.scale))
                    // A13（F2-5）：自适应烘焙路径 applyAdaptiveScale 收紧到 0.5..1.5——
                    // 控件范围与引擎约束一致，去掉显示变化但结果不变的无效上段
                    Slider(value = state.redrawParams.scale, onValueChange = viewModel::setScale, valueRange = 0.5f..1.5f, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ━━━ ③c: ALGORITHM adjustments ━━━
// T-B2：来源选择（含抓取原图/Material/Emoji 入口）已收敛到扁平来源列表；
// 本卡只留微调入口——微调关闭时整卡不渲染（空卡是死视觉承诺）
@Composable
private fun AlgorithmAdjustments(viewModel: AppDetailViewModel, tuningVisible: Boolean) {
    if (!tuningVisible) return
    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AlgoWipSection(viewModel::showSheet)
        }
    }
}

// ━━━ Keep old components used by new layout ━━━
@Composable
private fun AlgoWipSection(onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = OptShapes.medium) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.strategy_algo_open_panel_wip))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgoWipSheet(
    onDismiss: () -> Unit,
    scale: Float, offsetX: Float, offsetY: Float, threshold: Float,
    inputClass: io.github.deserthouse.opticon.engine.IconRedrawEngine.InputClass?,
    previewBitmap: android.graphics.Bitmap?,
    isRedrawing: Boolean,
    onScaleChange: (Float) -> Unit, onOffsetXChange: (Float) -> Unit, onOffsetYChange: (Float) -> Unit,
    onThresholdChange: (Int) -> Unit, onRestoreAuto: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = OptShapes.large) {
        // A11（F3-2）：外层定高不滚动（预览头固定），滚动只在参数内层
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 48.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.algo_panel_title_wip), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRestoreAuto) {
                    Text(stringResource(R.string.algo_restore_auto), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(OptSpacing.cardGap)); HorizontalDivider()
            Spacer(Modifier.height(OptSpacing.cardGap))
            // A11（F3-2）：固定预览头——深色语义底/前景对（与主预览同源常量），
            // 白色剪影恒可读；生成中→spinner+文案；空→明确占位而非整块消失
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(OptShapes.large)
                    .background(io.github.deserthouse.opticon.ui.component.PreviewSemanticBar.darkBg),
                contentAlignment = Alignment.Center
            ) {
                val preview = previewBitmap
                when {
                    // C08（X4）：进度优先于旧图可见性——有旧图也显示"正在更新"角标，
                    // 用户才能知道当前所见对应旧参数
                    isRedrawing -> Box(contentAlignment = Alignment.Center) {
                        if (preview != null) {
                            androidx.compose.foundation.Image(
                                preview.asImageBitmap(), null,
                                Modifier.height(64.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(
                                    io.github.deserthouse.opticon.ui.component.PreviewSemanticBar.darkBg.copy(alpha = 0.7f),
                                    OptShapes.small
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = io.github.deserthouse.opticon.ui.component.PreviewSemanticBar.darkFg)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.preview_generating), style = MaterialTheme.typography.labelSmall, color = io.github.deserthouse.opticon.ui.component.PreviewSemanticBar.darkFg)
                        }
                    }
                    preview != null -> androidx.compose.foundation.Image(
                        preview.asImageBitmap(), null,
                        Modifier.height(64.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    else -> Text(stringResource(R.string.preview_none), style = MaterialTheme.typography.labelMedium, color = io.github.deserthouse.opticon.ui.component.PreviewSemanticBar.darkFg)
                }
            }
            Spacer(Modifier.height(OptSpacing.cardGap))
            // 参数区独立滚动——预览头不随内容滚走
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.algo_panel_wip_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SliderLabel(stringResource(R.string.algo_slider_scale), "%.2f".format(scale))
                Slider(value = scale, onValueChange = onScaleChange, valueRange = 0.5f..2f, modifier = Modifier.fillMaxWidth())
                SliderLabel(stringResource(R.string.algo_slider_offset_x), "%.0f".format(offsetX))
                Slider(value = offsetX, onValueChange = onOffsetXChange, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
                SliderLabel(stringResource(R.string.algo_slider_offset_y), "%.0f".format(offsetY))
                Slider(value = offsetY, onValueChange = onOffsetYChange, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
                if (inputClass != io.github.deserthouse.opticon.engine.IconRedrawEngine.InputClass.TRANSPARENT_MARGIN) {
                    SliderLabel(stringResource(R.string.algo_slider_purity), "%.0f".format(threshold))
                    // R2-02：滑杆域统一到引擎生效域（RedrawParams.clamped 的 10..150）——此前 0-9/151-255
                    // 段拖动显示变化而产物不变（A13 同型洞）
                    Slider(value = threshold, onValueChange = { onThresholdChange(it.toInt()) }, valueRange = io.github.deserthouse.opticon.engine.RedrawParams.THRESHOLD_MIN.toFloat()..io.github.deserthouse.opticon.engine.RedrawParams.THRESHOLD_MAX.toFloat(), modifier = Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.algo_auto_alpha_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SliderLabel(label: String, value: String, alpha: Float = 1f) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
