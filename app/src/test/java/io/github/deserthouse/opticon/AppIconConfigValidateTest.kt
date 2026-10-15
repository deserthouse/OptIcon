package io.github.deserthouse.opticon.data

import io.github.deserthouse.opticon.engine.RedrawParams
import io.github.deserthouse.opticon.ui.state.AlgoSource
import io.github.deserthouse.opticon.ui.state.AssetSubStrategy
import io.github.deserthouse.opticon.ui.state.IconStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppIconConfig validate() 组合矩阵单测（阶段 2 A1 交付）。
 * 覆盖三种策略各自的必填字段约束与无效组合拒绝。
 */
class AppIconConfigValidateTest {

    private fun config(
        strategy: IconStrategy,
        sub: AssetSubStrategy? = null,
        algoSource: AlgoSource? = null,
        iconPackPkg: String? = null,
        packDrawable: String? = null,
        customIconPath: String? = null,
        materialIconName: String? = null,
        emojiText: String? = null
    ) = AppIconConfig(
        pkg = "com.test.app", enabled = true, strategy = strategy,
        assetSubStrategy = sub, algoSource = algoSource,
        iconPackPkg = iconPackPkg, packDrawable = packDrawable,
        customIconPath = customIconPath, materialIconName = materialIconName,
        emojiText = emojiText
    )

    @Test
    fun `valid FANKES config passes`() {
        val c = config(IconStrategy.FANKES)
        assertTrue(c.validate().isEmpty())
    }

    @Test
    fun `valid ASSET_IMPORT ICON_PACK passes`() {
        val c = config(IconStrategy.ASSET_IMPORT, sub = AssetSubStrategy.ICON_PACK,
            iconPackPkg = "com.pack", packDrawable = "icon_1")
        assertTrue(c.validate().isEmpty())
    }

    @Test
    fun `ASSET_IMPORT without sub fails`() {
        val errors = config(IconStrategy.ASSET_IMPORT).validate()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `ICON_PACK without packDrawable fails`() {
        val errors = config(IconStrategy.ASSET_IMPORT, sub = AssetSubStrategy.ICON_PACK,
            iconPackPkg = "com.pack").validate()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `valid ALGORITHM SELF passes`() {
        val errors = config(IconStrategy.ALGORITHM, algoSource = AlgoSource.SELF).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `ALGORITHM LOCAL_FILE without path fails`() {
        val errors = config(IconStrategy.ALGORITHM, algoSource = AlgoSource.LOCAL_FILE).validate()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `ALGORITHM MATERIAL_LIB without name fails`() {
        val errors = config(IconStrategy.ALGORITHM, algoSource = AlgoSource.MATERIAL_LIB).validate()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `valid ALGORITHM EMOJI passes`() {
        val errors = config(IconStrategy.ALGORITHM, algoSource = AlgoSource.EMOJI_TEXT,
            emojiText = "🐾").validate()
        assertTrue(errors.isEmpty())
    }
}
