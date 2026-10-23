package io.github.deserthouse.opticon.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.R
import io.github.deserthouse.opticon.ui.theme.OptShapes
import io.github.deserthouse.opticon.util.TraceLogger

/** Settings 页共享小组件（Q20 拆分自 SettingsScreen.kt）——卡片/行/sheet 原语，
 *  AppDetailScreen 同包复用 SettingItem/RadioSheet/RadioOption。 */

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = OptShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        // D4 (触摸几何): clip FIRST, no padding at the content layer —
        // descendant rows sit edge-to-edge in the clipped column and carry
        // their own 16dp indent INSIDE their clickable region, so the ripple
        // and hit area span the full card width (the old padding-then-clip
        // order made every ripple stop 16dp short of the card outline).
        Column(Modifier.clip(OptShapes.large), content = content)
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
internal fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    // D2: supplementary hint belongs in the supporting-text slot below the
    // subtitle — a trailing-slot hint fights the Switch/label for width and
    // breaks the row (mid-word wrap at large font scales). Placed BEFORE the
    // trailing action lambda: a new function-type-adjacent param after the
    // lambda slot steals the call site's trailing lambda (全局规范七.5).
    supportingHint: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    // D4: clickable BEFORE padding — ripple and hit area cover the full row
    // width; the 16dp indent is inside the clickable region.
    Row(Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (supportingHint != null) {
                Text(supportingHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, lineHeight = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.hintLine)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            // Align with the title line, not the row center: a centered label
            // lands mid-way down a multi-line subtitle and reads as part of it.
            Box(Modifier.align(Alignment.Top).padding(top = 2.dp)) { action() }
        }
    }
}

/** ME-10/11: a pure switch row is ONE semantics surface — the row carries
 *  toggleable(value, Role.Switch); the trailing Switch is display-only
 *  (onCheckedChange=null, its localized on/off state description applies to
 *  the whole row). Replaces the old SettingItem+Switch pairing where row
 *  clickable and switch each kept a separate behavior surface. */
@Composable
internal fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingHint: String? = null
) {
    Row(Modifier.fillMaxWidth()
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (supportingHint != null) {
                Text(supportingHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, lineHeight = io.github.deserthouse.opticon.ui.theme.OptTypeAdjustments.hintLine)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.align(Alignment.Top).padding(top = 2.dp)) {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Apply per-app locale (Android 13+). Empty tag = follow system.
 *  The system persists it and recreates the activity; localization
 *  applies to every locale-qualified resource automatically.
 *  Q19：返回成败——失败时调用方反馈用户并可重试，不再只写日志静默。 */
internal fun applyAppLocale(context: android.content.Context, tag: String): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 33) return false
    val lm = context.getSystemService(android.app.LocaleManager::class.java) ?: return false
    return try {
        if (tag.isEmpty()) lm.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
        else lm.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
        TraceLogger.i("OptIcon/Settings", "app locale set: ${tag.ifEmpty { "system" }}")
        true
    } catch (e: Exception) {
        TraceLogger.w("OptIcon/Settings", "setApplicationLocales failed: ${e.message}")
        false
    }
}

data class RadioOption<T>(val value: T, val label: String, val desc: String?)

/** M3 bottom-sheet single-choice picker: the whole row is the hit target,
 *  selected option carries the primary color + medium weight, options can
 *  carry a supporting description.
 *  ME-11: options scroll when tall (large font scales), each row is ONE
 *  selectable surface with Role.RadioButton; the radio is display-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> RadioSheet(
    title: String,
    options: List<RadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = OptShapes.large) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
            options.forEach { opt ->
                val selectedHere = opt.value == selected
                Row(
                    Modifier.fillMaxWidth()
                        .clip(OptShapes.medium)
                        .selectable(selected = selectedHere, role = Role.RadioButton) { onSelect(opt.value) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedHere, onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selectedHere) FontWeight.Medium else null,
                            color = if (selectedHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (opt.desc != null) {
                            Text(opt.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
