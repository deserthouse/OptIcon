package io.github.deserthouse.opticon.engine

/**
 * Bake source taxonomy (#20 架构收口): compile-time-exhaustive replacement for
 * the old free-form hitLevel strings. UI maps these via
 * `ui.state.hitLevelRes(source)`; the old string protocol could silently
 * fall through to "other" whenever a new literal missed the startsWith table.
 * [BakeResult.detail] keeps the per-site payload (pack label, drawable name,
 * error text) for logs — display stays categorical.
 */
enum class HitSource {
    PICP, PICP_FAILED, ICONLIB, NETWORK_FAIL, DISABLED, SELFFILTER,
    ADAPTIVE, ICONPACK, ICONPACK_MANUAL, REMOTE, MANUAL, AUTO_MISS, OTHER
}
