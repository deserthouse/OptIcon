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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.AlgoSource
import androidx.compose.ui.graphics.asImageBitmap
import io.github.deserthouse.opticon.engine.IconPackEngine
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.ui.component.PreviewCard
import io.github.deserthouse.opticon.ui.component.SelectionRow
import io.github.deserthouse.opticon.ui.state.AppDetailState
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.state.hitLevelRes
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.ui.viewmodel.AppDetailViewModel
import io.github.deserthouse.opticon.ui.viewmodel.AppDetailViewModel.SaveState
import kotlinx.coroutines.launch

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
    val tuningVisible = remember { io.github.deserthouse.opticon.util.PreferenceManager.isTuningOptionsVisible() }
    LaunchedEffect(packageName) { viewModel.loadApp(packageName) }

    val snackbarHostState = remember { SnackbarHostState() }
    val saveFeedback by viewModel.saveFeedback.collectAsState()
    LaunchedEffect(saveFeedback) {
        saveFeedback?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.consumeSaveFeedback()
        }
    }

    // ── Guarded leave (OI-09 + OI-03) ──
    val isDirty by viewModel.isDirty.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    var showUnsavedDialog by remember { mutableStateOf(false) }
    fun requestLeave() {
        if (isDirty && saveState != SaveState.Saving) showUnsavedDialog = true
        else onNavigateBack()
    }
    androidx.activity.compose.BackHandler { requestLeave() }
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
        uri?.let { viewModel.setCustomIconPath(it.toString()) }
    }

    // ── Source change sheet ──
    var showSourceSheet by remember { mutableStateOf(false) }

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
                        enabled = saveState != SaveState.Saving && state.methodEnabled
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
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ ① RESULT PREVIEW ═══
            PreviewCard(state.previewBitmap, state.previewMode, state.isRedrawing, viewModel::togglePreviewMode)
            Spacer(Modifier.height(12.dp))

            // ═══ ② ENABLE + SOURCE SUMMARY ═══
            Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    // Enable switch
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.setMethodEnabled(!state.methodEnabled) }.padding(vertical = 4.dp),
                        Arrangement.SpaceBetween, Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.enable_custom_icon), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.source_prefix, stringResource(hitLevelRes(state.hitSource))),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(state.methodEnabled, viewModel::setMethodEnabled)
                    }
                    if (!state.methodEnabled) {
                        Text(stringResource(R.string.detail_enable_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    }

                    AnimatedVisibility(visible = state.methodEnabled) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        // Source summary row
                        SourceSummaryRow(state, onShowSheet = { showSourceSheet = true })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ═══ ③ CURRENT SOURCE ADJUSTMENTS ═══
            AnimatedVisibility(visible = state.methodEnabled) {
                Column {
                    when (state.strategy) {
                        IconStrategy.FANKES -> FankesAdjustments(state)
                        IconStrategy.ASSET_IMPORT -> AssetImportAdjustments(state, viewModel, tuningVisible, pickLauncher)
                        IconStrategy.ALGORITHM -> AlgorithmAdjustments(state, viewModel, tuningVisible, algoInputClass, pickLauncher)
                    }
                }
            }

            // ═══ ④ ADVANCED: Color override ═══
            AnimatedVisibility(visible = state.methodEnabled) {
                Column {
                    Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Source selection sheet ──
    if (showSourceSheet) {
        ModalBottomSheet(onDismissRequest = { showSourceSheet = false }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.source_change_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.source_change_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                SourceOptionRow(
                    label = stringResource(R.string.strategy_fankes_label),
                    desc = stringResource(R.string.strategy_fankes_label_short),
                    selected = state.strategy == IconStrategy.FANKES,
                    onClick = { viewModel.setStrategy(IconStrategy.FANKES); showSourceSheet = false }
                )
                SourceOptionRow(
                    label = stringResource(R.string.strategy_asset_label),
                    desc = stringResource(R.string.strategy_asset_desc_short),
                    selected = state.strategy == IconStrategy.ASSET_IMPORT,
                    onClick = { viewModel.setStrategy(IconStrategy.ASSET_IMPORT); showSourceSheet = false }
                )
                SourceOptionRow(
                    label = stringResource(R.string.strategy_algo_label),
                    desc = stringResource(R.string.strategy_algo_desc_short),
                    selected = state.strategy == IconStrategy.ALGORITHM,
                    onClick = { viewModel.setStrategy(IconStrategy.ALGORITHM); showSourceSheet = false }
                )
            }
        }
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
            onScaleChange = viewModel::setScale,
            onOffsetXChange = viewModel::setOffsetX,
            onOffsetYChange = viewModel::setOffsetY,
            onThresholdChange = viewModel::setThreshold,
            onRestoreAuto = viewModel::restoreAutoParams
        )
    }
}

// ━━━ Source summary row (② in the new layout) ━━━
@Composable
private fun SourceSummaryRow(state: AppDetailState, onShowSheet: () -> Unit) {
    val (icon, label) = when (state.strategy) {
        IconStrategy.FANKES -> Icons.Rounded.Public to stringResource(R.string.strategy_fankes_label_short)
        IconStrategy.ASSET_IMPORT -> Icons.Rounded.ImageSearch to stringResource(R.string.strategy_asset_label_short)
        IconStrategy.ALGORITHM -> Icons.Rounded.AutoFixHigh to stringResource(R.string.strategy_algo_desc_short)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onShowSheet).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.current_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ━━━ Source option row (in the change sheet) ━━━
@Composable
private fun SourceOptionRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = OptShapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ━━━ ③a: FANKES adjustments ━━━
@Composable
private fun FankesAdjustments(state: AppDetailState) {
    val ctx = LocalContext.current
    val fankesHasIcon = remember(state.packageName) { IconLibEngine.hasIcon(state.packageName) }
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
                    Text(stringResource(R.string.strategy_fankes_submit_btn), fontSize = 13.sp)
                }
            }
        }
    }
}

// ━━━ ③b: ASSET_IMPORT adjustments ━━━
@Composable
private fun AssetImportAdjustments(
    state: AppDetailState,
    viewModel: AppDetailViewModel,
    tuningVisible: Boolean,
    pickLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.strategy_asset_b_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            // Adaptive decompose preview
            if (state.isAdaptiveIcon == true && state.adaptiveForeground != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.strategy_asset_b_foreground), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Image(state.adaptiveForeground!!.asImageBitmap(), "fg", Modifier.size(64.dp))
                    }
                }
            } else if (state.isAdaptiveIcon == false) {
                Text(stringResource(R.string.strategy_asset_b_not_adaptive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // Scale slider (when tuning enabled)
            if (tuningVisible && state.assetSubStrategy == io.github.deserthouse.opticon.ui.state.AssetSubStrategy.ADAPTIVE_DECOMPOSE) {
                Spacer(Modifier.height(12.dp))
                SliderLabel(stringResource(R.string.algo_slider_scale), "%.2f".format(state.redrawParams.scale))
                Slider(value = state.redrawParams.scale, onValueChange = viewModel::setScale, valueRange = 0.5f..2f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ━━━ ③c: ALGORITHM adjustments ━━━
@Composable
private fun AlgorithmAdjustments(
    state: AppDetailState,
    viewModel: AppDetailViewModel,
    tuningVisible: Boolean,
    algoInputClass: io.github.deserthouse.opticon.engine.IconRedrawEngine.InputClass?,
    pickLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    Card(shape = OptShapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Source picker
            val sourceLabel = when (state.algoSource) {
                io.github.deserthouse.opticon.ui.state.AlgoSource.SELF -> stringResource(R.string.algo_source_self)
                else -> stringResource(R.string.algo_source_upload)
            }
            SettingItem(
                Icons.Rounded.Tune,
                stringResource(R.string.algo_source_title),
                stringResource(R.string.algo_source_desc),
                onClick = {
                    if (state.algoSource == io.github.deserthouse.opticon.ui.state.AlgoSource.SELF) {
                        pickLauncher.launch("image/*")
                    } else {
                        viewModel.setAlgoSource(io.github.deserthouse.opticon.ui.state.AlgoSource.SELF)
                    }
                }
            ) {
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            // Fine-tune entry (when tuning enabled)
            if (tuningVisible) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                AlgoWipSection(viewModel::showSheet)
            }
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
    onScaleChange: (Float) -> Unit, onOffsetXChange: (Float) -> Unit, onOffsetYChange: (Float) -> Unit,
    onThresholdChange: (Int) -> Unit, onRestoreAuto: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 48.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.algo_panel_title_wip), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRestoreAuto) {
                    Text(stringResource(R.string.algo_restore_auto), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.algo_panel_wip_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            SliderLabel(stringResource(R.string.algo_slider_scale), "%.2f".format(scale))
            Slider(value = scale, onValueChange = onScaleChange, valueRange = 0.5f..2f, modifier = Modifier.fillMaxWidth())
            SliderLabel(stringResource(R.string.algo_slider_offset_x), "%.0f".format(offsetX))
            Slider(value = offsetX, onValueChange = onOffsetXChange, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
            SliderLabel(stringResource(R.string.algo_slider_offset_y), "%.0f".format(offsetY))
            Slider(value = offsetY, onValueChange = onOffsetYChange, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
            if (inputClass != io.github.deserthouse.opticon.engine.IconRedrawEngine.InputClass.TRANSPARENT_MARGIN) {
                SliderLabel(stringResource(R.string.algo_slider_purity), "%.0f".format(threshold))
                Slider(value = threshold, onValueChange = { onThresholdChange(it.toInt()) }, valueRange = 0f..255f, modifier = Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.algo_auto_alpha_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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
