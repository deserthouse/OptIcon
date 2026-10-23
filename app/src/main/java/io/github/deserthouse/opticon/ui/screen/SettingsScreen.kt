package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.github.deserthouse.opticon.BuildConfig
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.ui.viewmodel.SettingsViewModel

/** Q20 拆分：本文件只留页面编排；共享原语见 SettingsScreenShared.kt，
 *  订阅源/致谢见 SettingsScreenSources.kt，状态卡与重启见 SettingsScreenRuntime.kt。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val verboseLogging by viewModel.verboseLogging.collectAsState()
    val masterEnabled by viewModel.masterEnabled.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()
    val toastEvent by viewModel.toastEvent.collectAsState()
    val easterEggExpanded by viewModel.easterEggExpanded.collectAsState()
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showMasterOffDialog by remember { mutableStateOf(false) }
    val lsposedActive by viewModel.lsposedActive.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rechecking by viewModel.rechecking.collectAsState()
    val aniaSyncFailed by viewModel.aniaSyncFailed.collectAsState()
    val picpSyncFailed by viewModel.picpSyncFailed.collectAsState()
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
        Column(Modifier.fillMaxSize().widthIn(max = 640.dp).padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))

            // ── 状态区 ──
            RuntimeStatusCard(lsposedActive, rootAvailable, rechecking) { viewModel.recheckStatuses(force = true) }

            // ── 模块控制 ──
            SectionTitle(stringResource(R.string.module_control))
            SettingsCard {
                SwitchSettingItem(
                    // A-3 (2026-10-03 AVD audit): master control gets a control
                    // glyph — the bug icon belongs to Verbose Logging below.
                    Icons.Rounded.Tune,
                    stringResource(R.string.master_switch),
                    stringResource(R.string.master_switch_desc),
                    checked = masterEnabled,
                    onCheckedChange = { on ->
                        if (on) viewModel.setMasterEnabled(true) else showMasterOffDialog = true
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    Icons.Rounded.BugReport,
                    stringResource(R.string.verbose_logging),
                    stringResource(R.string.verbose_logging_desc),
                    checked = verboseLogging,
                    onCheckedChange = viewModel::setVerboseLogging
                )
            }

            // ── 工具 / 订阅源 ──
            SectionTitle(stringResource(R.string.tools_section))
            SettingsCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val aniaBuiltinHidden = aniaSources.none { it.id == "ania_raw" }
                    val picpBuiltinHidden = picpSources.none { it.id == "picp_github" }

                    SourceSection(stringResource(R.string.ania_section_title), aniaSources, activeAniaSource, aniaIconCount, aniaSyncing, aniaSyncStatus,
                        statusIsError = aniaSyncFailed,
                        onSelect = viewModel::setActiveAniaSource, onSync = viewModel::syncAnia,
                        onAddSource = viewModel::startAddSource, onEditSource = viewModel::startEditSource, onDeleteSource = viewModel::removeSource,
                        restoreLabel = if (aniaBuiltinHidden) stringResource(R.string.restore_default_ania) else null,
                        onRestoreDefault = if (aniaBuiltinHidden) viewModel::restoreDefaultAnia else null)
                    Spacer(Modifier.height(8.dp))
                    SourceSection(stringResource(R.string.picp_section_title), picpSources, activePicpSource, picpCount, picpSyncing, picpSyncStatus,
                        statusIsError = picpSyncFailed,
                        onSelect = viewModel::setActivePicpSource, onSync = viewModel::syncPicp,
                        onAddSource = viewModel::startAddSource, onEditSource = viewModel::startEditSource, onDeleteSource = viewModel::removeSource,
                        restoreLabel = if (picpBuiltinHidden) stringResource(R.string.restore_default_picp) else null,
                        onRestoreDefault = if (picpBuiltinHidden) viewModel::restoreDefaultPicp else null)
                }
            }
            RestartSystemUiButton(viewModel)


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
                    // A-3: notifications glyph for the shade-icons row — the
                    // puzzle piece stays unique to the LSPosed status card.
                    Icons.Rounded.Notifications,
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
                SwitchSettingItem(
                    Icons.Rounded.Palette,
                    stringResource(R.string.force_mono_title),
                    stringResource(R.string.force_mono_desc),
                    checked = forceMono,
                    onCheckedChange = { viewModel.setForceMono(it) },
                    supportingHint = if (forceMonoUnknown) stringResource(R.string.force_mono_unknown) else null
                )
            }

            // ── 应用（App 自身的外壳行为） ──
            SectionTitle(stringResource(R.string.app_section))
            SettingsCard {
                // App theme: Material You on by default; off = brand palette.
                val dynamicColor by SettingsViewModel.dynamicColorFlow.collectAsState()
                SwitchSettingItem(
                    Icons.Rounded.ColorLens,
                    stringResource(R.string.dynamic_color_title),
                    stringResource(R.string.dynamic_color_desc),
                    checked = dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // M4：微调选项可见性（默认关=三级策略全自动）
                val showTuning by viewModel.showTuning.collectAsState()
                SwitchSettingItem(
                    Icons.Rounded.Build,
                    stringResource(R.string.show_tuning_title),
                    stringResource(R.string.show_tuning_desc),
                    checked = showTuning,
                    onCheckedChange = { viewModel.setShowTuning(it) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // Per-app locale is a system capability (Android 13+); on 12
                // the row degrades to an honest "system only" note.
                val localeSupported = android.os.Build.VERSION.SDK_INT >= 33
                var showLangSheet by remember { mutableStateOf(false) }
                val currentLocaleTag = if (localeSupported) remember {
                    val lm = context.getSystemService(android.app.LocaleManager::class.java)
                    lm?.applicationLocales?.toLanguageTags().orEmpty()
                } else ""
                val langLabel = when {
                    !localeSupported -> stringResource(R.string.na_label)
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
                        // Q19：应用失败保留 sheet 并反馈，成功才收起
                        onSelect = {
                            if (applyAppLocale(context, it)) showLangSheet = false
                            else viewModel.notifyLocaleApplyFailed()
                        },
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
                        Text(stringResource(R.string.na_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            lineHeight = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.bodyLineTall
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
                                lineHeight = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.bodyLineTall
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
                                    lineHeight = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.bodyLineTall,
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
                    // Q11：容器形状回归 token 表（此前裸 28dp 违反形状纪律）
                    shape = OptShapes.large,
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
                isBuiltin = PreferenceManager.isBuiltinSource(source.id),
                onDismiss = viewModel::dismissEditSource,
                onConfirm = { id, name, url, desc -> viewModel.updateSource(id, name, url, desc) },
                onCopyToCustom = { name, url, desc ->
                    viewModel.dismissEditSource()
                    viewModel.addSource(name, url, desc)
                }
            )
        }
    }
}
