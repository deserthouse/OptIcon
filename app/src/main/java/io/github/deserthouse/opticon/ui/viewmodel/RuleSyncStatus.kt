package io.github.deserthouse.opticon.ui.viewmodel

import io.github.deserthouse.opticon.R
import java.time.Instant
import java.time.ZoneId

/**
 * 规则库同步结束后状态行的文案（批 UP-U4）。
 *
 * 快照来自上游官方发布通道时带上版本——「· 上游 e98c906（2026-11-26）」：标签对得上上游的发布页，
 * 日期说明有多新。退回整库 zipball、或旧版规则源没有版本可报，用原来的两条文案
 * （设备验证套件靠「ANIP synced」「已是最新」这两个说法认状态，带版本的两条以它们开头）。
 *
 * 纯决策，不读资源；[Text] 由调用方取字。
 */
internal object RuleSyncStatus {

    /** 一条状态行：资源 id 与参数。 */
    data class Text(val resId: Int, val args: Array<out Any?>)

    /** 上游版本的发布日期（yyyy-MM-dd，按 [zone]）。没有版本可报 → null。 */
    fun date(versionTime: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
        if (versionTime <= 0L) null else Instant.ofEpochMilli(versionTime).atZone(zone).toLocalDate().toString()

    private fun hasVersion(tag: String?, versionTime: Long): Boolean = !tag.isNullOrBlank() && versionTime > 0L

    /** 上游没有新版本。 */
    fun upToDate(count: Int, tag: String?, versionTime: Long, zone: ZoneId = ZoneId.systemDefault()): Text =
        if (hasVersion(tag, versionTime)) {
            Text(R.string.sync_anip_uptodate_upstream, arrayOf<Any?>(count, tag, date(versionTime, zone)))
        } else {
            Text(R.string.sync_anip_uptodate, arrayOf(count))
        }

    /** 同步成功，收尾也做完了。 */
    fun done(count: Int, tag: String?, versionTime: Long, zone: ZoneId = ZoneId.systemDefault()): Text =
        if (hasVersion(tag, versionTime)) {
            Text(R.string.sync_anip_done_upstream, arrayOf<Any?>(count, tag, date(versionTime, zone)))
        } else {
            Text(R.string.sync_anip_done, arrayOf(count))
        }
}
