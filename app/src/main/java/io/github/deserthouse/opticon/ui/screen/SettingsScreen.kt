package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.github.deserthouse.opticon.BuildConfig
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val verboseLogging by viewModel.verboseLogging.collectAsState()
    val masterEnabled by viewModel.masterEnabled.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()
    val toastEvent by viewModel.toastEvent.collectAsState()
    val easterEggExpanded by viewModel.easterEggExpanded.collectAsState()
    val lsposedActive by viewModel.lsposedActive.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rechecking by viewModel.rechecking.collectAsState()
    val emojiUnlocked by viewModel.emojiUnlocked.collectAsState()
    val rambleExtraShown by viewModel.rambleExtraShown.collectAsState()
    val aniaSources by viewModel.aniaSources.collectAsState()
    val activeAniaSource by viewModel.activeAniaSource.collectAsState()
    val aniaSyncing by viewModel.aniaSyncing.collectAsState()
    val aniaSyncStatus by viewModel.aniaSyncStatus.collectAsState()
    val aniaIconCount by viewModel.aniaIconCount.collectAsState()
    val picpSources by viewModel.picpSources.collectAsState()
    val activePicpSource by viewModel.activePicpSource.collectAsState()
    val picpSyncing by viewModel.picpSyncing.collectAsState()
    val picpSyncStatus by viewModel.picpSyncStatus.collectAsState()
    val picpCount by viewModel.picpCount.collectAsState()
    val showAddSource by viewModel.showAddSource.collectAsState()
    val showEditSource by viewModel.showEditSource.collectAsState()

    LaunchedEffect(toastEvent) { toastEvent?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeToast() } }

    infoMessage?.let { msg ->
        AlertDialog(onDismissRequest = viewModel::dismissInfo,
            title = { Text(stringResource(R.string.info_title)) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::dismissInfo) { Text(stringResource(R.string.ok_label)) } })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))

            // ── 状态区 ──
            RuntimeStatusCard(lsposedActive, rootAvailable, rechecking) { viewModel.recheckStatuses() }

            // ── 模块控制 ──
            SectionTitle(stringResource(R.string.module_control))
            SettingsCard {
                SettingItem(Icons.Rounded.BugReport, stringResource(R.string.master_switch), stringResource(R.string.master_switch_desc)) {
                    Switch(checked = masterEnabled, onCheckedChange = viewModel::setMasterEnabled)
                }
            }

            // ── 工具 / 订阅源 ──
            SectionTitle(stringResource(R.string.tools_section))
            SettingsCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val aniaBuiltinHidden = aniaSources.none { it.id == "ania_raw" }
                    val picpBuiltinHidden = picpSources.none { it.id == "picp_github" }

                    SourceSection(stringResource(R.string.ania_section_title), aniaSources, activeAniaSource, aniaIconCount, aniaSyncing, aniaSyncStatus,
                        onSelect = viewModel::setActiveAniaSource, onSync = viewModel::syncAnia,
                        onAddSource = viewModel::startAddSource, onEditSource = viewModel::startEditSource, onDeleteSource = viewModel::removeSource,
                        restoreLabel = if (aniaBuiltinHidden) stringResource(R.string.restore_default_ania) else null,
                        onRestoreDefault = if (aniaBuiltinHidden) viewModel::restoreDefaultAnia else null)
                    Spacer(Modifier.height(8.dp))
                    SourceSection(stringResource(R.string.picp_section_title), picpSources, activePicpSource, picpCount, picpSyncing, picpSyncStatus,
                        onSelect = viewModel::setActivePicpSource, onSync = viewModel::syncPicp,
                        onAddSource = viewModel::startAddSource, onEditSource = viewModel::startEditSource, onDeleteSource = viewModel::removeSource,
                        restoreLabel = if (picpBuiltinHidden) stringResource(R.string.restore_default_picp) else null,
                        onRestoreDefault = if (picpBuiltinHidden) viewModel::restoreDefaultPicp else null)
                }
            }
            RestartSystemUiButton()

            // ── 调试 ──
            SectionTitle(stringResource(R.string.debug))
            SettingsCard {
                SettingItem(Icons.Rounded.BugReport, stringResource(R.string.verbose_logging), stringResource(R.string.verbose_logging_desc)) {
                    Switch(checked = verboseLogging, onCheckedChange = viewModel::setVerboseLogging)
                }
            }

            // ── 界面 ──
            SectionTitle(stringResource(R.string.ui_section))
            SettingsCard {
                // Predictive back gesture preview is a system capability that
                // only exists on Android 13+; on 12 the option degrades to a
                // plain back press (harmless). Be honest per OS version.
                val predictiveSupported = android.os.Build.VERSION.SDK_INT >= 33
                SettingItem(
                    Icons.Rounded.Gesture,
                    stringResource(R.string.predictive_back),
                    stringResource(if (predictiveSupported) R.string.predictive_back_desc else R.string.predictive_back_desc_legacy)
                ) {
                    if (predictiveSupported) {
                        Text(stringResource(R.string.enabled_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("N/A", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 关于 ──
            SectionTitle(stringResource(R.string.about_section))
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth()
                        // padding BEFORE clickable → ripple fills the whole row
                        .padding(vertical = 6.dp)
                        .clickable(enabled = !emojiUnlocked) { viewModel.onVersionTapped() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Info, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("OptIcon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("v${BuildConfig.VERSION_NAME} \u00b7 " + stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (emojiUnlocked) {
                        IconButton(onClick = viewModel::toggleEasterEgg) {
                            Icon(
                                if (easterEggExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                stringResource(R.string.hero_status_cd),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = easterEggExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.avatar_deserthouse), "avatar", Modifier.size(48.dp).clip(CircleShape))
                            Spacer(Modifier.width(12.dp))
                            // "Vibrator: 澪(Mio)狼(Ookami) (Ling the Wolp)" — ruby
                            // annotations above the kanji; zh locale shows plain 澪狼.
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(stringResource(R.string.easter_egg_author_prefix), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                RubyKanji(stringResource(R.string.easter_egg_ruby_1), stringResource(R.string.easter_egg_kanji_1))
                                RubyKanji(stringResource(R.string.easter_egg_ruby_2), stringResource(R.string.easter_egg_kanji_2))
                                Text(stringResource(R.string.easter_egg_author_suffix), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.easter_egg_vibe_line),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deserthouse"))) }) {
                            Text("github.com/deserthouse", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Launch, "GitHub", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.easter_egg_card_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.easter_egg_ai_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { viewModel.onRambleTapped() }
                                        .padding(horizontal = 4.dp, vertical = 8.dp)
                                )
                                AnimatedVisibility(
                                    visible = rambleExtraShown,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        stringResource(R.string.easter_egg_extra_line),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 致谢（页面末尾） ──
            SectionTitle(stringResource(R.string.credits_section))
            SettingsCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    CreditEntry(stringResource(R.string.credit_fankes), stringResource(R.string.credit_fankes_desc), "https://github.com/fankes/AndroidNotifyIconAdapt")
                    CreditEntry(stringResource(R.string.credit_howard), stringResource(R.string.credit_howard_desc), "https://github.com/Xposed-Modules-Repo/io.github.howard20181.notificationiconfix")
                    CreditEntry(stringResource(R.string.credit_pzcn), stringResource(R.string.credit_pzcn_desc), "https://github.com/pzcn/Perfect-Icons-Completion-Project")
                    CreditEntry(stringResource(R.string.credit_lsposed), stringResource(R.string.credit_lsposed_desc), "https://github.com/libxposed/api")
                    CreditEntry(stringResource(R.string.credit_iconify), stringResource(R.string.credit_iconify_desc), "https://github.com/MohamedRejworkshop/Iconify")
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        // Add source dialog
        if (showAddSource) AddSourceDialog(
            onDismiss = viewModel::dismissAddSource,
            onConfirm = viewModel::addSource
        )

        // Edit source dialog
        showEditSource?.let { source ->
            EditSourceDialog(
                source = source,
                onDismiss = viewModel::dismissEditSource,
                onConfirm = { id, name, url, desc -> viewModel.updateSource(id, name, url, desc) }
            )
        }
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.source_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.source_url_label)) },
                    placeholder = { Text(stringResource(R.string.source_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.source_description_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(name, url, desc) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text(stringResource(R.string.add_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_btn)) } }
    )
}

@Composable
private fun EditSourceDialog(
    source: PreferenceManager.SubscriptionSource,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(source.name) }
    var url by remember { mutableStateOf(source.url) }
    var desc by remember { mutableStateOf(source.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_source_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.source_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.source_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.source_description_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(source.id, name, url, desc) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text(stringResource(R.string.save_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_btn)) } }
    )
}

@Composable
private fun CreditEntry(project: String, description: String, url: String) {
    val ctx = LocalContext.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(project, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.Launch, "Open", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun getSourceDisplayName(source: PreferenceManager.SubscriptionSource): String {
    return when (source.id) {
        "ania_raw" -> stringResource(R.string.source_ania_raw)
        "picp_github" -> stringResource(R.string.source_picp_github)
        else -> source.name
    }
}

@Composable
private fun getSourceDescription(source: PreferenceManager.SubscriptionSource): String? {
    return when (source.id) {
        "ania_raw" -> stringResource(R.string.source_ania_raw_desc)
        "picp_github" -> stringResource(R.string.source_picp_github_desc)
        else -> source.description.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun SourceSection(
    title: String,
    sources: List<PreferenceManager.SubscriptionSource>,
    activeId: String,
    iconCount: Int,
    syncing: Boolean,
    status: String?,
    onSelect: (String) -> Unit,
    onSync: () -> Unit,
    onAddSource: () -> Unit = {},
    onEditSource: (PreferenceManager.SubscriptionSource) -> Unit = {},
    onDeleteSource: (String) -> Unit = {},
    restoreLabel: String? = null,
    onRestoreDefault: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onSync, Modifier.size(40.dp)) { Icon(Icons.Rounded.Sync, "Sync", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        status?.let {
            val isFailure = it.contains("failed", ignoreCase = true) || it.contains("unavailable", ignoreCase = true) || it.contains("Invalid", ignoreCase = true)
            Text(
                it, style = MaterialTheme.typography.labelSmall,
                color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onSelect(source.id) }.padding(vertical = 2.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = activeId == source.id, onClick = { onSelect(source.id) }, modifier = Modifier.padding(end = 4.dp))
                    Text(getSourceDisplayName(source), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEditSource(source) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteSource(source.id) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
                // Description + link
                getSourceDescription(source)?.let { fullDesc ->
                    val lines = fullDesc.split("\n")
                    val descText = lines.dropLast(1).joinToString("\n").ifBlank { lines.first() }
                    val linkUrl = lines.lastOrNull()?.takeIf { it.startsWith("http") }
                    Column(Modifier.padding(start = 44.dp, end = 4.dp)) {
                        if (descText.isNotBlank()) Text(descText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (linkUrl != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }) {
                                Text(linkUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Launch, "Open", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Row {
            TextButton(onClick = onAddSource) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_custom_btn))
            }
            if (restoreLabel != null && onRestoreDefault != null) {
                TextButton(onClick = onRestoreDefault) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(restoreLabel)
                }
            }
        }
    }
}

/** Result of `su -c kill $(pidof com.android.systemui)` — surfaced inline, no toast */
private sealed interface RestartResult {
    data object Running : RestartResult
    data class Ok(val exitCode: Int) : RestartResult
    data class Failed(val exitCode: Int, val stderr: String) : RestartResult
    data class Error(val message: String) : RestartResult
}

@Composable
private fun RestartSystemUiButton() {
    var showConfirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RestartResult?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val running = result is RestartResult.Running

    // Auto-clear the success line after a few seconds (silent feedback)
    LaunchedEffect(result) {
        if (result is RestartResult.Ok) {
            kotlinx.coroutines.delay(6000)
            result = null
        }
    }

    Column {
        OutlinedButton(
            onClick = { showConfirm = true },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.restart_running))
            } else {
                Text(stringResource(R.string.restart_systemui))
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = result is RestartResult.Ok) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.restart_result_ok, (result as? RestartResult.Ok)?.exitCode ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when (val r = result) {
            is RestartResult.Failed -> RestartFeedbackRow(
                text = stringResource(R.string.restart_result_failed, r.exitCode,
                    if (r.stderr.isNotBlank()) " — ${r.stderr.take(120)}" else ""),
                isError = true)
            is RestartResult.Error -> RestartFeedbackRow(
                text = stringResource(R.string.restart_result_error, r.message),
                isError = true)
            else -> {}
        }
    }

    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.restart_confirm_title)) },
            text = { Text(stringResource(R.string.restart_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    result = RestartResult.Running
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        result = restartSystemUi()
                    }
                }) { Text(stringResource(R.string.restart_confirm_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.ok_label)) } })
    }
}

/** Kanji with small ruby annotation above it (hidden when ruby is empty) */
@Composable
private fun RubyKanji(ruby: String, kanji: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ruby,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(kanji, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RestartFeedbackRow(text: String, isError: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, lineHeight = 14.sp)
    }
}

/**
 * Runs the restart on the caller's dispatcher. Reads the real su exit code —
 * a missing/forbidden su or a failed kill no longer shows a fake success toast.
 */
private suspend fun restartSystemUi(): RestartResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        // pidof = exact package match. pkill -f is a FULL-CMDLINE SUBSTRING
        // match and would collateral-kill any process whose cmdline merely
        // contains the string (e.g. OOS wallpaper engine on A17 → wallpaper
        // + Monet palette reset, reported by the tester).
        val proc = ProcessBuilder("su", "-c", "kill \$(pidof com.android.systemui)")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
            proc.destroy()
            RestartResult.Failed(-1, "timeout")
        } else if (proc.exitValue() == 0) {
            RestartResult.Ok(0)
        } else {
            RestartResult.Failed(proc.exitValue(), output)
        }
    } catch (e: Exception) {
        RestartResult.Error(e.message ?: e.javaClass.simpleName)
    }
}

@Composable
private fun StatusRow(
    title: String,
    statusText: String,
    active: Boolean?,
    checking: Boolean,
    onRecheck: () -> Unit,
    hint: String? = null
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (title.contains("LSPosed")) Icons.Rounded.Extension else Icons.Rounded.Key,
                null, Modifier.padding(end = 12.dp),
                tint = if (checking) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                // 三态: 检测中(中性) → 结果(主色/错误色), 颜色平滑过渡
                val statusColor by animateColorAsState(
                    targetValue = when {
                        checking || active == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                    label = "statusColor"
                )
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                if (hint != null && !checking) Text(hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), lineHeight = 14.sp)
            }
            if (checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRecheck, modifier = Modifier.size(width = 44.dp, height = 36.dp),
                    contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    lsposedActive: Boolean?,
    rootAvailable: Boolean?,
    rechecking: Boolean,
    onRecheck: () -> Unit
) {
    SettingsCard {
        StatusRow(
            title = stringResource(R.string.lsposed_status_title),
            statusText = when {
                rechecking && lsposedActive == null -> stringResource(R.string.status_checking)
                lsposedActive == null -> stringResource(R.string.status_checking)
                lsposedActive -> stringResource(R.string.lsposed_status_active)
                else -> stringResource(R.string.lsposed_status_inactive)
            },
            active = lsposedActive,
            checking = rechecking || lsposedActive == null,
            onRecheck = onRecheck,
            hint = if (lsposedActive == false) stringResource(R.string.lsposed_status_hint) else null
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        StatusRow(
            title = stringResource(R.string.root_status_title),
            statusText = when {
                rechecking && rootAvailable == null -> stringResource(R.string.root_status_checking)
                rootAvailable == null -> stringResource(R.string.root_status_checking)
                rootAvailable -> stringResource(R.string.root_status_active)
                else -> stringResource(R.string.root_status_inactive)
            },
            active = rootAvailable,
            checking = rechecking || rootAvailable == null,
            onRecheck = onRecheck
        )
    }
}

@Composable private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        // clip at card-content level so descendant clickable ripples stay
        // inside the 24dp rounded outline (otherwise they draw square)
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)),
            content = content
        )
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable private fun SettingItem(icon: ImageVector, title: String, subtitle: String, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) action()
    }
}
