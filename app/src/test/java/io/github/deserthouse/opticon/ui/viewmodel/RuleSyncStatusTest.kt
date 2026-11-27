package io.github.deserthouse.opticon.ui.viewmodel

import io.github.deserthouse.opticon.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 批 UP-U4：规则库同步之后状态行用哪条文案、带什么参数。
 * 文案本身在资源里（设备套件在界面上核对），这里测的是选哪条。
 */
class RuleSyncStatusTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 上游 e98c906 的清单里的时间戳：UTC 2026-11-26 12:52。 */
    private val released = 1795697528000L

    @Test
    fun `a snapshot from the release channel shows the upstream version`() {
        val upToDate = RuleSyncStatus.upToDate(924, "e98c906", released, utc)
        assertEquals(R.string.sync_anip_uptodate_upstream, upToDate.resId)
        assertEquals(listOf(924, "e98c906", "2026-11-26"), upToDate.args.toList())

        val done = RuleSyncStatus.done(924, "e98c906", released, utc)
        assertEquals(R.string.sync_anip_done_upstream, done.resId)
        assertEquals(listOf(924, "e98c906", "2026-11-26"), done.args.toList())
    }

    @Test
    fun `without a version to report the wording stays as it was`() {
        // 退回整库 zipball：没有标签也没有时间
        for (tag in listOf(null, "")) {
            val upToDate = RuleSyncStatus.upToDate(924, tag, 0L, utc)
            assertEquals(R.string.sync_anip_uptodate, upToDate.resId)
            assertEquals(listOf(924), upToDate.args.toList())

            val done = RuleSyncStatus.done(924, tag, 0L, utc)
            assertEquals(R.string.sync_anip_done, done.resId)
            assertEquals(listOf(924), done.args.toList())
        }
    }

    @Test
    fun `half a version is not shown`() {
        // 标签和时间缺一个就不报版本：不让界面上出现「上游 null」或一个没有出处的日期
        assertEquals(R.string.sync_anip_uptodate, RuleSyncStatus.upToDate(924, "e98c906", 0L, utc).resId)
        assertEquals(R.string.sync_anip_done, RuleSyncStatus.done(924, "e98c906", 0L, utc).resId)
        assertEquals(R.string.sync_anip_uptodate, RuleSyncStatus.upToDate(924, null, released, utc).resId)
        assertEquals(R.string.sync_anip_done, RuleSyncStatus.done(924, null, released, utc).resId)
        assertEquals(R.string.sync_anip_uptodate, RuleSyncStatus.upToDate(924, null, -5L, utc).resId)
    }

    @Test
    fun `the date follows the device time zone`() {
        assertEquals("2026-11-26", RuleSyncStatus.date(released, utc))
        assertEquals("2026-11-26", RuleSyncStatus.date(released, shanghai))
        // UTC 的 26 日 23:30，在东八区已经是 27 日早上
        val lateEvening = 1795735800000L
        assertEquals("2026-11-26", RuleSyncStatus.date(lateEvening, utc))
        assertEquals("2026-11-27", RuleSyncStatus.date(lateEvening, shanghai))
    }

    @Test
    fun `no timestamp means no date`() {
        assertNull(RuleSyncStatus.date(0L))
        assertNull(RuleSyncStatus.date(-1L))
    }
}
