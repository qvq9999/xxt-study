package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.Parsers
import com.xxt.study.domain.Course
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkStatus
import com.xxt.study.domain.WorkView
import com.xxt.study.domain.isActionable
import com.xxt.study.domain.view
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业展示状态机。
 *
 * 这一层是"已结课仍显示未完成作业 / 已截止仍显示未提交 / 状态显示不对"
 * 三个问题的唯一判定口径，所以单独锁死：
 * 服务端状态 × 截止时间 × 课程是否结课 × 当前时间 → 界面看到的状态。
 */
class WorkStateTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun work(
        status: WorkStatus,
        deadline: Long? = null,
        label: String = "",
    ) = Work(
        workId = "w1",
        answerId = "a1",
        enc = "e1",
        title = "作业1",
        rawLabel = label,
        status = status,
        detailUrl = "https://mooc1.chaoxing.com/work?workId=w1",
        deadline = deadline,
    )

    @Test
    fun `未交且未到期是可提交`() {
        val w = work(WorkStatus.PENDING, now + 2 * hour)
        assertEquals(WorkView.OPEN, w.view(now))
        assertTrue(w.isActionable(now))
    }

    @Test
    fun `未交但已过截止时间一律按已截止`() {
        val w = work(WorkStatus.PENDING, now - hour)
        assertEquals(WorkView.EXPIRED, w.view(now))
        assertFalse("已经交不上了，不该再挂红点", w.isActionable(now))
    }

    @Test
    fun `截止时间未知时按宁可多提醒处理`() {
        // 用户在"要不要为未知时间计红点"上选了宁可多提醒
        val w = work(WorkStatus.PENDING, null)
        assertEquals(WorkView.OPEN, w.view(now))
        assertTrue(w.isActionable(now))
    }

    @Test
    fun `结课课程里的未交不再计红点`() {
        // 这就是"已结课仍显示未完成作业"的修法：
        // 结课后补交通道已经关了，挂着红点只会让人白跑
        val w = work(WorkStatus.PENDING, null)
        assertEquals(WorkView.EXPIRED, w.view(now, courseClosed = true))
        assertFalse(w.isActionable(now, courseClosed = true))
    }

    @Test
    fun `结课不会把已交改判成已截止`() {
        val submitted = work(WorkStatus.SUBMITTED)
        assertEquals(WorkView.SUBMITTED, submitted.view(now, courseClosed = true))
    }

    @Test
    fun `服务端明说已截止就是已截止`() {
        assertEquals(WorkView.EXPIRED, work(WorkStatus.OVERDUE).view(now))
    }

    @Test
    fun `已完成与待批阅不会进待办`() {
        assertEquals(WorkView.DONE, work(WorkStatus.GRADED).view(now))
        assertEquals(WorkView.SUBMITTED, work(WorkStatus.SUBMITTED).view(now))
        assertFalse(work(WorkStatus.GRADED).isActionable(now))
        assertFalse(work(WorkStatus.SUBMITTED).isActionable(now))
    }

    @Test
    fun `认不出的状态按待确认且仍然提醒`() {
        val w = work(WorkStatus.UNKNOWN, label = "作业1 ; 系统维护中")
        assertEquals(WorkView.UNKNOWN, w.view(now))
        // 词表漏了一种新写法时，宁可多提醒一次，也不能让作业凭空消失
        assertTrue(w.view(now).countsAsTodo)
        // 但结课课程里的未知状态同样不打扰
        assertEquals(WorkView.EXPIRED, w.view(now, courseClosed = true))
    }

    @Test
    fun `课程列表能识别结课标记`() {
        val json = """
        {"channelList":[
          {"content":{"id":"c1","cpi":"1","state":1,"course":{"data":[{"id":"101","name":"军事理论","coursestate":1}]}}},
          {"content":{"id":"c2","cpi":"1","state":0,"course":{"data":[{"id":"102","name":"Java高级编程","coursestate":0}]}}},
          {"content":{"id":"c3","cpi":"1","state":0,"course":{"data":[{"id":"103","name":"旧课","coursestate":1}]}}}
        ]}
        """.trimIndent()
        val courses = Parsers.parseCourses(json).associateBy { it.courseId }

        assertTrue("state=1 是结课", courses.getValue("101").closed)
        assertFalse("正在上的课不该被判结课", courses.getValue("102").closed)
        assertTrue("coursestate=1 也认", courses.getValue("103").closed)
    }

    @Test
    fun `没有结课字段时默认按未结课处理`() {
        val json = """
        {"channelList":[{"content":{"id":"c1","course":{"data":[{"id":"101","name":"某课"}]}}}]}
        """.trimIndent()
        assertFalse(Parsers.parseCourses(json).first().closed)
    }

    @Test
    fun `课程默认未结课`() {
        // 缓存里的旧数据没有 closed 字段，反序列化后落在默认值上
        assertFalse(Course("1", "2", "3", "课").closed)
    }
}
