package io.github.deserthouse.opticon.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.ui.state.AppUiEntry
import io.github.deserthouse.opticon.ui.state.ModificationSource
import io.github.deserthouse.opticon.ui.theme.MotionTokens
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * ModernAppCard — v0.3.0-alpha redesigned app card
 *
 * Design intent (LSPosed manager / SukiSU Ultra influence):
 *   - Subtle press elevation via animateColorAsState
 *   - 48dp circular icon with gradient placeholder fallback
 *   - Status chip strip with semantic colors (ANIA/PICP/Adaptive/Modified)
 *   - Modification badge (top-right) for modified apps
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernAppCard(
    entry: AppUiEntry,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val containerColor by animateColorAsState(
        targetValue = if (isPressed)
            MaterialTheme.colorScheme.surfaceContainerHighest
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(durationMillis = MotionTokens.FAST),
        label = "cardBg"
    )
    // M3E press: springy scale-down (expressive motion)
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "cardScale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
            }
            .clip(OptShapes.large)
            // ME-08：恢复默认 ripple——indication=null 曾绕过项目「按压波纹
            // 覆盖视觉边界」门禁；缩放动画保留与波纹共存。
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = OptShapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconCircle(entry)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (entry.isUserModified) {
                        Spacer(Modifier.width(6.dp))
                        ModificationBadge(entry.modificationSource)
                    }
                }
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                AdaptiveStatusStrip(entry = entry)
            }
        }
    }
}

@Composable
private fun AppIconCircle(entry: AppUiEntry) {
    // ME-05：包名哈希任意 RGB 与主题无关——统一中性容器，应用原图自带色彩。
    val accent = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center
    ) {
        val iconBitmap = remember(entry.icon) {
            entry.icon?.let { drawable ->
                if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap
                else {
                    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, 48, 48); drawable.draw(android.graphics.Canvas(bmp)); bmp
                }
            }
        }
        if (iconBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = entry.appName,
                modifier = Modifier.size(32.dp).clip(CircleShape)
            )
        } else {
            // U-A2b（ME-05 尾巴）：字母前景随容器配对（onSurfaceVariant）——
            // 深色模式下 Color.White 在 surfaceVariant 上对比度不可控
            Text(
                text = entry.appName.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

private data class BadgeColors(val labelRes: Int, val bg: Color, val fg: Color)

@Composable
private fun ModificationBadge(source: ModificationSource) {
    // ME-05：背景/前景按颜色角色成对——旧实现背景三变而前景一律 onPrimary。
    val c = MaterialTheme.colorScheme
    val badge = when (source) {
        ModificationSource.ICON_LIBRARY -> BadgeColors(io.github.deserthouse.opticon.R.string.badge_source_lib, c.primary, c.onPrimary)
        ModificationSource.ALGORITHM -> BadgeColors(io.github.deserthouse.opticon.R.string.badge_source_algo, c.tertiary, c.onTertiary)
        ModificationSource.CUSTOM -> BadgeColors(io.github.deserthouse.opticon.R.string.badge_source_custom, c.secondary, c.onSecondary)
        ModificationSource.NONE -> return
    }
    Surface(
        shape = OptShapes.small,
        color = badge.bg,
        contentColor = badge.fg
    ) {
        Text(
            text = stringResource(badge.labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdaptiveStatusStrip(entry: AppUiEntry) {
    // U-A2a（ME-08 尾巴）：FlowRow——窄屏/大字号下 pills 换行而非溢出截断
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        if (entry.iconCompliant == false) {
            StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_noncompliant), active = true, alert = true)
        }
        // Info-first: inactive capability pills are NOISE on every card —
        // only show what's true. Inactive "adaptive/ANIA/PICP" pills removed.
        if (entry.aniaAdapted) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_ania), active = true)
        if (entry.picpAdapted) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_picp), active = true)
        if (entry.hasAdaptiveIcon) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_adaptive), active = true)
        // Q7b：硬编码英文 "System" 徽标死分支已删——列表扫描 includeSystemApps=false，
        // isSystemApp 恒 false；若未来收录系统应用须先抽 stringResource。
    }
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean,
    dim: Boolean = false,
    alert: Boolean = false
) {
    val bg by animateColorAsState(
        targetValue = when {
            alert -> MaterialTheme.colorScheme.errorContainer
            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            dim -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        label = "pillBg"
    )
    val fg by animateColorAsState(
        targetValue = when {
            // ME-05：警示分支前景配 onErrorContainer（旧实现无 alert 前景分支）
            alert -> MaterialTheme.colorScheme.onErrorContainer
            active -> MaterialTheme.colorScheme.primary
            dim -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "pillFg"
    )
    Surface(
        shape = OptShapes.small,
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}