package io.github.deserthouse.opticon.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.engine.RedrawParams

/**
 * ParameterPanel — 算法微调实时参数面板
 *
 * 提供可视化实时调参入口：
 * 缩放比例 / 阈值(10~150) / X 偏移 / Y 偏移 / 圆角半径
 *
 * 阈值滑条仅在方案 5 (SELF_FILTER) 可见时展示。
 * 「智能全自动」模式下阈值初始化为公式阈值，默认 30。
 */
@Composable
fun ParameterPanel(
    params: RedrawParams,
    visible: Boolean,
    showThreshold: Boolean,
    onScaleChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                fadeOut(animationSpec = tween(180)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.param_panel_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            ParamSlider(
                label = stringResource(R.string.param_scale),
                value = params.scale,
                valueRange = RedrawParams.SCALE_MIN..RedrawParams.SCALE_MAX,
                displayValue = "${(params.scale * 100).toInt()}%",
                onValueChange = onScaleChange
            )

            ParamSlider(
                label = stringResource(R.string.param_offset_x),
                value = params.offsetX,
                valueRange = -RedrawParams.OFFSET_MAX..RedrawParams.OFFSET_MAX,
                displayValue = "${params.offsetX.toInt()}px",
                onValueChange = onOffsetXChange
            )

            ParamSlider(
                label = stringResource(R.string.param_offset_y),
                value = params.offsetY,
                valueRange = -RedrawParams.OFFSET_MAX..RedrawParams.OFFSET_MAX,
                displayValue = "${params.offsetY.toInt()}px",
                onValueChange = onOffsetYChange
            )

            if (showThreshold) {
                ParamSlider(
                    label = stringResource(R.string.param_threshold),
                    value = params.threshold.toFloat(),
                    valueRange = RedrawParams.THRESHOLD_MIN.toFloat()..RedrawParams.THRESHOLD_MAX.toFloat(),
                    displayValue = params.threshold.toString(),
                    onValueChange = onThresholdChange
                )
            }

            ParamSlider(
                label = stringResource(R.string.param_radius),
                value = params.radius,
                valueRange = 0f..RedrawParams.RADIUS_MAX,
                displayValue = "${params.radius.toInt()}dp",
                onValueChange = onRadiusChange
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(stringResource(R.string.param_reset), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    dimmed: Boolean = false
) {
    val dimAlpha = if (dimmed) 0.38f else 1f
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (dimmed) 0.5f else 1f),
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = dimAlpha),
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = dimAlpha),
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = dimAlpha)
            )
        )
    }
}
