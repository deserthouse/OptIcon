package io.github.deserthouse.opticon.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.domain.SourceFormat
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.util.PreferenceManager

/** 订阅源管理与致谢组件（Q20 拆分自 SettingsScreen.kt）。 */

@Composable
internal fun SourceFormatPicker(
    selected: SourceFormat?,
    enabled: Boolean = true,
    onSelect: (SourceFormat) -> Unit
) {
    val options = listOf(
        Triple(
            SourceFormat.ANIP_BUNDLE,
            stringResource(R.string.source_format_anip),
            stringResource(R.string.source_format_anip_desc)
        ),
        Triple(
            SourceFormat.LEGACY_JSON,
            stringResource(R.string.source_format_legacy),
            stringResource(R.string.source_format_legacy_desc)
        ),
        Triple(
            SourceFormat.PICP_ZIPBALL,
            stringResource(R.string.source_format_picp),
            stringResource(R.string.source_format_picp_desc)
        )
    )
    Column {
        Text(
            stringResource(R.string.source_format_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        options.forEach { (format, label, desc) ->
            val selectedHere = selected == format
            // 修复批（X-11 / X-20）：圆圈中心对齐标题**首行**，不随描述占几行而漂移——
            // 此前整行垂直居中，描述一换行圆圈就落到描述文字那几行上。
            // 行的最小高度 48dp（原为 44dp，低于项目的触摸目标纪律）。
            // 第二轮复审（视觉）：选项之间加留白、大字体下标题不越出本行（见下）。
            Layout(
                content = {
                    RadioButton(selected = selectedHere, onClick = null, enabled = enabled)
                    Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(
                        desc, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selectedHere,
                        role = Role.RadioButton,
                        enabled = enabled,
                        onClick = { onSelect(format) }
                    )
                    // 上下各留一点：描述一换行，下一项的标题就紧贴着上一项描述的末行，
                    // 比那项描述自己的行距还近，各项的归属只能靠圆圈和字重分辨。
                    // 留白在 selectable 之内，波纹与点击区仍是整行。
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) { measurables, constraints ->
                val textStart = 8.dp.roundToPx()
                val descGap = 2.dp.roundToPx()
                val radio = measurables[0].measure(Constraints())
                val textWidth = (constraints.maxWidth - radio.width - textStart).coerceAtLeast(0)
                val title = measurables[1].measure(Constraints(maxWidth = textWidth))
                val description = measurables[2].measure(Constraints(maxWidth = textWidth))
                // 圆圈在自己的触摸区里居中；把标题首行的中心放到同一高度上。
                // 字体放大后标题比圆圈的触摸区还高：这时反过来把圆圈对到标题的中部，
                // 标题不再越出本行的上边界（越出的部分会落进上一行的点击区）。
                val titleTaller = title.height > radio.height
                val textTop = if (titleTaller) 0 else (radio.height - title.height) / 2
                val radioTop = if (titleTaller) (title.height - radio.height) / 2 else 0
                val contentHeight = maxOf(
                    radioTop + radio.height,
                    textTop + title.height + descGap + description.height
                )
                layout(constraints.maxWidth, maxOf(contentHeight, constraints.minHeight)) {
                    radio.placeRelative(0, radioTop)
                    title.placeRelative(radio.width + textStart, textTop)
                    description.placeRelative(radio.width + textStart, textTop + title.height + descGap)
                }
            }
        }
    }
}

@Composable
internal fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String, String, SourceFormat) -> Unit) {
    // 修复批（X-10）：输入用可保存的状态——界面重建（转屏等）不丢；内容区可滚动。
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    // V3 (batch SYNC): format is mandatory — the URL does not determine the
    // payload, so nothing is guessed; save stays disabled until chosen.
    var format by rememberSaveable { mutableStateOf<SourceFormat?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Spacer(Modifier.height(8.dp))
                SourceFormatPicker(selected = format) { format = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { format?.let { onConfirm(name, url, desc, it) } },
                enabled = name.isNotBlank() && url.isNotBlank() && format != null
            ) { Text(stringResource(R.string.add_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_btn)) } }
    )
}

@Composable
internal fun EditSourceDialog(
    source: PreferenceManager.SubscriptionSource,
    isBuiltin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, SourceFormat) -> Unit,
    onCopyToCustom: (String, String, String, SourceFormat) -> Unit
) {
    var name by rememberSaveable(source.id) { mutableStateOf(source.name) }
    var url by rememberSaveable(source.id) { mutableStateOf(source.url) }
    var desc by rememberSaveable(source.id) { mutableStateOf(source.description) }
    // 待确认格式的源（修复批 X-02）不预选任何格式：用户必须选定一个才能保存。
    var format by rememberSaveable(source.id) {
        mutableStateOf<SourceFormat?>(source.format.takeUnless { it == SourceFormat.UNSPECIFIED })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_source_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // OI-14: builtin sources silently dropped edits before — the
                // fields are now read-only and the only write path is
                // "copy to custom", which actually persists.
                if (isBuiltin) {
                    Text(
                        stringResource(R.string.builtin_source_readonly),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.source_name_label)) },
                    singleLine = true,
                    readOnly = isBuiltin,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.source_url_label)) },
                    singleLine = true,
                    readOnly = isBuiltin,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.source_description_label)) },
                    maxLines = 3,
                    readOnly = isBuiltin,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // V3: format stays editable on custom sources (readonly for builtins).
                SourceFormatPicker(
                    selected = format,
                    enabled = !isBuiltin,
                    onSelect = { format = it }
                )
            }
        },
        confirmButton = {
            if (isBuiltin) {
                TextButton(onClick = {
                    format?.let { onCopyToCustom(name, url, desc, it) }
                }) {
                    Text(stringResource(R.string.copy_to_custom), color = MaterialTheme.colorScheme.primary)
                }
            } else {
                TextButton(
                    onClick = {
                        format?.let {
                            if (name.isNotBlank() && url.isNotBlank()) onConfirm(source.id, name, url, desc, it)
                        }
                    },
                    enabled = name.isNotBlank() && url.isNotBlank() && format != null
                ) { Text(stringResource(R.string.save_btn)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_btn)) } }
    )
}

@Composable
internal fun CreditEntry(project: String, description: String, url: String) {
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
internal fun getSourceDisplayName(source: PreferenceManager.SubscriptionSource): String {
    return when (source.id) {
        "ania_raw" -> stringResource(R.string.source_ania_raw)
        "picp_github" -> stringResource(R.string.source_picp_github)
        else -> source.name
    }
}

@Composable
internal fun getSourceDescription(source: PreferenceManager.SubscriptionSource): String? {
    return when (source.id) {
        "ania_raw" -> stringResource(R.string.source_ania_raw_desc)
        "picp_github" -> stringResource(R.string.source_picp_github_desc)
        else -> source.description.takeIf { it.isNotBlank() }
    }
}

@Composable
internal fun SourceSection(
    title: String,
    sources: List<PreferenceManager.SubscriptionSource>,
    activeId: String,
    iconCount: Int,
    syncing: Boolean,
    status: String?,
    statusIsError: Boolean = false,
    onSelect: (String) -> Unit,
    onSync: () -> Unit,
    onAddSource: () -> Unit = {},
    onEditSource: (PreferenceManager.SubscriptionSource) -> Unit = {},
    onDeleteSource: (String) -> Unit = {},
    restoreLabel: String? = null,
    onRestoreDefault: (() -> Unit)? = null,
    onCancelSync: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    // O-4 (2026-10-03 audit): destructive delete gets a confirm dialog — same
    // protection level as master-off / restart on this screen.
    var pendingDelete by remember { mutableStateOf<PreferenceManager.SubscriptionSource?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (syncing) {
                // V4 (batch SYNC): cancel affordance during a long sync (PICP
                // downloads hundreds of MB). Same 48dp footprint discipline.
                if (onCancelSync != null) {
                    IconButton(onClick = onCancelSync) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.cd_cancel_sync), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                // Reserve the same 48dp footprint as the sync IconButton — a
                // bare 20dp spinner would shrink the header row mid-sync.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else IconButton(onClick = onSync) { Icon(Icons.Rounded.Sync, stringResource(R.string.cd_sync), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        status?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // D4: clickable row carries its own 16dp indent — ripple spans
                // the full card width.
                // ME-10: row reads as name + selection + overflow; edit/delete
                // moved into the menu (delete keeps its O-4 confirm dialog).
                var rowMenuOpen by remember(source.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth()
                    .selectable(selected = activeId == source.id, role = Role.RadioButton) { onSelect(source.id) }
                    .padding(vertical = 6.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = activeId == source.id, onClick = null, modifier = Modifier.padding(end = 4.dp))
                    Text(getSourceDisplayName(source), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box {
                        IconButton(onClick = { rowMenuOpen = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options), Modifier.size(18.dp)) }
                        DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_edit_source)) },
                                onClick = { rowMenuOpen = false; onEditSource(source) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_delete_source), color = MaterialTheme.colorScheme.error) },
                                onClick = { rowMenuOpen = false; pendingDelete = source }
                            )
                        }
                    }
                }
                // Description + link (aligned under the row text: 16dp indent
                // + radio 48dp touch target)
                val description = getSourceDescription(source)
                // 待确认格式的源（修复批 X-02）：两个列表都显示，带提示等用户选定格式
                val needsFormat = source.format == SourceFormat.UNSPECIFIED
                if (needsFormat || description != null) {
                    val fullDesc = description.orEmpty()
                    val lines = fullDesc.split("\n")
                    val linkUrl = lines.lastOrNull()?.takeIf { it.startsWith("http") }
                    // Q14：描述仅一行 URL 时不再把同一 URL 又当描述文本渲染
                    val descText = lines.filterNot { it == linkUrl }.joinToString("\n")
                    Column(Modifier.padding(start = 68.dp, end = 16.dp)) {
                        if (needsFormat) {
                            Text(
                                stringResource(R.string.source_format_unspecified_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (descText.isNotBlank()) Text(descText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (linkUrl != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(OptShapes.small)
                                .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                                .padding(vertical = 6.dp)) {
                                Text(linkUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Launch, stringResource(R.string.cd_open_link), Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
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
    pendingDelete?.let { source ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_source_title)) },
            text = { Text(stringResource(R.string.delete_source_body, getSourceDisplayName(source))) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteSource(source.id)
                }) { Text(stringResource(R.string.delete_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
