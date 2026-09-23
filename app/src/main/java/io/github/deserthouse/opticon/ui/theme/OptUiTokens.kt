package io.github.deserthouse.opticon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.deserthouse.opticon.ui.theme.OptShapes

/**
 * M3 Expressive token set — the ONLY place hand-written dp/shape constants
 * are allowed to live. Screens reference these; grep for stray
 * RoundedCornerShape(x.dp) outside this file must stay zero.
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
}
