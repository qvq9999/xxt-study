package com.xxt.study.chaoxing

import com.xxt.study.core.study.StudyLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按课程隔离的运行日志。
 *
 * 这里最容易出的错是"切换课程时旧日志没被清掉"，而它在界面上表现为数据串了，
 * 很难一眼看出来——所以按契约逐条钉死。
 */
class StudyLogsTest {

    private val a = "1001:2001"
    private val b = "1002:2002"

    @Test
    fun `两门课的日志互不干扰`() {
        var m = StudyLogs.push(emptyMap(), a, "A1")
        m = StudyLogs.push(m, b, "B1")
        m = StudyLogs.push(m, a, "A2")

        assertEquals(listOf("A2", "A1"), m[a])
        assertEquals(listOf("B1"), m[b])
    }

    @Test
    fun `没跑过的课查出来是空`() {
        val m = StudyLogs.push(emptyMap(), a, "A1")
        assertTrue(m["9999:9999"].orEmpty().isEmpty())
    }

    @Test
    fun `最新的一条在最上面`() {
        var m = emptyMap<String, List<String>>()
        for (i in 1..3) m = StudyLogs.push(m, a, "line$i")
        assertEquals(listOf("line3", "line2", "line1"), m[a])
    }

    @Test
    fun `重跑前清掉该课上一轮日志 别的课不受影响`() {
        var m = StudyLogs.push(emptyMap(), a, "A1")
        m = StudyLogs.push(m, b, "B1")
        m = StudyLogs.reset(m, a)
        assertTrue(m[a].orEmpty().isEmpty())
        assertEquals(listOf("B1"), m[b])
    }

    @Test
    fun `单课日志有上限 不会无限长`() {
        var m = emptyMap<String, List<String>>()
        repeat(StudyLogs.MAX_PER_COURSE + 20) { m = StudyLogs.push(m, a, "line$it") }
        assertEquals(StudyLogs.MAX_PER_COURSE, m[a]?.size)
        assertTrue("最新的要留下", m[a]!!.first().endsWith("${StudyLogs.MAX_PER_COURSE + 19}"))
    }

    @Test
    fun `同时保留的课程数有上限 淘汰最久没动的`() {
        var m = emptyMap<String, List<String>>()
        for (i in 1..StudyLogs.MAX_COURSES) m = StudyLogs.push(m, "c$i", "x")
        m = StudyLogs.push(m, "c${StudyLogs.MAX_COURSES + 1}", "x")

        assertEquals(StudyLogs.MAX_COURSES, m.size)
        assertTrue("最早的那门应该被淘汰", "c1" !in m)
        assertTrue("新来的要在", "c${StudyLogs.MAX_COURSES + 1}" in m)
    }

    @Test
    fun `更新过的课不会被优先淘汰`() {
        var m = emptyMap<String, List<String>>()
        for (i in 1..StudyLogs.MAX_COURSES) m = StudyLogs.push(m, "c$i", "x")
        // c1 又动了一次，它应该变成"最近活跃"
        m = StudyLogs.push(m, "c1", "y")
        m = StudyLogs.push(m, "new", "x")

        assertTrue("刚更新过的 c1 不该被淘汰", "c1" in m)
        assertTrue("c2 才是最久没动的", "c2" !in m)
    }

    @Test
    fun `空键与空行直接忽略`() {
        val m = StudyLogs.push(emptyMap(), a, "A1")
        assertEquals(m, StudyLogs.push(m, "", "x"))
        assertEquals(m, StudyLogs.push(m, a, ""))
    }
}
