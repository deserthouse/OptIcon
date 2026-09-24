package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.layout.offset
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
import io.github.deserthouse.opticon.util.TraceLogger
import io.github.deserthouse.opticon.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import io.github.deserthouse.opticon.ui.theme.OptShapes

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
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showMasterOffDialog by remember { mutableStateOf(false) }
    val lsposedActive by viewModel.lsposedActive.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rechecking by viewModel.rechecking.collectAsState()
    val emojiUnlocked by viewModel.emojiUnlocked.collectAsState()
    val rambleExtraShown by viewModel.rambleExtraShown.collectAsState()
    val shadeAppIconMode by viewModel.shadeAppIconMode.collectAsState()
    val forceMono by viewModel.forceMono.collectAsState()
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

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.consumeToast()
        }
    }

    infoMessage?.let { msg ->
        AlertDialog(onDismissRequest = viewModel::dismissInfo,
            title = { Text(stringResource(R.string.info_title)) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::dismissInfo) { Text(stringResource(R.string.ok_label)) } })
    }

    if (showMasterOffDialog) {
        AlertDialog(onDismissRequest = { showMasterOffDialog = false },
            title = { Text(stringResource(R.string.master_off_dialog_title)) },
            text = { Text(stringResource(R.string.master_off_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showMasterOffDialog = false
                    viewModel.setMasterEnabled(false)
                }) { Text(stringResource(R.string.action_turn_off)) }
            },
            dismissButton = {
                TextButton(onClick = { showMasterOffDialog = false }) { Text(stringResource(R.string.cancel)) }
            })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))

            // ── 状态区 ──
            RuntimeStatusCard(lsposedActive, rootAvailable, rechecking) { viewModel.recheckStatuses(force = true) }

            // ── 模块控制 ──
            SectionTitle(stringResource(R.string.module_control))
            SettingsCard {
                SettingItem(
                    Icons.Rounded.BugReport,
                    stringResource(R.string.master_switch),
                    stringResource(R.string.master_switch_desc),
                    onClick = { if (masterEnabled) showMasterOffDialog = true else viewModel.setMasterEnabled(true) }
                ) {
                    Switch(checked = masterEnabled, onCheckedChange = { on ->
                        if (on) viewModel.setMasterEnabled(true) else showMasterOffDialog = true
                    })
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SettingItem(
                    Icons.Rounded.BugReport,
                    stringResource(R.string.verbose_logging),
                    stringResource(R.string.verbose_logging_desc),
                    onClick = { viewModel.setVerboseLogging(!verboseLogging) }
                ) {
                    Switch(checked = verboseLogging, onCheckedChange = viewModel::setVerboseLogging)
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


            // ── 通知图标样式（模块对 shade 的行为） ──
            SectionTitle(stringResource(R.string.notif_style_section))
            SettingsCard {
                var showShadeSheet by remember { mutableStateOf(false) }
                val shadeModeLabel = when (shadeAppIconMode) {
                    "notif" -> stringResource(R.string.shade_mode_notif)
                    "pref" -> stringResource(R.string.shade_mode_pref)
                    else -> stringResource(R.string.shade_mode_app)
                }
                SettingItem(
                    Icons.Rounded.Extension,
                    stringResource(R.string.shade_icon_title),
                    stringResource(R.string.shade_icon_desc),
                    onClick = { showShadeSheet = true }
                ) {
                    Text(
                        shadeModeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (showShadeSheet) {
                    RadioSheet(
                        title = stringResource(R.string.shade_icon_title),
                        selected = shadeAppIconMode,
                        options = listOf(
                            RadioOption("app", stringResource(R.string.shade_mode_app), stringResource(R.string.shade_mode_app_desc)),
                            RadioOption("notif", stringResource(R.string.shade_mode_notif), stringResource(R.string.shade_mode_notif_desc)),
                            RadioOption("pref", stringResource(R.string.shade_mode_pref), stringResource(R.string.shade_mode_pref_desc))
                        ),
                        onSelect = { viewModel.setShadeAppIconMode(it); showShadeSheet = false },
                        onDismiss = { showShadeSheet = false }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                val forceMonoUnknown by viewModel.forceMonoUnknown.collectAsState()
                // D2: the "policy unreadable" hint lives in the supporting
                // slot, not under the Switch — the trailing slot gave the
                // long hint no width constraint and the row collapsed.
                SettingItem(
                    Icons.Rounded.Palette,
                    stringResource(R.string.force_mono_title),
                    stringResource(R.string.force_mono_desc),
                    onClick = { viewModel.setForceMono(!forceMono) },
                    supportingHint = if (forceMonoUnknown) stringResource(R.string.force_mono_unknown) else null
                ) {
                    Switch(checked = forceMono, onCheckedChange = { viewModel.setForceMono(it) })
                }
            }

            // ── 应用（App 自身的外壳行为） ──
            SectionTitle(stringResource(R.string.app_section))
            SettingsCard {
                // Per-app locale is a system capability (Android 13+); on 12
                // the row degrades to an honest "system only" note.
                val localeSupported = android.os.Build.VERSION.SDK_INT >= 33
                var showLangSheet by remember { mutableStateOf(false) }
                val currentLocaleTag = if (localeSupported) remember {
                    val lm = context.getSystemService(android.app.LocaleManager::class.java)
                    lm?.applicationLocales?.toLanguageTags().orEmpty()
                } else ""
                val langLabel = when {
                    !localeSupported -> "N/A"
                    currentLocaleTag.startsWith("zh") -> "简体中文"
                    currentLocaleTag.startsWith("en") -> "English"
                    else -> stringResource(R.string.language_system)
                }
                SettingItem(
                    Icons.Rounded.Translate,
                    stringResource(R.string.language_title),
                    stringResource(R.string.language_desc),
                    onClick = if (localeSupported) ({ showLangSheet = true }) else null,
                    action = {
                        Text(
                            langLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (localeSupported) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                if (showLangSheet) {
                    RadioSheet(
                        title = stringResource(R.string.language_title),
                        selected = currentLocaleTag,
                        options = listOf(
                            RadioOption("", stringResource(R.string.language_system), null),
                            RadioOption("en", "English", null),
                            RadioOption("zh-CN", "简体中文", null)
                        ),
                        onSelect = { applyAppLocale(context, it); showLangSheet = false },
                        onDismiss = { showLangSheet = false }
                    )
                }
                // Predictive back gesture preview is a system capability that
                // only exists on Android 13+; on 12 the option degrades to a
                // plain back press (harmless). Be honest per OS version.
                val predictiveSupported = android.os.Build.VERSION.SDK_INT >= 33
                var showPredictiveDialog by remember { mutableStateOf(false) }
                SettingItem(
                    Icons.Rounded.Gesture,
                    stringResource(R.string.predictive_back),
                    stringResource(if (predictiveSupported) R.string.predictive_back_desc else R.string.predictive_back_desc_legacy),
                    onClick = if (predictiveSupported) ({ showPredictiveDialog = true }) else null
                ) {
                    if (predictiveSupported) {
                        Text(stringResource(R.string.enabled_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("N/A", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showPredictiveDialog) {
                    AlertDialog(onDismissRequest = { showPredictiveDialog = false },
                        title = { Text(stringResource(R.string.predictive_back_dialog_title)) },
                        text = { Text(stringResource(R.string.predictive_back_dialog_text)) },
                        confirmButton = { TextButton(onClick = { showPredictiveDialog = false }) { Text(stringResource(R.string.ok_label)) } })
                }
            }

            // ── 关于 ──
            SectionTitle(stringResource(R.string.about_section))
            // Card-level onClick = the WHOLE card is the level-1 easter-egg
            // button: ripple and hit area cover the full rounded outline.
            // Locked: counts taps (🐾…🐺). Unlocked: any tap on non-interactive
            // area collapses/expands; child clickables (ramble, links) consume
            // their own regions first.
            Card(
                onClick = {
                    if (emojiUnlocked) viewModel.toggleEasterEgg()
                    else viewModel.onVersionTapped()
                },
                shape = OptShapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("OptIcon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (emojiUnlocked) {
                        Icon(
                            if (easterEggExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            stringResource(R.string.hero_status_cd),
                            Modifier.padding(start = 8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Project home link — always visible, below the description.
                // Child clickable consumes its own taps (never feeds the egg
                // counter); row-level hit target, credits-link visual idiom.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(OptShapes.medium)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deserthouse/OptIcon"))) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.repo_link_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "github.com/deserthouse/OptIcon",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Rounded.Launch, stringResource(R.string.repo_link_label),
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(
                    visible = easterEggExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Easter egg #0: tap the avatar for the full artwork
                            Image(
                                painterResource(R.drawable.avatar_deserthouse),
                                "avatar",
                                Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { showAvatarDialog = true }
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        stringResource(R.string.easter_egg_author_name),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.easter_egg_author_handle),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    stringResource(R.string.easter_egg_author_en),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.easter_egg_vibe_line),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                        .clip(OptShapes.small)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deserthouse"))) }
                        .padding(vertical = 6.dp)) {
                            Text("github.com/deserthouse", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Launch, "GitHub", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(14.dp))
                        // Whole musings block (title + body + extra line) is the
                        // level-2 tap target; once burned out it becomes the
                        // level-3 "not a single drop left" target.
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(OptShapes.medium)
                                .clickable { viewModel.onRambleTapped() }
                                .padding(horizontal = 4.dp, vertical = 10.dp)
                        ) {
                            Text(stringResource(R.string.easter_egg_card_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.easter_egg_ai_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
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
                                    modifier = Modifier.padding(top = 10.dp)
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
                    CreditEntry(stringResource(R.string.credit_pzcn), stringResource(R.string.credit_pzcn_desc), "https://github.com/pzcn/Perfect-Icons-Completion-Project")
                    CreditEntry(stringResource(R.string.credit_howard), stringResource(R.string.credit_howard_desc), "https://github.com/Xposed-Modules-Repo/io.github.howard20181.notificationiconfix")
                    CreditEntry(stringResource(R.string.credit_iconify), stringResource(R.string.credit_iconify_desc), "https://github.com/MohamedRejworkshop/Iconify")
                    CreditEntry(stringResource(R.string.credit_lsposed), stringResource(R.string.credit_lsposed_desc), "https://github.com/libxposed/api")
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
        // Avatar full-size viewer (easter egg #0)
        if (showAvatarDialog) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showAvatarDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { showAvatarDialog = false }
                ) {
                    Image(
                        painterResource(R.drawable.avatar_full),
                        contentDescription = "avatar",
                        modifier = Modifier.padding(10.dp).clip(OptShapes.large)
                    )
                }
            }
        }

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
        Text(project, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        // Whole row is the hit target — the blue URL is the strongest link
        // affordance on screen, so the click must live there too, not only on
        // the 16dp launcher glyph. D4: the 16dp indent sits INSIDE the
        // clickable region, ripple spans the full card width.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // D5 gate: ripple width is full-card; heightIn keeps the touch
            // target at the 48dp floor without inflating the visual text.
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(OptShapes.medium).clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Launch, stringResource(R.string.hero_status_cd), Modifier.padding(start = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (syncing) {
                // Reserve the same 48dp footprint as the sync IconButton — a
                // bare 20dp spinner would shrink the header row mid-sync.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else IconButton(onClick = onSync) { Icon(Icons.Rounded.Sync, "Sync", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        status?.let {
            val isFailure = it.contains("failed", ignoreCase = true) || it.contains("unavailable", ignoreCase = true) || it.contains("Invalid", ignoreCase = true)
            Text(
                it, style = MaterialTheme.typography.labelSmall,
                color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // D4: clickable row carries its own 16dp indent — ripple spans
                // the full card width.
                Row(Modifier.fillMaxWidth()
                    .clickable { onSelect(source.id) }
                    .padding(vertical = 6.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = activeId == source.id, onClick = { onSelect(source.id) }, modifier = Modifier.padding(end = 4.dp))
                    Text(getSourceDisplayName(source), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEditSource(source) }) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteSource(source.id) }) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
                // Description + link (aligned under the row text: 16dp indent
                // + radio 48dp touch target)
                getSourceDescription(source)?.let { fullDesc ->
                    val lines = fullDesc.split("\n")
                    val descText = lines.dropLast(1).joinToString("\n").ifBlank { lines.first() }
                    val linkUrl = lines.lastOrNull()?.takeIf { it.startsWith("http") }
                    Column(Modifier.padding(start = 68.dp, end = 16.dp)) {
                        if (descText.isNotBlank()) Text(descText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (linkUrl != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(OptShapes.small)
                                .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                                .padding(vertical = 6.dp)) {
                                Text(linkUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Launch, "Open", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
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
    data object Ok : RestartResult
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
            shape = OptShapes.medium
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
                    stringResource(R.string.restart_result_ok),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when (val r = result) {
            is RestartResult.Failed -> RestartFeedbackRow(
                // E7: no "exit N" jargon — the user cares about root permission,
                // stderr detail stays as the suffix.
                text = stringResource(R.string.restart_result_failed,
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
            RestartResult.Ok
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
    hint: String? = null,
    // E7: root-unavailable is expected on non-rooted devices — a neutral
    // note, not an error-red alarm (LSPosed-inactive keeps the error color).
    inactiveIsNeutral: Boolean = false
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        inactiveIsNeutral -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    label = "statusColor"
                )
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                if (hint != null && !checking) Text(hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), lineHeight = 14.sp)
            }
            if (checking) {
                // Same 48dp footprint as the recheck IconButton — prevents
                // the row from shrinking while a check is in flight.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                // D4b: no explicit size() — IconButton's default is 48dp,
                // the 18dp glyph sets the visual size.
                IconButton(onClick = onRecheck) {
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
            onRecheck = onRecheck,
            inactiveIsNeutral = true
        )
    }
}

@Composable private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = OptShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        // D4 (触摸几何): clip FIRST, no padding at the content layer —
        // descendant rows sit edge-to-edge in the clipped column and carry
        // their own 16dp indent INSIDE their clickable region, so the ripple
        // and hit area span the full card width (the old padding-then-clip
        // order made every ripple stop 16dp short of the card outline).
        Column(Modifier.clip(OptShapes.large), content = content)
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable internal fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    // D2: supplementary hint belongs in the supporting-text slot below the
    // subtitle — a trailing-slot hint fights the Switch/label for width and
    // breaks the row (mid-word wrap at large font scales). Placed BEFORE the
    // trailing action lambda: a new function-type-adjacent param after the
    // lambda slot steals the call site's trailing lambda (全局规范七.5).
    supportingHint: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    // D4: clickable BEFORE padding — ripple and hit area cover the full row
    // width; the 16dp indent is inside the clickable region.
    Row(Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (supportingHint != null) {
                Text(supportingHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, lineHeight = 14.sp)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            // Align with the title line, not the row center: a centered label
            // lands mid-way down a multi-line subtitle and reads as part of it.
            Box(Modifier.align(Alignment.Top).padding(top = 2.dp)) { action() }
        }
    }
}

/** Apply per-app locale (Android 13+). Empty tag = follow system.
 *  The system persists it and recreates the activity; localization
 *  applies to every locale-qualified resource automatically. */
private fun applyAppLocale(context: android.content.Context, tag: String) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val lm = context.getSystemService(android.app.LocaleManager::class.java) ?: return
    try {
        if (tag.isEmpty()) lm.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
        else lm.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
        TraceLogger.i("OptIcon/Settings", "app locale set: ${tag.ifEmpty { "system" }}")
    } catch (e: Exception) {
        TraceLogger.w("OptIcon/Settings", "setApplicationLocales failed: ${e.message}")
    }
}

data class RadioOption<T>(val value: T, val label: String, val desc: String?)

/** M3 bottom-sheet single-choice picker: the whole row is the hit target,
 *  selected option carries the primary color + medium weight, options can
 *  carry a supporting description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> RadioSheet(
    title: String,
    options: List<RadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
            options.forEach { opt ->
                val selectedHere = opt.value == selected
                Row(
                    Modifier.fillMaxWidth()
                        .clip(OptShapes.medium)
                        .clickable { onSelect(opt.value) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedHere, onClick = { onSelect(opt.value) })
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selectedHere) FontWeight.Medium else null,
                            color = if (selectedHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (opt.desc != null) {
                            Text(opt.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
