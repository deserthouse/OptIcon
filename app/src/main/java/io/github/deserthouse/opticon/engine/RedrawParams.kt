package io.github.deserthouse.opticon.engine

/**
 * RedrawParams — 算法微调用户可调参数集
 *
 * 仅暴露 4 个真正影响输出的参数，99% 的算法运行中无需再调整微调位置。
 * 其余逻辑参数视为实现细节，内部默认处理。
 *
 * @property scale     缩放比例 (0.5~2.0, 默认 1.0)
 * @property offsetX   X 轴偏移量 (-50~50, 默认 0)
 * @property offsetY   Y 轴偏移量 (-50~50, 默认 0)
 * @property threshold 四角采样的色距过滤阈值 (10~150, 默认 30)
 * @property radius    圆角半径 (0~48px, 默认 0 = 不圆角)
 */
data class RedrawParams(
    val scale: Float = DEFAULT_SCALE,
    val offsetX: Float = DEFAULT_OFFSET,
    val offsetY: Float = DEFAULT_OFFSET,
    val threshold: Int = DEFAULT_THRESHOLD,
    val radius: Float = DEFAULT_RADIUS
) {
    companion object {
        /** 默认缩放比例 */
        const val DEFAULT_SCALE = 1.0f

        /** 默认偏移量 */
        const val DEFAULT_OFFSET = 0f

        /** 默认色距过滤阈值（四角采样场景值） */
        const val DEFAULT_THRESHOLD = 30

        /** 阈值调节下限 */
        const val THRESHOLD_MIN = 10

        /** 阈值调节上限 */
        const val THRESHOLD_MAX = 150

        /** 默认圆角半径 */
        const val DEFAULT_RADIUS = 0f

        /** 缩放下限 */
        const val SCALE_MIN = 0.5f

        /** 缩放上限 */
        const val SCALE_MAX = 2.0f

        /** 偏移量范围 */
        const val OFFSET_MAX = 50f

        /** 圆角半径上限 */
        const val RADIUS_MAX = 48f

        /** 全参数恢复默认值 */
        val DEFAULT = RedrawParams()
    }

    /** 约束所有参数到合法范围 */
    fun clamped(): RedrawParams = copy(
        scale = scale.coerceIn(SCALE_MIN, SCALE_MAX),
        offsetX = offsetX.coerceIn(-OFFSET_MAX, OFFSET_MAX),
        offsetY = offsetY.coerceIn(-OFFSET_MAX, OFFSET_MAX),
        threshold = threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
        radius = radius.coerceIn(0f, RADIUS_MAX)
    )
}
