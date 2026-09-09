package io.github.deserthouse.opticon.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.opticon.ui.state.AppUiEntry

/**
 * AppCard — App list card (performance-optimized)
 *
 * No spring animations in lists to avoid frame drops.
 * Combined click + long-click on a single touch target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    entry: AppUiEntry,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else
                    Modifier.combinedClickable(onClick = onClick)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(entry)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.appName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(entry.packageName, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AniaChip(adapted = entry.aniaAdapted)
                    PicpChip(adapted = entry.picpAdapted)
                    AdaptiveChip(hasAdaptive = entry.hasAdaptiveIcon)
                    if (entry.isUserModified) ModificationChip(source = entry.modificationSource)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(entry: AppUiEntry) {
    val iconBitmap = remember(entry.icon) {
        entry.icon?.let { drawable ->
            if (drawable is BitmapDrawable) drawable.bitmap
            else {
                val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 48, 48); drawable.draw(Canvas(bmp)); bmp
            }
        }
    }
    if (iconBitmap != null) {
        Image(iconBitmap.asImageBitmap(), entry.appName, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF000000L or (entry.packageName.hashCode() and 0xFFFFFF).toLong())), contentAlignment = Alignment.Center) {
            Text(entry.appName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}
