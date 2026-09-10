package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.engine.IconPackEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.ui.component.PreviewCard
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.viewmodel.AppDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MATERIAL_ICONS = listOf(
    "Home" to Icons.Filled.Home, "Settings" to Icons.Filled.Settings, "Search" to Icons.Filled.Search,
    "Share" to Icons.Filled.Share, "Star" to Icons.Filled.Star, "Favorite" to Icons.Filled.Favorite,
    "Person" to Icons.Filled.Person, "Notifications" to Icons.Filled.Notifications, "Info" to Icons.Filled.Info,
    "Warning" to Icons.Filled.Warning, "ThumbUp" to Icons.Filled.ThumbUp, "Phone" to Icons.Rounded.Phone,
    "Email" to Icons.Rounded.Email, "Lock" to Icons.Rounded.Lock, "Cloud" to Icons.Rounded.Cloud,
    "Music" to Icons.Rounded.MusicNote, "Gaming" to Icons.Rounded.SportsEsports, "Map" to Icons.Rounded.Map,
    "Shopping" to Icons.Rounded.ShoppingCart, "Check" to Icons.Filled.Check, "Palette" to Icons.Filled.Favorite
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: AppDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    LaunchedEffect(packageName) { viewModel.loadApp(packageName) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.setCustomIconPath(it.toString()) } }
    val configSavedText = stringResource(R.string.config_saved)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(state.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { viewModel.saveConfig(); Toast.makeText(context, configSavedText, Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Filled.Check, stringResource(R.string.save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            PreviewCard(state.previewBitmap, state.previewMode, state.isRedrawing, viewModel::togglePreviewMode)
            Spacer(Modifier.height(20.dp))

            // ── 启用开关（卡片化，与整体语言统一） ──
            StrategyCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.enable_custom_icon), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.source_prefix, stringResource(io.github.deserthouse.opticon.ui.state.hitLevelRes(state.hitLevel))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.methodEnabled, viewModel::setMethodEnabled)
                }
            }
            Spacer(Modifier.height(12.dp))

            val enabled = state.methodEnabled

            // Strategy 1: Fankes
            StrategyCard(selected = state.strategy == IconStrategy.FANKES) {
            val fankesHasIcon = remember(state.packageName) { IconLibEngine.hasIcon(state.packageName) }
            val fankesMeta = remember(state.packageName) { IconLibEngine.getMeta(state.packageName) }
            StrategyRadio(
                label = stringResource(R.string.strategy_fankes_label),
                desc = if (fankesHasIcon) stringResource(R.string.strategy_fankes_covered, fankesMeta?.contributorName ?: "")
                       else stringResource(R.string.strategy_fankes_not_covered),
                selected = state.strategy == IconStrategy.FANKES,
                enabled = enabled,
                onClick = { viewModel.setStrategy(IconStrategy.FANKES) }
            )
            AnimatedVisibility(visible = enabled && state.strategy == IconStrategy.FANKES) {
                if (fankesHasIcon) {
                    Text(stringResource(R.string.strategy_fankes_contributor, fankesMeta?.contributorName ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp))
                } else {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.strategy_fankes_submit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fankes/AndroidNotifyIconAdapt/issues/new"))) },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Text(stringResource(R.string.strategy_fankes_submit_btn), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            }

            Spacer(Modifier.height(12.dp))

            // Strategy 2: Asset Import
            StrategyCard(selected = state.strategy == IconStrategy.ASSET_IMPORT) {
            StrategyRadio(
                label = stringResource(R.string.strategy_asset_label),
                desc = stringResource(R.string.strategy_asset_desc),
                selected = state.strategy == IconStrategy.ASSET_IMPORT,
                enabled = enabled,
                onClick = { viewModel.setStrategy(IconStrategy.ASSET_IMPORT) }
            )
            AnimatedVisibility(visible = enabled && state.strategy == IconStrategy.ASSET_IMPORT) {
                AssetImportPanel(state, viewModel, enabled)
            }
            }

            Spacer(Modifier.height(12.dp))

            // Strategy 3: Algorithm (WIP)
            StrategyCard(selected = state.strategy == IconStrategy.ALGORITHM) {
            StrategyRadio(
                label = stringResource(R.string.strategy_algo_label),
                desc = stringResource(R.string.strategy_algo_wip),
                selected = state.strategy == IconStrategy.ALGORITHM,
                enabled = enabled,
                onClick = { viewModel.setStrategy(IconStrategy.ALGORITHM) }
            )
            AnimatedVisibility(visible = enabled && state.strategy == IconStrategy.ALGORITHM) {
                AlgoWipSection(viewModel::showSheet)
            }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.sheetVisible) {
        AlgoWipSheet(onDismiss = viewModel::hideSheet, onScaleChange = viewModel::setScale,
            onOffsetXChange = viewModel::setOffsetX, onOffsetYChange = viewModel::setOffsetY,
            onThresholdChange = viewModel::setThreshold)
    }
}

/** M3E strategy group card — 24dp radius, floats on layered background */
@Composable
private fun StrategyCard(selected: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // M3E: selected strategy gets a primaryContainer wash — the card itself
    // answers "which one am I on", not just the radio dot.
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(220), label = "strategyCardBg"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(vertical = 8.dp),
            content = content
        )
    }
}

// Strategy radio with optional disable
@Composable
private fun StrategyRadio(label: String, desc: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.selectable(selected = selected, onClick = onClick) else Modifier).padding(vertical = 10.dp).then(Modifier), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.alpha(alpha)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssetImportPanel(state: io.github.deserthouse.opticon.ui.state.AppDetailState, viewModel: AppDetailViewModel, enabled: Boolean) {
    Column(Modifier.padding(horizontal = 32.dp)) {
        val alpha = if (enabled) 1f else 0.4f

        // 1st: Adaptive Decompose
        AssetSubRadio(stringResource(R.string.strategy_asset_b_label), state.assetSubStrategy == AssetSubStrategy.ADAPTIVE_DECOMPOSE, enabled) { viewModel.setAssetSubStrategy(AssetSubStrategy.ADAPTIVE_DECOMPOSE) }
        AnimatedVisibility(state.assetSubStrategy == AssetSubStrategy.ADAPTIVE_DECOMPOSE) { AdaptiveDecomposeSection(state) }

        // 2nd: Perfect Icons
        val ctx = LocalContext.current
        val picpHasIcon = remember(state.packageName) { PicpEngine.hasIcon(state.packageName, ctx) }
        AssetSubRadio(stringResource(R.string.strategy_picp_label), state.assetSubStrategy == AssetSubStrategy.PERFECT_ICONS, enabled) { viewModel.setAssetSubStrategy(AssetSubStrategy.PERFECT_ICONS) }
        AnimatedVisibility(state.assetSubStrategy == AssetSubStrategy.PERFECT_ICONS) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (picpHasIcon) {
                    OutlinedButton(onClick = { viewModel.downloadPicpIcon() }, enabled = !state.perfectIconsLoading && enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        if (state.perfectIconsLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(if (state.perfectIconsBitmap != null) "PICP icon loaded" else "Pull PICP icon")
                    }
                } else {
                    val ctx = LocalContext.current
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.strategy_picp_not_downloaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.strategy_picp_submit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PicpEngine.FEEDBACK_URL))) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                                Text(stringResource(R.string.strategy_picp_submit_btn), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3rd: Icon Pack (WIP)
        AssetSubRadio(stringResource(R.string.strategy_asset_a_label), state.assetSubStrategy == AssetSubStrategy.ICON_PACK, enabled) { viewModel.setAssetSubStrategy(AssetSubStrategy.ICON_PACK) }
        AnimatedVisibility(state.assetSubStrategy == AssetSubStrategy.ICON_PACK) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (state.iconPacks.isEmpty()) Text(stringResource(R.string.no_icon_packs_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else IconPackSection(state, viewModel)
                Text(stringResource(R.string.strategy_asset_a_wip_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AssetSubRadio(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.selectable(selected = selected, onClick = onClick) else Modifier).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconPackSection(state: io.github.deserthouse.opticon.ui.state.AppDetailState, viewModel: AppDetailViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selLabel = state.iconPacks.find { it.packageName == state.selectedIconPack }?.label ?: stringResource(R.string.select_icon_pack)
    val ctx = LocalContext.current
    Column {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(selLabel, {}, readOnly = true, label = { Text(stringResource(R.string.select_icon_pack)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.iconPacks.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { viewModel.setSelectedIconPack(p.packageName); expanded = false }) }
            }
        }
        val selPkg = state.selectedIconPack
        if (selPkg != null && remember(selPkg) { IconPackEngine.hasLaunchableActivity(ctx, selPkg) }) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(selPkg)?.let { ctx.startActivity(it) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.strategy_asset_open_pack_app))
            }
        }
        if (state.iconPackIcons.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("${state.iconPackIcons.size} ${stringResource(R.string.icons_available)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(180.dp)) {
                items(state.iconPackIcons.size, key = { "${state.iconPackIcons[it].componentRaw}_${state.iconPackIcons[it].drawableName}" }) { i ->
                    val entry = state.iconPackIcons[i]
                    IconPackIconCard(entry, selPkg ?: "", state.selectedPackIconDrawable == entry.drawableName) { viewModel.setSelectedPackIconDrawable(entry.drawableName) }
                }
            }
        }
    }
}

@Composable
private fun IconPackIconCard(entry: IconPackEngine.PackIconEntry, iconPackPkg: String, selected: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry) { preview = withContext(Dispatchers.IO) { IconPackEngine.loadIconPreview(context, iconPackPkg, entry.drawableName, 48) } }
    Card(onClick = onSelect, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(4.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            if (preview != null) Image(preview!!.asImageBitmap(), entry.drawableName, Modifier.size(32.dp))
            else Icon(Icons.Filled.Favorite, entry.drawableName, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.drawableName.take(10), style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdaptiveDecomposeSection(state: io.github.deserthouse.opticon.ui.state.AppDetailState) {
    if (state.isAdaptiveIcon == null) Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (state.isAdaptiveIcon == false) Text(stringResource(R.string.strategy_asset_b_not_adaptive), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.strategy_asset_b_foreground), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (state.adaptiveForeground != null) Image(state.adaptiveForeground!!.asImageBitmap(), "fg", Modifier.size(64.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.strategy_asset_b_background), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (state.adaptiveBackground != null) Image(state.adaptiveBackground!!.asImageBitmap(), "bg", Modifier.size(64.dp))
        }
    }
}

// Strategy 3: WIP section and sheet

@Composable
private fun AlgoWipSection(onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.strategy_algo_open_panel_wip))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgoWipSheet(
    onDismiss: () -> Unit,
    onScaleChange: (Float) -> Unit, onOffsetXChange: (Float) -> Unit, onOffsetYChange: (Float) -> Unit, onThresholdChange: (Int) -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.algo_panel_title_wip), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.algo_panel_wip_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // Show sliders as preview (non-functional)
            SliderLabel(stringResource(R.string.algo_slider_scale), "1.00", 0.4f)
            androidx.compose.material3.Slider(value = 1f, onValueChange = {}, enabled = false, valueRange = 0.5f..2f, modifier = Modifier.fillMaxWidth())
            SliderLabel(stringResource(R.string.algo_slider_offset_x), "0", 0.4f)
            androidx.compose.material3.Slider(value = 0f, onValueChange = {}, enabled = false, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
            SliderLabel(stringResource(R.string.algo_slider_offset_y), "0", 0.4f)
            androidx.compose.material3.Slider(value = 0f, onValueChange = {}, enabled = false, valueRange = -50f..50f, modifier = Modifier.fillMaxWidth())
            SliderLabel(stringResource(R.string.algo_slider_purity), "0", 0.4f)
            androidx.compose.material3.Slider(value = 0f, onValueChange = {}, enabled = false, valueRange = 0f..255f, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SliderLabel(label: String, value: String, alpha: Float = 1f) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).alpha(alpha), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
