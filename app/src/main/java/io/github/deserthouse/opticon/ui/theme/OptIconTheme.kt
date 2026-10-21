package io.github.deserthouse.opticon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * OptIcon Material 3 Expressive 主题
 *
 * Android 12+：默认 Material You 动态取色（可在设置关闭）。
 * 关闭/不支持时：品牌靛蓝 × 琥珀金静态色板（与 adaptive icon 呼应）。
 */
@Composable
fun OptIconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDarkColors
        else -> BrandLightColors
    }

    // ME-04（M3E 评审）：Expressive 根主题——默认动效走 MotionScheme.expressive，
    // 组件默认行为与局部 Expressive 表达（hero 形变/波浪/弹簧）统一在同一体系内。
    // ME-06：OptShapes 三档注入主题 shapes——未显式传形状的组件也落同一套圆角词汇。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = OptThemeShapes,
        content = content
    )
}

/** ME-06：三档形状词汇进主题（extraSmall/extraLarge 留库默认——不在三档词汇内）。 */
private val OptThemeShapes = Shapes(
    small = OptShapes.small,
    medium = OptShapes.medium,
    large = OptShapes.large
)

// ━━━ 品牌静态色板：靛蓝主色 + 琥珀金强调（v0.9.4 adaptive icon 同源），M3 tonal 对 ━━━
// 关闭动态取色时的观感——不落 M3 默认紫，落自家颜色。
// ME-06：中性色补齐（surface 系/outline 系按靛蓝色调 tinted-neutral），不再继承库默认紫灰。
private val BrandLightColors = lightColorScheme(
    primary = Color(0xFF4355C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF171A2C),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9F),
    onTertiaryContainer = Color(0xFF271900),
    surfaceDim = Color(0xFFDBD9E4),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F2FA),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1EA),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1B1B21),
    // R2-08：background/onBackground 一并覆写，ramp 完整（导航转场叠帧不露库默认近白）
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFBAC3FF),
    scrim = Color(0xFF000000),
)

private val BrandDarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFFF5C64A),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFDF9F),
    surfaceDim = Color(0xFF131318),
    surfaceBright = Color(0xFF3A3942),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1EA),
    // R2-08：background/onBackground 一并覆写（ramp 完整性）
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1EA),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    inverseSurface = Color(0xFFE4E1EA),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4355C0),
    scrim = Color(0xFF000000),
)
