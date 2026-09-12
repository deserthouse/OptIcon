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
        animationSpec = tween(durationMillis = 120),
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
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(24.dp),
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
                        fontWeight = FontWeight.SemiBold,
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
    val accent = run {
        val h = entry.packageName.hashCode()
        Color(
            red = ((h shr 16) and 0xFF).coerceIn(60, 220),
            green = ((h shr 8) and 0xFF).coerceIn(60, 220),
            blue = (h and 0xFF).coerceIn(60, 220)
        )
    }
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
            Text(
                text = entry.appName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun ModificationBadge(source: ModificationSource) {
    val (labelRes, color) = when (source) {
        ModificationSource.ICON_LIBRARY -> io.github.deserthouse.opticon.R.string.badge_source_lib to MaterialTheme.colorScheme.primary
        ModificationSource.ALGORITHM -> io.github.deserthouse.opticon.R.string.badge_source_algo to MaterialTheme.colorScheme.tertiary
        ModificationSource.CUSTOM -> io.github.deserthouse.opticon.R.string.badge_source_custom to MaterialTheme.colorScheme.secondary
        ModificationSource.NONE -> return
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AdaptiveStatusStrip(entry: AppUiEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (entry.iconCompliant == false) {
            StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_noncompliant), active = true, alert = true)
        }
        // Info-first: inactive capability pills are NOISE on every card —
        // only show what's true. Inactive "adaptive/ANIA/PICP" pills removed.
        if (entry.aniaAdapted) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_ania), active = true)
        if (entry.picpAdapted) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_picp), active = true)
        if (entry.hasAdaptiveIcon) StatusPill(text = stringResource(io.github.deserthouse.opticon.R.string.pill_adaptive), active = true)
        if (entry.isSystemApp) StatusPill(text = "System", active = false, dim = true)
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
            active -> MaterialTheme.colorScheme.primary
            dim -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "pillFg"
    )
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}