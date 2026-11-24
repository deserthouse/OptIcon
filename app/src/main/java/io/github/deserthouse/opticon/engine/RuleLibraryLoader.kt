package io.github.deserthouse.opticon.engine

import android.content.Context
import io.github.deserthouse.opticon.domain.SourceFormat

/**
 * RuleLibraryLoader — 把规则库从磁盘加载进内存索引。
 *
 * 应用启动（AppListViewModel）与规则库同步收尾（SettingsViewModel.finishRuleSync）
 * 都走这里，走哪条路的判断在 [RuleSyncFollowUp.loadPlan]。
 *
 * 修复批第一轮只在同步收尾加了「当前源是 Legacy JSON 才导入旧版规则文件」的判断，
 * 应用启动入口仍是「只要旧文件还在就导入」：在设置里同步好的规则库，应用进程一重启
 * 就被旧内容覆盖回去。两处入口共用一个函数之后，这条判断不可能再只修一半，
 * 而且启动路径的行为可以直接在 JVM 里执行到。
 */
internal object RuleLibraryLoader {

    /** 加载并返回走了哪条路。[routedFormat] 是同步实际会走的格式（SubscriptionManager.resolveFormat）。 */
    fun load(context: Context, routedFormat: SourceFormat): RuleSyncFollowUp.Load {
        // 旧版规则文件只在当前源确实是 Legacy JSON 时才读——其它格式下它不算数
        val legacyRules = if (RuleSyncFollowUp.shouldIngestLegacyRules(routedFormat)) {
            SubscriptionManager.readRulesJson(context)
        } else {
            null
        }
        val plan = RuleSyncFollowUp.loadPlan(
            routedFormat, legacyRules != null, IconLibEngine.hasMetaIndex(context)
        )
        when (plan) {
            RuleSyncFollowUp.Load.INGEST_LEGACY_RULES -> IconLibEngine.ingestJson(context, legacyRules!!)
            RuleSyncFollowUp.Load.READ_SNAPSHOT -> IconLibEngine.initializeFromFiles(context, emptyList())
            // 没有可加载的东西：内存里也不能残留上一次的内容
            RuleSyncFollowUp.Load.NOTHING -> IconLibEngine.clearMemory()
        }
        return plan
    }
}
