package io.github.deserthouse.opticon.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments
import io.github.deserthouse.opticon.ui.viewmodel.RestartResult
import io.github.deserthouse.opticon.ui.viewmodel.SettingsViewModel

/** 运行状态卡与 SystemUI 重启（Q20 拆分自 SettingsScreen.kt）。
 *  重启状态由 SettingsViewModel 持有——旋转/重建后 Running 与结果不再丢失。 */

internal enum class StatusIconKind { LSPOSED, ROOT }

@Composable
internal fun StatusRow(
    title: String,
    statusText: String,
    active: Boolean?,
    checking: Boolean,
    onRecheck: () -> Unit,
    hint: String? = null,
    // E7: root-unavailable is expected on non-rooted devices — a neutral
    // note, not an error-red alarm (LSPosed-inactive keeps the error color).
    inactiveIsNeutral: Boolean = false,
    // R2-10：显式语义参数取代 title.contains("LSPosed") 子串选图标（翻译耦合）
    iconKind: StatusIconKind = StatusIconKind.ROOT
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (iconKind == StatusIconKind.LSPOSED) Icons.Rounded.Extension else Icons.Rounded.Key,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), lineHeight = OptTypeAdjustments.hintLine)
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
internal fun RuntimeStatusCard(
    lsposedActive: Boolean?,
    rootAvailable: Boolean?,
    rechecking: Boolean,
    onRecheck: () -> Unit
) {
    SettingsCard {
        StatusRow(
            iconKind = StatusIconKind.LSPOSED,
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
        // F4 (2026-10-07 visual audit): inset to 16dp matching every other
        // card on this screen — the full-bleed divider was the lone outlier.
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        StatusRow(
            iconKind = StatusIconKind.ROOT,
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

@Composable
internal fun RestartSystemUiButton(viewModel: SettingsViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    val result by viewModel.restartState.collectAsState()
    val running = result is RestartResult.Running

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
        AnimatedVisibility(visible = result is RestartResult.Ok) {
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
                    viewModel.restartSystemUi()
                }) { Text(stringResource(R.string.restart_confirm_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun RestartFeedbackRow(text: String, isError: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, lineHeight = OptTypeAdjustments.hintLine)
    }
}
