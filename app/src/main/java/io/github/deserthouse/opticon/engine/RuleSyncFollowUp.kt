package io.github.deserthouse.opticon.engine

import io.github.deserthouse.opticon.domain.SourceFormat

/**
 * RuleSyncFollowUp — 规则库同步收尾时的两个判定（纯函数）。
 *
 * 撤销在通知实际读取的通道上生效（修复批 X-04）：hook 的第一读取路径是共享目录
 * Download/OptIcon/{pkg}.opticon，那里只增不减——镜像阶段只往里写，上游撤销的包
 * 留在原地，通知栏继续显示旧图标。同步收尾时要删掉它们的镜像。
 *
 * 不变量：用户自己保存过的产物不被误删。为某个应用启用过自定义（无论哪种策略），
 * 它的镜像一律不动——保守默认，放宽与否是待定的产品决策。
 */
internal object RuleSyncFollowUp {

    /** 需要从共享目录删除镜像的包：被上游撤销，且用户没有为它启用自定义。 */
    fun mirrorsToDelete(revoked: Collection<String>, customized: (String) -> Boolean): List<String> =
        revoked.filterNot(customized)

    /**
     * 旧版 base64 规则文件（Legacy JSON 时代的遗留）要不要在本次同步之后导入。
     * 只有当前源确实是 Legacy JSON 时才导入——此前只要文件还在就导入，规则库
     * （ANIP）刚同步好的结果会被这份旧内容原样覆盖回去（X-21）。
     */
    fun shouldIngestLegacyRules(routedFormat: SourceFormat): Boolean =
        routedFormat == SourceFormat.LEGACY_JSON
}
