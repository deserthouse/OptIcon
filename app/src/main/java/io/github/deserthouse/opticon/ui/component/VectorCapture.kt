package io.github.deserthouse.opticon.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * VectorCapture — Compose 侧把 ImageVector 一次性渲染为 Android Bitmap（T4-A）。
 *
 * ImageVector 的 Painter 体系只在 Compose 可用（engine 层无 Compose 依赖），
 * 因此 Material 图标在**选择时**于此捕获成位图，持久化为资产文件；
 * bake 路径读持久化文件重绘，进程重启后无需再次组合即可重烘焙。
 */
@Composable
fun captureVectorBitmap(vector: ImageVector, sizePx: Int = 96): Bitmap {
    val painter = rememberVectorPainter(vector)
    return remember(vector, sizePx) {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp.asImageBitmap())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
            with(painter) { draw(Size(sizePx.toFloat(), sizePx.toFloat())) }
        }
        bmp
    }
}
