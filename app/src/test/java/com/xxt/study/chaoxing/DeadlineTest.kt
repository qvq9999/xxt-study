package com.xxt.study.chaoxing

import com.xxt.study.core.ui.countdownText
import com.xxt.study.core.ui.isExpired
import com.xxt.study.core.ui.isUrgent
import com.xxt.study.data.remote.chaoxing.Parsers
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkStatus
import com.xxt.study.domain.isActionable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 作业截止时间。
 *
 * 学习通列表页不给绝对时间，只给「剩余394小时23分钟」这种相对量，
 * 所以要把「扫描时刻 + 剩余量」换算成绝对时间戳，界面才能做真实倒计时。
 */
class DeadlineTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `小时+分钟的写法`() {
        val d = Parsers.parseDeadline("当与同学意见冲突… ; 未交 剩余394小时23分钟", now)
        assertEquals(now + 394 * 3_600_000L + 23 * 60_000L, d)
    }

    @Test
    fun `天+小时+分钟的写法`() {
        val d = Parsers.parseDeadline("作业1 ; 未交 剩余 2 天 3 小时 5 分钟", now)
        assertEquals(now + (2 * 24 + 3) * 3_600_000L + 5 * 60_000L, d)
    }

    @Test
    fun `只有天也要能算`() {
        val d = Parsers.parseDeadline("作业2 ; 未交 剩余3天", now)
        assertEquals(now + 3 * 24 * 3_600_000L, d)
    }

    @Test
    fun `没有剩余时间就没有截止时间`() {
        assertNull(Parsers.parseDeadline("实践报告1 ; 未交", now))
        assertNull(Parsers.parseDeadline("作业3 ; 已完成", now))
        assertNull(Parsers.parseDeadline("", now))
    }

    @Test
    fun `零剩余不当作有时限`() {
        assertNull(Parsers.parseDeadline("作业 ; 未交 剩余0分钟", now))
    }

    @Test
    fun `倒计时文案按量级切换粒度`() {
        assertEquals("剩 30 分钟", countdownText(now + 30 * 60_000L, now))
        assertEquals("剩 5 小时", countdownText(now + 5 * 3_600_000L, now))
        assertEquals("剩 2 天 3 小时", countdownText(now + (2 * 24 + 3) * 3_600_000L, now))
        assertEquals("已过期", countdownText(now - 1000, now))
    }

    @Test
    fun `不足一分钟也要显示一分钟而不是零`() {
        assertEquals("剩 1 分钟", countdownText(now + 20_000L, now))
    }

    @Test
    fun `只有还能交的作业才提醒`() {
        val future = makeWork(WorkStatus.PENDING, now + 3_600_000L)
        val past = makeWork(WorkStatus.PENDING, now - 3_600_000L)
        val noDeadline = makeWork(WorkStatus.PENDING, null)
        val done = makeWork(WorkStatus.GRADED, now + 3_600_000L)

        assertTrue("未交且没过期 → 要提醒", future.isActionable(now))
        assertFalse("已过截止 → 不提醒（点了也交不上）", past.isActionable(now))
        assertTrue("未交且不知道截止 → 保守提醒", noDeadline.isActionable(now))
        assertFalse("已完成 → 不提醒", done.isActionable(now))
    }

    @Test
    fun `24小时内算紧急 过了算过期`() {
        assertTrue(isUrgent(now + TimeUnit.HOURS.toMillis(3), now))
        assertFalse(isUrgent(now + TimeUnit.DAYS.toMillis(3), now))
        assertFalse("已过期不算紧急", isUrgent(now - 1000, now))
        assertTrue(isExpired(now - 1000, now))
        assertFalse(isExpired(now + 1000, now))
    }

    private fun makeWork(status: WorkStatus, deadline: Long?) = Work(
        workId = "1",
        answerId = "",
        enc = "",
        title = "作业",
        rawLabel = "",
        status = status,
        detailUrl = "https://mooc1.chaoxing.com/x",
        deadline = deadline,
    )
}
