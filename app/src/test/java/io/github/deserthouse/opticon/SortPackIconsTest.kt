package io.github.deserthouse.opticon

import io.github.deserthouse.opticon.engine.IconPackEngine.PackIconEntry
import io.github.deserthouse.opticon.engine.IconPackEngine.sortPackIcons
import org.junit.Assert.assertEquals
import org.junit.Test

class SortPackIconsTest {

    private val targetPkg = "com.google.android.deskclock"
    private val appLabel = "Clock"

    private fun e(drawable: String, pkg: String? = null) =
        PackIconEntry("ComponentInfo{$pkg/...}", pkg, drawable)

    private fun entries(vararg drawables: String) = drawables.map { e(it) }
    private fun names(l: List<PackIconEntry>) = l.map { it.drawableName }

    @Test
    fun `no query - component match first then hint then alphabetical`() {
        val icons = entries("alarm", "clock_round", "watch") +
            listOf(e("clock", "com.google.android.deskclock"))
        val out = sortPackIcons(icons, targetPkg, appLabel, "")
        assertEquals(listOf("clock", "clock_round", "alarm", "watch"), names(out))
    }

    @Test
    fun `exact match beats prefix beats substring`() {
        // "clockish" is a prefix hit; "clock_monochrome" only contains — but
        // both also start with the query, so alphabetical breaks the tie
        val icons = entries("alarm", "clockish", "clock_monochrome", "clock")
        val out = sortPackIcons(icons, targetPkg, appLabel, "clock")
        assertEquals(listOf("clock", "clock_monochrome", "clockish"), names(out))
    }

    @Test
    fun `fuzzy subsequence fallback - clck finds clock`() {
        val icons = entries("analog", "clock", "circular")
        val out = sortPackIcons(icons, targetPkg, appLabel, "clck")
        assertEquals(listOf("clock"), names(out))
    }

    @Test
    fun `query by package segment keeps app-hinted icons`() {
        // "deskclock" names the app itself; clock-hinted drawables survive,
        // unrelated ones ("desk" matches neither hint) are dropped
        val icons = entries("alarm", "clock_round", "watch", "desk")
        val out = sortPackIcons(icons, targetPkg, appLabel, "deskclock")
        assertEquals(listOf("clock_round"), names(out))
    }

    @Test
    fun `query by app label keeps app-hinted icons`() {
        val icons = entries("alarm", "clock_round", "watch")
        val out = sortPackIcons(icons, targetPkg, appLabel, "Clock")
        assertEquals(listOf("clock_round"), names(out))
    }

    @Test
    fun `case insensitive and trimmed query`() {
        val icons = entries("CLOCK")
        assertEquals(listOf("CLOCK"), names(sortPackIcons(icons, targetPkg, appLabel, "  CLOCK ")))
    }

    @Test
    fun `short query skips fuzzy tier`() {
        // "ce" is a subsequence of "clockery" but not a substring, and too
        // short for the fuzzy tier — nothing else matches
        val icons = entries("clockery", "alarm")
        assertEquals(emptyList<String>(), names(sortPackIcons(icons, targetPkg, appLabel, "ce")))
    }
}
