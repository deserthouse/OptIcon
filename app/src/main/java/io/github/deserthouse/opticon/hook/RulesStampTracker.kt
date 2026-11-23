package io.github.deserthouse.opticon.hook

/**
 * RulesStampTracker — 规则版本戳的跟踪（修复批 X-04，纯 Kotlin）。
 *
 * 应用侧在规则库同步收尾后往共享目录写一个版本戳（时间戳）。hook 侧每次看到
 * 与上次不同的戳，就意味着共享目录里的规则图标镜像变了（被更新、或被上游撤销的
 * 包的镜像已被删除）——图标缓存必须作废，否则通知栏继续显示旧图标。
 *
 * 读不到戳（null：文件不存在，或带锁屏密码的设备首次解锁前目录不可读）时
 * 永远不触发；从「读不到」变成「读到」算一次变化。
 */
internal class RulesStampTracker(private val onChanged: () -> Unit) {

    @Volatile
    var last: String? = null
        private set

    /** 初始化时记下当前的戳，避免把开机时就已有的戳当成一次变化。 */
    fun seed(current: String?) {
        last = current
    }

    /** @return true = 戳变了，并且已经触发 onChanged */
    fun observe(current: String?): Boolean {
        if (current == null || current == last) return false
        last = current
        onChanged()
        return true
    }
}
