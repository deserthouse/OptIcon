package io.github.deserthouse.opticon.engine.publish

/**
 * PublishStore — 发布目标的抽象边界（F1-1，post-change 审查 A02/A03）。
 *
 * 生产实现 = SharedIconStore 适配（回读校验）；测试实现 = 内存假体。
 * 契约：[deliver] 返回 true 表示「目标位现在就是这些字节」——实现必须自行
 * 回读验证，不得以「写入调用没抛异常」为成功依据（审查不变量④：替身配送端
 * 返回路径但交付错误字节时必须报失败）。
 */
interface PublishStore {
    /** 写入共享目标并回读校验；true = 已确认交付的就是这些字节 */
    fun deliver(pkg: String, bytes: ByteArray): Boolean

    /** 读当前实际交付内容（hook 直读的 canonical 文件），无则 null */
    fun readDelivered(pkg: String): ByteArray?

    /** 删除交付物；目标本就不存在也返回 true */
    fun remove(pkg: String): Boolean
}

/** 日志边界——核心保持 JVM 纯净，不直接依赖 android.util/TraceLogger */
interface PublishLog {
    fun i(m: String)
    fun e(m: String)
}
