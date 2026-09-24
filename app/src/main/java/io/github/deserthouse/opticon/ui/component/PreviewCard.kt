package io.github.deserthouse.opticon.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.opticon.ui.state.StatusBarMode
import kotlin.math.roundToInt
import io.github.deserthouse.opticon.ui.theme.OptShapes

@Composable
fun PreviewCard(
    bitmap: Bitmap?,
    mode: StatusBarMode,
    isRedrawing: Boolean,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    // D1: the preview simulates the REAL status bar, whose light/dark
    // appearance is a semantic constant, not a decoration. Deriving the bar
    // tones from the theme's surface ramp inverts in dark mode (the "dark
    // bar" rendered lighter than the "light" one) — fixed constant pairs,
    // foreground always paired to its background; the card frame itself
    // still follows the theme surface.
    val darkBar = PreviewSemanticBar.darkBg
    val lightBar = PreviewSemanticBar.lightBg
    val bgColor by animateColorAsState(
        targetValue = if (mode == StatusBarMode.DARK) darkBar else lightBar,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "previewBg"
    )
    // Foreground paired to the simulated bar's background, not to the theme.
    val labelColor = if (mode == StatusBarMode.DARK) PreviewSemanticBar.darkFg else PreviewSemanticBar.lightFg

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = OptShapes.medium,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(io.github.deserthouse.opticon.R.string.preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(mode.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onToggleMode
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Brightness6,
                            contentDescription = stringResource(io.github.deserthouse.opticon.R.string.toggle_preview_mode),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Status bar simulation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(88.dp)
                    .clip(OptShapes.medium)
                    .background(bgColor)
            ) {
                // Status bar decorations (time / signal / battery)
                StatusBarDecorations(labelColor)

                // Notification icon 22dp slot — centered, weight ensures no overlap with status text
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                color = labelColor.copy(alpha = 0.15f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Canvas(modifier = Modifier.size(24.dp)) {
                                drawIcon(bitmap, labelColor)
                            }
                        } else if (isRedrawing) {
                            Text(
                                text = "...",
                                color = labelColor.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "?",
                                color = labelColor.copy(alpha = 0.3f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            // Bottom legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendDot(color = Color(0xFFFFFFFF), label = stringResource(io.github.deserthouse.opticon.R.string.preview_legend_icon))
                LegendDot(color = Color(0xCCCCCCCC.toInt()), label = stringResource(io.github.deserthouse.opticon.R.string.preview_legend_edge))
            }
        }
    }
}

@Composable
private fun StatusBarDecorations(color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "12:00",
            color = color.copy(alpha = 0.7f),
            fontSize = 9.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height((3 + i * 2).dp)
                        .padding(end = 1.dp)
                        .background(color.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "100%",
                color = color.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }
}

private fun DrawScope.drawIcon(bitmap: Bitmap, tintColor: Color) {
    val img = bitmap.asImageBitmap()
    val canvasW = size.width
    val canvasH = size.height

    val scale = minOf(canvasW / img.width, canvasH / img.height)
    val drawW = img.width * scale
    val drawH = img.height * scale
    val offsetX = (canvasW - drawW) / 2f
    val offsetY = (canvasH - drawH) / 2f

    drawImage(
        image = img,
        dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
        dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
        alpha = 1f
    )
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // E4: the white dot sits on a white card — a 1dp variant outline
        // keeps both legend swatches visible in light theme.
        Surface(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape
                ),
            color = color
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

/** D1: semantic constants for the simulated status bar. Foreground is
 *  always paired to its background — these describe how a real status bar
 *  looks in each mode and must not ride the theme's surface ramp. */
private object PreviewSemanticBar {
    val darkBg = Color(0xFF141318)
    val darkFg = Color(0xFFF2EFF4)
    val lightBg = Color(0xFFF4F2F7)
    val lightFg = Color(0xFF1C1B20)
}
