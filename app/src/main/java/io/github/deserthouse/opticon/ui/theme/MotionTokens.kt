package io.github.deserthouse.opticon.ui.theme

/**
 * MotionTokens (E2) — the named motion vocabulary. One source of truth for
 * durations/easing instead of scattered tween(120/180/220/280) magic numbers.
 *
 * Springs stay inline: M3E expressive motion is spring-first, and each
 * spring's stiffness/damping is tuned to its spot (press scale, preview wash)
 * — forcing them through duration tokens would flatten the feel.
 */
object MotionTokens {
    /** Small state changes: fades, color washes, chip transitions. */
    const val FAST = 150

    /** Q11：effects 快速过渡档——比 FAST 略长的颜色/透明度 wash（预览底色），
     *  又不及 NORMAL 的面板级时长。 */
    const val FASTEST = 200

    /** Medium transitions: expand/collapse panels, shared content. */
    const val NORMAL = 300

    /** Large set-piece transitions (reserved — hero/pager level). */
    const val SLOW = 600
}
