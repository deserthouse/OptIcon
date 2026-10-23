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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.util.PreferenceManager

/** 订阅源管理与致谢组件（Q20 拆分自 SettingsScreen.kt）。 */

@Composable
internal fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
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
internal fun EditSourceDialog(
    source: PreferenceManager.SubscriptionSource,
    isBuiltin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
    onCopyToCustom: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(source.name) }
    var url by remember { mutableStateOf(source.url) }
    var desc by remember { mutableStateOf(source.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_source_title)) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            if (isBuiltin) {
                TextButton(onClick = { onCopyToCustom(name, url, desc) }) {
                    Text(stringResource(R.string.copy_to_custom), color = MaterialTheme.colorScheme.primary)
                }
            } else {
                TextButton(
                    onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(source.id, name, url, desc) },
                    enabled = name.isNotBlank() && url.isNotBlank()
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
    onRestoreDefault: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    // O-4 (2026-10-03 audit): destructive delete gets a confirm dialog — same
    // protection level as master-off / restart on this screen.
    var pendingDelete by remember { mutableStateOf<PreferenceManager.SubscriptionSource?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (syncing) {
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
                getSourceDescription(source)?.let { fullDesc ->
                    val lines = fullDesc.split("\n")
                    val linkUrl = lines.lastOrNull()?.takeIf { it.startsWith("http") }
                    // Q14：描述仅一行 URL 时不再把同一 URL 又当描述文本渲染
                    val descText = lines.filterNot { it == linkUrl }.joinToString("\n")
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
