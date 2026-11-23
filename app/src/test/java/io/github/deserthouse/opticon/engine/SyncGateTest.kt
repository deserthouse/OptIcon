package io.github.deserthouse.opticon.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncGate 单测（修复批 X-22：进程级同步互斥）。 */
class SyncGateTest {

    @After
    fun tearDown() {
        SyncGate.release()
    }

    @Test
    fun `second acquire is refused while the first sync holds the gate`() {
        assertTrue(SyncGate.tryAcquire())
        assertTrue(SyncGate.isHeld)

        assertFalse("a second concurrent sync must be refused", SyncGate.tryAcquire())

        // 第一次的占用不受影响
        assertTrue(SyncGate.isHeld)
        SyncGate.release()
        assertFalse(SyncGate.isHeld)
    }

    @Test
    fun `gate is reusable after release`() {
        assertTrue(SyncGate.tryAcquire())
        SyncGate.release()
        assertTrue(SyncGate.tryAcquire())
        SyncGate.release()
        assertFalse(SyncGate.isHeld)
    }
}
