package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * SharedIconStore.normalizedRelPath 单测（A10/F2-4）：
 * 共享 RELATIVE_PATH 的唯一规范形——恒带尾斜杠，双调用幂等。
 */
class SharedIconStorePathTest {

    @Test
    fun `root dir keeps single trailing slash`() {
        assertEquals("Download/OptIcon/", SharedIconStore.normalizedRelPath(null))
    }

    @Test
    fun `sub dir gets exactly one trailing slash`() {
        assertEquals("Download/OptIcon/color_override/", SharedIconStore.normalizedRelPath("color_override"))
    }

    @Test
    fun `sub dir with trailing slash is normalized idempotently`() {
        // A10 双斜杠正源于"调用方传入已带斜杠的 subDir"——函数必须吸收它
        assertEquals(
            "Download/OptIcon/color_override/",
            SharedIconStore.normalizedRelPath("color_override/")
        )
        assertFalse(SharedIconStore.normalizedRelPath("color_override").contains("//"))
    }
}
