package io.github.deserthouse.opticon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RulesStampTracker 单测（修复批 X-04：规则版本戳 → hook 图标缓存作废）。 */
class RulesStampTrackerTest {

    @Test
    fun `a changed stamp fires exactly once`() {
        var fired = 0
        val tracker = RulesStampTracker { fired++ }
        tracker.seed("100")

        assertTrue(tracker.observe("200"))
        assertEquals(1, fired)
        assertEquals("200", tracker.last)

        assertFalse("same stamp must not fire again", tracker.observe("200"))
        assertEquals(1, fired)
    }

    @Test
    fun `an unreadable stamp never fires`() {
        var fired = 0
        val tracker = RulesStampTracker { fired++ }
        tracker.seed("100")

        // 文件被删 / 目录暂时读不到
        assertFalse(tracker.observe(null))
        assertEquals(0, fired)
        assertEquals("100", tracker.last)

        // 目录恢复可读，戳没变 → 也不触发
        assertFalse(tracker.observe("100"))
        assertEquals(0, fired)
    }

    @Test
    fun `becoming readable after a locked boot counts as a change`() {
        // 带锁屏密码的设备：开机时（首次解锁前）共享目录读不到
        var fired = 0
        val tracker = RulesStampTracker { fired++ }
        tracker.seed(null)

        assertTrue(tracker.observe("100"))
        assertEquals(1, fired)
    }

    @Test
    fun `no stamp at all never fires`() {
        var fired = 0
        val tracker = RulesStampTracker { fired++ }
        tracker.seed(null)

        assertFalse(tracker.observe(null))
        assertEquals(0, fired)
    }
}
