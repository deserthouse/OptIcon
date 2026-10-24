package io.github.deserthouse.opticon.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.engine.IconLibEngine
import io.github.deserthouse.opticon.engine.PicpEngine
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.FlatSource
import io.github.deserthouse.opticon.ui.state.selectedFlatSource
import io.github.deserthouse.opticon.ui.state.AppDetailState
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.theme.OptShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扁平来源选择 Sheet（T-B1 原型 A 版落地）——「更换图标来源」单步完成：
 * 直达适配（规则库/自适应拆解/PICP/图标包）+ 重绘输入（自身图标/抓取原图/
 * 上传/Material/Emoji）。行可见性=可用性（不可用即不出现），徽章只标注
 * 需要额外步骤的状态；取消不落父稿由外层 showSourceSheet 布尔保证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    state: AppDetailState,
    onDismiss: () -> Unit,
    onPick: (FlatSource) -> Unit
) {
    val ctx = LocalContext.current
    // Q1（K11）同纪律：覆盖检查是磁盘/索引读，produceState 异步取徽章
    val rulesCovered by produceState(false, state.packageName) {
        value = withContext(Dispatchers.IO) { IconLibEngine.hasIcon(state.packageName) }
    }
    val picpCached by produceState(false, state.packageName) {
        value = withContext(Dispatchers.IO) { PicpEngine.hasIcon(state.packageName, ctx) }
    }
    val selectedSource = selectedFlatSource(
        state.strategy, state.assetSubStrategy, state.algoSource, state.capturedOriginalInUse
    )
    ModalBottomSheet(onDismissRequest = onDismiss, shape = OptShapes.large) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.source_change_title),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.source_change_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.size(8.dp))

            GroupLabel(stringResource(R.string.source_group_direct))
            SourceRow(
                icon = Icons.Rounded.Public,
                title = stringResource(R.string.strategy_fankes_label_short),
                desc = stringResource(R.string.strategy_fankes_desc),
                badge = if (rulesCovered) stringResource(R.string.badge_covered) else null,
                selected = selectedSource == FlatSource.RulesLib,
                onClick = { onPick(FlatSource.RulesLib) }
            )
            // 可用性即可见性：非自适应图标不给死选项（与选择器草稿校验同源语义）
            if (state.isAdaptiveIcon == true) {
                SourceRow(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.asset_sub_adaptive),
                    desc = stringResource(R.string.source_adaptive_desc),
                    badge = null,
                    selected = selectedSource == FlatSource.AdaptiveDecompose,
                    onClick = { onPick(FlatSource.AdaptiveDecompose) }
                )
            }
            SourceRow(
                icon = Icons.Rounded.CloudDownload,
                title = "PICP",
                desc = stringResource(R.string.source_picp_desc),
                badge = if (picpCached) stringResource(R.string.badge_cached)
                        else stringResource(R.string.badge_need_download),
                selected = selectedSource == FlatSource.Picp,
                onClick = { onPick(FlatSource.Picp) }
            )
            SourceRow(
                icon = Icons.Rounded.Apps,
                title = stringResource(R.string.source_iconpack_short),
                desc = stringResource(R.string.source_iconpack_desc),
                badge = null,
                selected = selectedSource == FlatSource.IconPack,
                onClick = { onPick(FlatSource.IconPack) }
            )

            GroupLabel(stringResource(R.string.source_group_redraw))
            SourceRow(
                icon = Icons.Rounded.Smartphone,
                title = stringResource(R.string.algo_source_self),
                desc = stringResource(R.string.algo_source_self_desc),
                badge = null,
                selected = selectedSource == FlatSource.SelfIcon,
                onClick = { onPick(FlatSource.SelfIcon) }
            )
            if (state.hasOriginalCaptured) {
                SourceRow(
                    icon = Icons.Rounded.ImageSearch,
                    title = stringResource(R.string.source_captured_short),
                    desc = stringResource(R.string.source_captured_desc),
                    badge = stringResource(R.string.badge_captured),
                    selected = selectedSource == FlatSource.CapturedOriginal,
                    onClick = { onPick(FlatSource.CapturedOriginal) }
                )
            }
            SourceRow(
                icon = Icons.Rounded.Upload,
                title = stringResource(R.string.algo_source_upload),
                desc = stringResource(R.string.algo_source_upload_desc),
                badge = null,
                selected = selectedSource == FlatSource.Upload,
                onClick = { onPick(FlatSource.Upload) }
            )
            SourceRow(
                icon = Icons.Rounded.Category,
                title = stringResource(R.string.algo_source_material),
                desc = stringResource(R.string.algo_source_material_desc),
                badge = null,
                selected = selectedSource == FlatSource.MaterialLib,
                onClick = { onPick(FlatSource.MaterialLib) }
            )
            // 「已解锁」徽章不做（T-B1 拍板）：行可见本身即解锁信号
            if (state.emojiUnlocked) {
                SourceRow(
                    icon = Icons.Rounded.EmojiEmotions,
                    title = stringResource(R.string.algo_source_emoji),
                    desc = stringResource(R.string.algo_source_emoji_desc),
                    badge = null,
                    selected = selectedSource == FlatSource.EmojiText,
                    onClick = { onPick(FlatSource.EmojiText) }
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** 单一可点面（触摸区四问）：clickable 先行、高度 56dp 起、尾随对勾
 *  不抢宽度；选中态=主色名+容器底图标圈，与原型一致。 */
@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    desc: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(OptShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null, Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.clip(OptShapes.small)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
