package io.github.deserthouse.opticon.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * SelectionRow — the single single-choice row idiom (M3E alignment).
 *
 * Replaces StrategyRadio / AssetSubRadio / RadioSheet's inline Row.
 * Whole-row hit target, radio + label (+ optional description).
 */
@Composable
fun SelectionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    // E6: disabled rows read as a "gray wall" when the whole column is just
    // alpha-squashed — keep the text colors explicit instead: onSurface at
    // 38% alpha stays legible and clearly inert, variant×0.4 was not.
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val descColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.selectable(selected = selected, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = labelColor)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = descColor)
            }
        }
    }
}
