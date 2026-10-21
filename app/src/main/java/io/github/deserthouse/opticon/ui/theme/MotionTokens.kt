package io.github.deserthouse.opticon.ui.theme

/**
 * MotionTokens (E2) — the named motion vocabulary. One source of truth for
 * durations/easing instead of scattered tween(120/180/220/280) magic numbers.
 *
 * Springs stay inline: M3E expressive motion is spring-first, and each
 * spring's stiffness/damping is tuned to its spot (press scale, preview wash)
 * — forcing them through duration tokens would flatten the feel.
 *
 * MotionScheme 调研结论（2026-10-21，R2/A6-① 评估后维持现状）：项目根主题
 * MaterialExpressiveTheme 已默认给全部 M3 组件套 MotionScheme.expressive
 * （组件层受益已生效）；自定义动效点维持本 token 词表——D05 的 spatial/
 * effects 语义配对（颜色/透明度无过冲）与 motionScheme 的 spatial/effects
 * 二分一致，且替换为 fastEffectsSpec() 会改变已验证的 200ms wash 手感。
 * 重启条件：M3 motionScheme API 稳定转正且需要跨组件统一自定义动效时。
 */
object MotionTokens {
    /** Small state changes: fades, color washes, chip transitions. */
    const val FAST = 150

    /** Q11+R2-07：颜色/透明度 wash 档（预览底色）——时长介于 FAST 与 NORMAL
     *  之间；按用途命名而非速度序（FASTEST 命名曾与 FAST 构成名实反转）。 */
    const val WASH = 200

    /** Medium transitions: expand/collapse panels, shared content. */
    const val NORMAL = 300

    /** Large set-piece transitions (reserved — hero/pager level). */
    const val SLOW = 600
}
