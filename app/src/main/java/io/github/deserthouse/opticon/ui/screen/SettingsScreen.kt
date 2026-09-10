package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Info
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val verboseLogging by viewModel.verboseLogging.collectAsState()
    val masterEnabled by viewModel.masterEnabled.collectAsState()
    val predictiveBack by viewModel.predictiveBack.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()
    val toastEvent by viewModel.toastEvent.collectAsState()
    val easterEggExpanded by viewModel.easterEggExpanded.collectAsState()
    val lsposedActive by viewModel.lsposedActive.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
            navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))

            LsposedStatusCard(lsposedActive)
            RootStatusCard(rootAvailable)

            SectionTitle(stringResource(R.string.module_control))
            SettingItem(Icons.Rounded.BugReport, stringResource(R.string.master_switch), stringResource(R.string.master_switch_desc)) {
                Switch(checked = masterEnabled, onCheckedChange = viewModel::setMasterEnabled)
            }
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SectionTitle(stringResource(R.string.tools_section))
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            RestartSystemUiButton()
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SectionTitle(stringResource(R.string.debug))
            SettingItem(Icons.Rounded.BugReport, stringResource(R.string.verbose_logging), stringResource(R.string.verbose_logging_desc)) {
                Switch(checked = verboseLogging, onCheckedChange = viewModel::setVerboseLogging)
            }
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SectionTitle(stringResource(R.string.ui_section))
            SettingItem(Icons.Rounded.Gesture, stringResource(R.string.predictive_back), stringResource(R.string.predictive_back_desc)) {
                Switch(checked = predictiveBack, onCheckedChange = viewModel::setPredictiveBack)
            }
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SectionTitle(stringResource(R.string.about_section))
            Row(Modifier.fillMaxWidth().clickable { viewModel.onVersionTapped() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("OptIcon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text("v${BuildConfig.VERSION_NAME} \u00b7 " + stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(easterEggExpanded) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.avatar_deserthouse), "avatar", Modifier.size(48.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.easter_egg_author), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.easter_egg_email), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deserthouse"))) }) {
                        Text("github.com/deserthouse", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Launch, "GitHub", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.easter_egg_card_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.easter_egg_ai_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SectionTitle(stringResource(R.string.credits_section))
            CreditEntry(stringResource(R.string.credit_fankes), stringResource(R.string.credit_fankes_desc), "https://github.com/fankes/AndroidNotifyIconAdapt")
            CreditEntry(stringResource(R.string.credit_howard), stringResource(R.string.credit_howard_desc), "https://github.com/Xposed-Modules-Repo/io.github.howard20181.notificationiconfix")
            CreditEntry(stringResource(R.string.credit_pzcn), stringResource(R.string.credit_pzcn_desc), "https://github.com/pzcn/Perfect-Icons-Completion-Project")
            CreditEntry(stringResource(R.string.credit_lsposed), stringResource(R.string.credit_lsposed_desc), "https://github.com/libxposed/api")
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
            else IconButton(onClick = onSync, Modifier.size(32.dp)) { Icon(Icons.Rounded.Sync, "Sync", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onSelect(source.id) }.padding(vertical = 2.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = activeId == source.id, onClick = { onSelect(source.id) }, modifier = Modifier.padding(end = 4.dp))
                    Text(getSourceDisplayName(source), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEditSource(source) }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Edit, null, Modifier.size(14.dp)) }
                    IconButton(onClick = { onDeleteSource(source.id) }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Delete, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
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

@Composable
private fun RestartSystemUiButton() {
    var showConfirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    OutlinedButton(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
        Text(stringResource(R.string.restart_systemui))
    }

    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.restart_confirm_title)) },
            text = { Text(stringResource(R.string.restart_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    restartSystemUi(ctx)
                }) { Text(stringResource(R.string.restart_confirm_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.ok_label)) } })
    }
}

private fun restartSystemUi(context: android.content.Context) {
    try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f com.android.systemui"))
        Toast.makeText(context, "SystemUI 正在重启...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "重启失败：Root 权限不可用", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun LsposedStatusCard(active: Boolean) {
    val color = if (active) Color(0xFF4CAF50) else Color(0xFFFF5722)
    val statusText = if (active) stringResource(R.string.lsposed_status_active) else stringResource(R.string.lsposed_status_inactive)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.lsposed_status_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.lsposed_status_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun RootStatusCard(available: Boolean) {
    val color = if (available) Color(0xFF4CAF50) else Color(0xFFFF5722)
    val statusText = if (available) stringResource(R.string.root_status_active) else stringResource(R.string.root_status_inactive)
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.root_status_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
