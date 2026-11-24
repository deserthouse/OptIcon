package io.github.deserthouse.opticon.engine

/**
 * AutoUpdateReconcile — 「规则库自动更新」开关两个通道的对账判定（纯函数）。
 *
 * 开关有两份状态：应用内偏好（界面显示、用户操作的事实源）和共享目录里的
 * auto_lib_update.opticon（hook 进程唯一的读取面——它读不到模块的偏好）。
 * 修复批之前对账是单向的：只处理「偏好开着而文件缺失」，反过来的不一致没人管——
 * 清除应用数据后偏好回到关、文件仍是开，界面显示关，后台却一直在联网同步
 * （修复批 X-05 关联项 A-09 / B-04）。
 *
 * 现在双向对齐，以应用内偏好为事实源。共享文件读不到时无从判断，什么都不做。
 */
internal object AutoUpdateReconcile {

    enum class Outcome {
        /** 两个通道已经一致。 */
        CONSISTENT,

        /** 不一致，已按应用内偏好把共享文件改写。 */
        FIXED,

        /** 共享文件读不到，或改写失败——无从判断 / 没能对齐。 */
        UNKNOWN,
    }

    /**
     * @return 需要把共享文件写成什么；null = 不用动。
     * [sharedEnabled] 为 null 表示读不到（文件不存在按「关」算，与读不到不同）。
     */
    fun desiredSharedFile(prefEnabled: Boolean, sharedEnabled: Boolean?): Boolean? {
        if (sharedEnabled == null) return null
        return if (sharedEnabled != prefEnabled) prefEnabled else null
    }
}
