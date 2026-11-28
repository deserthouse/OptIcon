package io.github.deserthouse.opticon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则版本戳变化之后的三步（批 MT M2）：位图缓存作废 → 按清单删本地缓存 → 叫醒循环。
 * 审查员 D 把「版本戳变化时不作废图标缓存」「规则库变化不叫醒循环」改坏时没有测试变红。
 */
class RulesStampReactionTest {

    private val calls = ArrayList<String>()
    private val warns = ArrayList<String>()
    private var failDrop = false

    private fun reaction() = RulesStampReaction(
        dropIconCache = { calls += "evict" },
        dropLocalPackages = { revoked ->
            calls += "drop:${revoked.joinToString(",")}"
            if (failDrop) throw IllegalStateException("thread could not start")
        },
        wakeSyncLoop = { calls += "wake" },
        warn = warns::add
    )

    @Test
    fun `a stamp change evicts the icon cache, drops the revoked packages and wakes the loop - in that order`() {
        reaction().onStampChanged(listOf("com.a", "com.b"))

        assertEquals(listOf("evict", "drop:com.a,com.b", "wake"), calls)
    }

    @Test
    fun `an empty revoked list still evicts the cache and wakes the loop`() {
        reaction().onStampChanged(emptyList())

        assertEquals(listOf("evict", "wake"), calls)
    }

    @Test
    fun `a failing drop does not stop the loop from being woken`() {
        failDrop = true

        reaction().onStampChanged(listOf("com.a"))

        assertEquals(listOf("evict", "drop:com.a", "wake"), calls)
        assertEquals(1, warns.size)
        assertTrue(warns.single().startsWith("drop revoked packages failed"))
    }
}
