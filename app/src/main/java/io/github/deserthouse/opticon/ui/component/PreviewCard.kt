package io.github.deserthouse.opticon.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
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
import io.github.deserthouse.opticon.ui.session.EditorSessionCore
import io.github.deserthouse.opticon.ui.state.StatusBarMode
import kotlin.math.roundToInt
import io.github.deserthouse.opticon.ui.theme.OptShapes

@Composable
fun PreviewCard(
    bitmap: Bitmap?,
    mode: StatusBarMode,
    preview: EditorSessionCore.PreviewState,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
    // D02：当前结果来源归属标签（空=不显示）
    sourceTag: String = ""
) {
    // A5.2（R2-06）：分态单源派生（此前 isRedrawing/rejected 双布尔散布）
    val isRedrawing = preview is EditorSessionCore.PreviewState.Updating
    val rejected = preview is EditorSessionCore.PreviewState.Rejected
    // D1: the preview simulates the REAL status bar, whose light/dark
    // appearance is a semantic constant, not a decoration. Deriving the bar
    // tones from the theme's surface ramp inverts in dark mode (the "dark
    // bar" rendered lighter than the "light" one) — fixed constant pairs,
    // foreground always paired to its background; the card frame itself
    // still follows the theme surface.
    val darkBar = PreviewSemanticBar.darkBg
    val lightBar = PreviewSemanticBar.lightBg
    // D05（RB-5）：颜色/透明度属 effects——不过冲（MediumBouncy 弹簧会让
    // 底色趋白回弹再稳定，AVD 录屏已观察）；改快速 tween 语义配对
    val bgColor by animateColorAsState(
        targetValue = if (mode == StatusBarMode.DARK) darkBar else lightBar,
        animationSpec = tween(durationMillis = io.github.deserthouse.opticon.ui.theme.MotionTokens.WASH),
        label = "previewBg"
    )
    // Foreground paired to the simulated bar's background, not to the theme.
    // §7.3(R2 风险3)：前景与底色共同进度过渡（此前前景瞬跳、底色过渡，
    // 过渡窗口内短暂低可辨）
    val labelColor by animateColorAsState(
        targetValue = if (mode == StatusBarMode.DARK) PreviewSemanticBar.darkFg else PreviewSemanticBar.lightFg,
        animationSpec = tween(durationMillis = io.github.deserthouse.opticon.ui.theme.MotionTokens.WASH),
        label = "previewFg"
    )

    Surface(
        // ME-06: no page-level horizontal indent inside reusable components —
        // the hosting page owns the 16dp margin (detail Column already pads).
        modifier = modifier
            .fillMaxWidth(),
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
                        // C08（X4）：进度判定先于旧图——旧结果存在时也应可见"正在更新"，
                        // 否则用户把旧参数的图当成当前结果
                        if (isRedrawing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = labelColor.copy(alpha = 0.6f)
                            )
                        } else if (bitmap != null) {
                            Canvas(modifier = Modifier.size(24.dp)) {
                                drawIcon(bitmap, labelColor)
                            }
                        } else if (rejected) {
                            // Q10：守卫拒绝——素材不可用，error 色调与"?"分态
                            Icon(
                                Icons.Rounded.Block,
                                contentDescription = stringResource(io.github.deserthouse.opticon.R.string.preview_rejected_hint),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        } else {
                            // D02：Missing 态问号；OV-01 修复——引导文案移出
                            // 40dp 圆形裁剪槽（槽内会截断长文本），与拒绝态
                            // 文案同放在预览卡下层区域
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

            // OV-01：Missing 态引导文案（放裁剪槽外，长文本不截断）
            if (!isRedrawing && bitmap == null && !rejected) {
                Text(
                    text = stringResource(io.github.deserthouse.opticon.R.string.preview_missing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            // D02 补全：当前结果来源归属行
            if (!isRedrawing && bitmap != null && sourceTag.isNotBlank()) {
                Text(
                    text = stringResource(io.github.deserthouse.opticon.R.string.preview_source_tag, sourceTag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            // Q10：拒绝态文案——不可用素材的诚实引导（位于模拟栏与图例之间）
            if (rejected && !isRedrawing) {
                Text(
                    text = stringResource(io.github.deserthouse.opticon.R.string.preview_rejected_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // Bottom legend — U-4: weight-split columns keep each label on one
            // line at large font scales (the old SpaceBetween let labels wrap and
            // the dot drifted off the first line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                LegendDot(color = Color(0xFFFFFFFF), label = stringResource(io.github.deserthouse.opticon.R.string.preview_legend_icon), modifier = Modifier.weight(1f))
                LegendDot(color = Color(0xCCCCCCCC.toInt()), label = stringResource(io.github.deserthouse.opticon.R.string.preview_legend_edge), modifier = Modifier.weight(1f))
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
                        // Q11 豁免：1dp 信号条微装饰形状，非容器形状，不入 token 表
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

    // OI-10: the compliance silhouette is pure white — without the tint it
    // vanished on light preview backgrounds. Tint it to the semantic
    // foreground color of the current preview mode.
    drawImage(
        image = img,
        dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
        dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
        alpha = 1f,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tintColor)
    )
}

@Composable
private fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
            fontSize = 10.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** D1: semantic constants for the simulated status bar. Foreground is
 *  always paired to its background — these describe how a real status bar
 *  looks in each mode and must not ride the theme's surface ramp. */
/** 预览语义色（深/浅状态栏底-前景对）——微调面板固定预览头同源复用（A11） */
internal object PreviewSemanticBar {
    val darkBg = Color(0xFF141318)
    val darkFg = Color(0xFFF2EFF4)
    val lightBg = Color(0xFFF4F2F7)
    val lightFg = Color(0xFF1C1B20)
}
