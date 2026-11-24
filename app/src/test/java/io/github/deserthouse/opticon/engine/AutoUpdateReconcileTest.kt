package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AutoUpdateReconcile 单测（修复批：自动更新开关两通道的双向对账）。 */
class AutoUpdateReconcileTest {

    @Test
    fun `pref on and shared file missing or off - write it on`() {
        // 旧行为已覆盖的一半：共享目录被用户 / 清理工具删过
        assertEquals(true, AutoUpdateReconcile.desiredSharedFile(prefEnabled = true, sharedEnabled = false))
    }

    @Test
    fun `pref off and shared file on - write it off`() {
        // 清除应用数据之后：偏好回到关、文件仍是开——界面显示关，后台却一直在同步。
        // 此前这个方向没人管。
        assertEquals(false, AutoUpdateReconcile.desiredSharedFile(prefEnabled = false, sharedEnabled = true))
    }

    @Test
    fun `consistent channels need no write`() {
        assertNull(AutoUpdateReconcile.desiredSharedFile(prefEnabled = true, sharedEnabled = true))
        assertNull(AutoUpdateReconcile.desiredSharedFile(prefEnabled = false, sharedEnabled = false))
    }

    @Test
    fun `unreadable shared file cannot be judged - never write`() {
        // 带锁屏密码的设备首次解锁前：读不到不等于关
        assertNull(AutoUpdateReconcile.desiredSharedFile(prefEnabled = true, sharedEnabled = null))
        assertNull(AutoUpdateReconcile.desiredSharedFile(prefEnabled = false, sharedEnabled = null))
    }
}
