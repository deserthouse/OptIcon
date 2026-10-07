package io.github.deserthouse.opticon

import io.github.deserthouse.opticon.engine.IconRedrawEngine
import io.github.deserthouse.opticon.engine.IconRedrawEngine.BgEstimateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IconRedrawEngine 纯像素核心单测（M1 主色键控 + 覆盖率守卫）。
 * 不依赖 android.graphics —— Bitmap 包装层的薄壳不在本测试范围。
 */
class IconRedrawEngineCoreTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** 生成 w×h 图像：全图 bg 色，中心 side×side 方块为 glyph 色 */
    private fun solidWithGlyph(w: Int, h: Int, bg: Int, glyph: Int, side: Int): IntArray {
        val px = IntArray(w * h) { bg }
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        for (y in y0 until y0 + side) for (x in x0 until x0 + side) px[y * w + x] = glyph
        return px
    }

    // ━━ 背景估计 ━━

    @Test
    fun `corner sampling averages only opaque corners`() {
        // 三角不透明青色 + 一角透明（RGB=0）——旧实现会把黑均值进来
        val w = 8; val h = 8
        val cyan = argb(255, 0, 200, 200)
        val px = IntArray(w * h) { cyan }
        px[0] = argb(0, 0, 0, 0) // 透明角
        val est = IconRedrawEngine.estimateBackground(px, w)
        assertNotNull(est)
        assertEquals(BgEstimateMode.CORNER_SAMPLE, est!!.mode)
        // 均值只来自 3 个不透明角 = 纯青，不被透明角的 0 拉暗
        assertEquals(0, (est.rgb shr 16) and 0xFF)
        assertEquals(200, (est.rgb shr 8) and 0xFF)
        assertEquals(200, est.rgb and 0xFF)
    }

    @Test
    fun `mostly transparent margins fall back to dominant color`() {
        // 透明边距图标：中心 4x4 深灰 glyph 在 8x8 透明画布上（0 不透明角）
        val w = 8; val h = 8
        val transparent = argb(0, 0, 0, 0)
        val glyph = argb(255, 60, 60, 60)
        val px = IntArray(w * h) { transparent }
        for (y in 2 until 6) for (x in 2 until 6) px[y * w + x] = glyph
        val est = IconRedrawEngine.estimateBackground(px, w)
        assertNotNull(est)
        assertEquals(BgEstimateMode.DOMINANT_COLOR, est!!.mode)
        // 主色=glyph 自身（60,60,60 落 32 级量化的 1 号桶，代表色 = 1*32+16 = 48）
        assertEquals(48, (est.rgb shr 16) and 0xFF)
    }

    @Test
    fun `fully transparent source has no estimate`() {
        val px = IntArray(64) // 全 0
        assertNull(IconRedrawEngine.estimateBackground(px, 8))
    }

    // ━━ 键控 ━━

    @Test
    fun `keying whites the glyph and clears the background`() {
        val w = 10; val h = 10
        val bg = argb(255, 250, 250, 250)
        val glyph = argb(255, 30, 30, 30)
        val px = solidWithGlyph(w, h, bg, glyph, side = 4)
        val est = IconRedrawEngine.estimateBackground(px, w)!!
        val keyed = IconRedrawEngine.keyBackground(px, est.rgb, threshold = 30)

        assertEquals(0x00000000, keyed[0]) // 角落背景 → 全透明
        val center = keyed[5 * w + 5]
        assertEquals(255, (center ushr 24) and 0xFF) // glyph 不透明
        assertEquals(255, (center shr 16) and 0xFF)  // 刷白
        assertEquals(255, center and 0xFF)
    }

    @Test
    fun `soft alpha pixels keep their alpha when keyed as glyph`() {
        val w = 4; val h = 4
        val bg = argb(255, 255, 255, 255)
        val soft = argb(128, 10, 10, 10) // 半透明深色前景
        val px = IntArray(w * h) { bg }
        px[5] = soft
        val keyed = IconRedrawEngine.keyBackground(px, bg, threshold = 30)
        assertEquals(128, (keyed[5] ushr 24) and 0xFF) // 继承原 alpha
        assertEquals(255, keyed[5] and 0xFF)           // 刷白
    }

    // ━━ 覆盖率守卫 ━━

    @Test
    fun `coverage counts only opaque pixels`() {
        val px = IntArray(100) { argb(0, 0, 0, 0) }
        for (i in 0 until 25) px[i] = argb(255, 255, 255, 255)
        assertEquals(0.25f, IconRedrawEngine.foregroundCoverage(px), 0.0001f)
    }

    @Test
    fun `white rectangle garbage exceeds degenerate guard`() {
        // 键控失败场景：背景估计落在 glyph 上 → 全图变前景（白矩形）
        val coverage = 0.97f
        assertTrue(coverage > IconRedrawEngine.FG_COVERAGE_MAX)
    }

    @Test
    fun `over-keyed empty result falls below floor guard`() {
        val coverage = 0.0f
        assertTrue(coverage < IconRedrawEngine.FG_COVERAGE_MIN)
    }

    @Test
    fun `legitimate thin glyph sits inside guard band`() {
        // 10x10 里 2x8 细条 = 16% 占比，正常 glyph 范围
        val w = 10; val h = 10
        val bg = argb(255, 240, 240, 240)
        val glyph = argb(255, 40, 40, 40)
        val px = IntArray(w * h) { bg }
        for (y in 1 until 9) for (x in 4 until 6) px[y * w + x] = glyph
        val keyed = IconRedrawEngine.keyBackground(px, bg, threshold = 30)
        val coverage = IconRedrawEngine.foregroundCoverage(keyed)
        assertTrue("coverage=$coverage should be within band", 
            coverage in IconRedrawEngine.FG_COVERAGE_MIN..IconRedrawEngine.FG_COVERAGE_MAX)
    }
}
