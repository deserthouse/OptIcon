package io.github.deserthouse.opticon.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.engine.MaterialIconRenderer
import io.github.deserthouse.opticon.ui.component.captureVectorBitmap
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * T4-A：Material 图标库选择器（ModalBottomSheet）——按类别分段展示，
 * 点击即在该 Composable 内捕获位图（VectorCapture）并回传 VM 持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialIconPickerSheet(
    onDismiss: () -> Unit,
    onPick: (name: String, rendered: Bitmap) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = OptShapes.large) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.algo_source_material), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(72.dp),
                modifier = Modifier.fillMaxWidth().height(480.dp)
            ) {
                MaterialIconRenderer.categories.forEach { (category, entries) ->
                    item(key = "cat_$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(entries, key = { "$category-${it.first}" }) { (name, icon) ->
                        MaterialIconCell(icon = icon, name = name, onPick = onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialIconCell(icon: ImageVector, name: String, onPick: (String, Bitmap) -> Unit) {
    // 选择时才捕获位图——onClick 非 Composable，改由本 Composable 预捕获
    val captured = captureVectorBitmap(icon)
    Box(
        Modifier.fillMaxWidth().padding(4.dp).clip(OptShapes.small)
            .clickable { onPick(name, captured) }.padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * T4-A：Emoji/文字输入对话框（彩蛋解锁后可达）。
 */
@Composable
fun EmojiInputDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.algo_source_emoji)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.algo_source_emoji_hint)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
