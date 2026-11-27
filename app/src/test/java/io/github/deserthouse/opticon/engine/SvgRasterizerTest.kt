package io.github.deserthouse.opticon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批 UP：SVG 出图尺寸的判断（纯函数部分）。渲染本身要 Android 的 Bitmap / Canvas，在设备上验证。
 * 尺寸约定参照上游 SDK：文档尺寸须在 1~150px。
 */
class SvgRasterizerTest {

    @Test
    fun `the size the svg declares is used`() {
        assertEquals(128 to 128, SvgRasterizer.renderSize(128f, 128f, 150))
        assertEquals(150 to 150, SvgRasterizer.renderSize(150f, 150f, 150))
        assertEquals(72 to 48, SvgRasterizer.renderSize(72f, 48f, 150))
        // 小数四舍五入
        assertEquals(128 to 128, SvgRasterizer.renderSize(127.6f, 128.4f, 150))
    }

    @Test
    fun `an svg without a declared size is drawn at the default size`() {
        // AndroidSVG 对没写宽高的文档返回 NaN
        assertEquals(72 to 72, SvgRasterizer.renderSize(Float.NaN, Float.NaN, 150))
        assertEquals(72 to 72, SvgRasterizer.renderSize(-1f, 0f, 150))
        assertEquals(72 to 48, SvgRasterizer.renderSize(Float.POSITIVE_INFINITY, 48f, 150))
    }

    @Test
    fun `a document larger than the limit is not rendered`() {
        assertNull(SvgRasterizer.renderSize(151f, 151f, 150))
        assertNull(SvgRasterizer.renderSize(128f, 151f, 150))
        assertNull(SvgRasterizer.renderSize(1000f, 1000f, 150))
    }

    @Test
    fun `the limit comes from the caller`() {
        assertEquals(128 to 128, SvgRasterizer.renderSize(128f, 128f, 128))
        assertNull(SvgRasterizer.renderSize(128f, 128f, 127))
    }
}
