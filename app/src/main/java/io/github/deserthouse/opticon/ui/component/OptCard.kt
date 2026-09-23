package io.github.deserthouse.opticon.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * OptCard — the single card language for all pages (M3E alignment).
 *
 * Replaces SettingsCard (Settings) and StrategyCard (AppDetail); ModernAppCard
 * keeps its own press-scale interaction and stays list-specific.
 *
 * @param selected when true, washes the card with primaryContainer (strategy
 * selection semantics). Pass onClick to make the whole card a selection target.
 */
@Composable
fun OptCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = OptShapes.large
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container)
        ) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container)
        ) { content() }
    }
}

/** Card with standard outer padding (vertical 6dp), the common settings idiom. */
@Composable
fun OptCardPadded(
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = OptCard(modifier = Modifier.padding(vertical = 6.dp), selected = selected, enabled = enabled, onClick = onClick, content = content)
