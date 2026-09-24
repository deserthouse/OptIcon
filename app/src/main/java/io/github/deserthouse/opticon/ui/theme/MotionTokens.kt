package io.github.deserthouse.opticon.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

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

    /** Medium transitions: expand/collapse panels, shared content. */
    const val NORMAL = 300

    /** Large set-piece transitions (reserved — hero/pager level). */
    const val SLOW = 600

    /** Standard easing for the token durations. */
    val EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
