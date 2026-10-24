package io.github.deserthouse.opticon.ui

import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.FlatSource
import io.github.deserthouse.opticon.ui.state.IconStrategy
import io.github.deserthouse.opticon.ui.state.selectedFlatSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-B2：扁平来源选中映射的唯一事实源——高亮与摘要标签共用，9 项 + 抓取
 *  原图翻转 + 跨策略链全覆盖。 */
class FlatSourceMappingTest {

    private fun map(
        strategy: IconStrategy,
        sub: AssetSubStrategy = AssetSubStrategy.ADAPTIVE_DECOMPOSE,
        algo: AlgoSource = AlgoSource.SELF,
        captured: Boolean = false
    ): FlatSource = selectedFlatSource(strategy, sub, algo, captured)

    @Test
    fun `all nine sources map from their canonical states`() {
        assertEquals(FlatSource.RulesLib, map(IconStrategy.FANKES))
        assertEquals(FlatSource.AdaptiveDecompose, map(IconStrategy.ASSET_IMPORT, AssetSubStrategy.ADAPTIVE_DECOMPOSE))
        assertEquals(FlatSource.Picp, map(IconStrategy.ASSET_IMPORT, AssetSubStrategy.PERFECT_ICONS))
        assertEquals(FlatSource.IconPack, map(IconStrategy.ASSET_IMPORT, AssetSubStrategy.ICON_PACK))
        assertEquals(FlatSource.SelfIcon, map(IconStrategy.ALGORITHM, algo = AlgoSource.SELF))
        assertEquals(FlatSource.CapturedOriginal, map(IconStrategy.ALGORITHM, algo = AlgoSource.LOCAL_FILE, captured = true))
        assertEquals(FlatSource.Upload, map(IconStrategy.ALGORITHM, algo = AlgoSource.LOCAL_FILE, captured = false))
        assertEquals(FlatSource.MaterialLib, map(IconStrategy.ALGORITHM, algo = AlgoSource.MATERIAL_LIB))
        assertEquals(FlatSource.EmojiText, map(IconStrategy.ALGORITHM, algo = AlgoSource.EMOJI_TEXT))
    }

    @Test
    fun `captured flag flips LOCAL_FILE between captured-original and upload`() {
        assertEquals(FlatSource.CapturedOriginal, map(IconStrategy.ALGORITHM, algo = AlgoSource.LOCAL_FILE, captured = true))
        assertEquals(FlatSource.Upload, map(IconStrategy.ALGORITHM, algo = AlgoSource.LOCAL_FILE, captured = false))
    }

    @Test
    fun `strategy chain fankes - iconpack - algorithm keeps mapping consistent`() {
        // 用户在扁平列表依次点 规则库 → 图标包 → 应用自身图标（applyFlatSource 落稿后的期望状态）
        assertEquals(FlatSource.RulesLib, map(IconStrategy.FANKES))
        assertEquals(FlatSource.IconPack, map(IconStrategy.ASSET_IMPORT, AssetSubStrategy.ICON_PACK))
        assertEquals(FlatSource.SelfIcon, map(IconStrategy.ALGORITHM, algo = AlgoSource.SELF))
    }

    @Test
    fun `non-algorithm strategies ignore stale algo fields`() {
        // 策略已切走但 algoSource/captured 残留旧值（既定语义：源字段不清洗）——映射不受污染
        assertEquals(FlatSource.RulesLib, map(IconStrategy.FANKES, algo = AlgoSource.LOCAL_FILE, captured = true))
        assertEquals(FlatSource.Picp, map(IconStrategy.ASSET_IMPORT, AssetSubStrategy.PERFECT_ICONS, AlgoSource.EMOJI_TEXT))
    }
}
