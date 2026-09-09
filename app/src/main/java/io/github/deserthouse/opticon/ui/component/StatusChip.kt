package io.github.deserthouse.opticon.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.opticon.ui.state.ModificationSource

private val ColorAnia = Color(0xFF536DFE)
private val ColorPicp = Color(0xFF1D9E75)
private val ColorAdaptive = Color(0xFF9C27B0)
private val ColorAlgorithm = Color(0xFFE65100)
private val ColorCustom = Color(0xFFFF9800)
private val ColorUnmodified = Color(0xFF757575)

@Composable
private fun Chip(label: String, color: Color, modifier: Modifier = Modifier) {
    val animatedColor by animateColorAsState(targetValue = color, label = "chipColor")
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = animatedColor.copy(alpha = 0.15f)) {
        Text(text = label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = animatedColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ModificationChip(source: ModificationSource, modifier: Modifier = Modifier) {
    val (label, color) = when (source) {
        ModificationSource.NONE -> "Modified" to ColorUnmodified
        ModificationSource.ICON_LIBRARY -> "Icon lib" to ColorAnia
        ModificationSource.ALGORITHM -> "Algorithm" to ColorAlgorithm
        ModificationSource.CUSTOM -> "Custom" to ColorCustom
    }
    Chip(label = label, color = color, modifier = modifier)
}

@Composable
fun AniaChip(adapted: Boolean, modifier: Modifier = Modifier) {
    Chip(
        label = if (adapted) "ANIA" else "ANIA",
        color = if (adapted) ColorAnia else ColorUnmodified,
        modifier = modifier
    )
}

@Composable
fun PicpChip(adapted: Boolean, modifier: Modifier = Modifier) {
    Chip(
        label = if (adapted) "PICP" else "PICP",
        color = if (adapted) ColorPicp else ColorUnmodified,
        modifier = modifier
    )
}

@Composable
fun AdaptiveChip(hasAdaptive: Boolean, modifier: Modifier = Modifier) {
    Chip(
        label = if (hasAdaptive) "Adaptive" else "Adaptive",
        color = if (hasAdaptive) ColorAdaptive else ColorUnmodified,
        modifier = modifier
    )
}
