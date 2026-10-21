package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2-02/A1（补课）：threshold 三处域统一后的引擎域锚定——UI 滑杆域已改用
 * 同一常量，此测试锁定引擎钳制行为本身（防未来常量漂移）。
 */
class RedrawParamsDomainTest {

    @Test
    fun `threshold clamps to engine domain`() {
        assertEquals(10, RedrawParams(threshold = 0).clamped().threshold)
        assertEquals(10, RedrawParams(threshold = 9).clamped().threshold)
        assertEquals(150, RedrawParams(threshold = 150).clamped().threshold)
        assertEquals(150, RedrawParams(threshold = 255).clamped().threshold)
    }

    @Test
    fun `threshold mid values pass through`() {
        assertEquals(30, RedrawParams(threshold = 30).clamped().threshold)
        assertEquals(149, RedrawParams(threshold = 149).clamped().threshold)
    }
}
