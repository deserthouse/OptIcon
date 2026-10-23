package io.github.deserthouse.opticon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * M3 Expressive token set — the ONLY place hand-written dp/shape constants
 * are allowed to live. Screens reference these; grep for stray
 * RoundedCornerShape(x.dp) outside this file must stay zero.
 *
 * 已知例外（Q11 豁免，调用点带注释）：PreviewCard 模拟系统栏的微装饰
 * （信号条 1dp、时间/图例 9/10sp）为像素画式刻度，不进 token 表——
 * 它们是装饰性绘制值而非布局/字阶语义（OI-05 注释同步，2026-10-24）。
 */
object OptShapes {
    /** Chips, small badges, inline accents. */
    val small = RoundedCornerShape(12.dp)

    /** Standard list cards, source rows, inline panels. */
    val medium = RoundedCornerShape(16.dp)

    /** Page-level strategy cards, hero panels, bottom sheets (top corners). */
    val large = RoundedCornerShape(24.dp)
}

/** Standard vertical rhythm — replaces ad-hoc Spacer heights. */
object OptSpacing {
    val cardGap = 12.dp
    val sectionGap = 24.dp
    val rowInner = 4.dp
    /** 行内 icon↔文本 间距（值同 cardGap，语义独立防误用）。 */
    val inlineGap = 12.dp
}

/**
 * 字阶微调常量（R2-11 后续/sp 散点收编，2026-10-21）——这些是刻意的视觉微调
 * 值（M3 字阶无对应档），收编进本文件统一管理；渲染值与收编前逐处相同，
 * 改值须过双主题视觉轮。
 */
object OptTypeAdjustments {
    /** 按钮内文案压小一档（默认 labelLarge 14 → 13，东亚按钮密度）。 */
    val compactButton = 13.sp
    /** bodySmall 长文本行高（关于卡描述，中文可读性）。 */
    val bodyLineTall = 18.sp
    /** labelSmall 提示行行高（错误行/supporting hint）。 */
    val hintLine = 14.sp
}
