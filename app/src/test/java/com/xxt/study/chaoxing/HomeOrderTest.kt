package com.xxt.study.chaoxing

import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.feature.home.orderCourses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程列表排序（置顶优先）。
 *
 * 这里锁两件事，都是"看起来不会错、改起来最容易翻车"的：
 * 1. **置顶的排最前**（写成 `sortedBy` 就反了，用户长按置顶却看到它跑最后）；
 * 2. **组内顺序不变**（repo 已经按待办数降序排过，置顶只能改分组）。
 */
class HomeOrderTest {

    private fun digest(courseId: String, name: String, todo: Int = 0) = CourseDigest(
        course = Course(courseId, "class-$courseId", "cpi", name),
        todoCount = todo,
        works = emptyList(),
        error = null,
        at = 0L,
    )

    @Test
    fun `置顶的课程排在最前面`() {
        val a = digest("1", "高数")
        val b = digest("2", "英语")
        val c = digest("3", "物理")
        val out = orderCourses(listOf(a, b, c), pinned = setOf(b.key))
        assertEquals("英语", out.first().course.name)
        assertEquals("其余保持原顺序", listOf("高数", "物理"), out.drop(1).map { it.course.name })
    }

    @Test
    fun `多门置顶之间也保持原顺序`() {
        val a = digest("1", "A")
        val b = digest("2", "B")
        val c = digest("3", "C")
        val out = orderCourses(listOf(a, b, c), pinned = setOf(a.key, c.key))
        assertEquals(listOf("A", "C", "B"), out.map { it.course.name })
    }

    @Test
    fun `没有置顶时顺序完全不变`() {
        val list = listOf(digest("1", "A"), digest("2", "B"), digest("3", "C"))
        assertEquals(list.map { it.course.name }, orderCourses(list, emptySet()).map { it.course.name })
    }

    @Test
    fun `置顶集合里有过期 key 不会影响结果`() {
        // 换了学期、课程没了，置顶集合里会留下旧 key —— 不能因此把别人顶走
        val list = listOf(digest("1", "A"), digest("2", "B"))
        val out = orderCourses(list, pinned = setOf("999:class-999"))
        assertEquals(listOf("A", "B"), out.map { it.course.name })
        assertTrue("数量不能变", out.size == 2)
    }

    @Test
    fun `空列表不崩`() {
        assertTrue(orderCourses(emptyList(), setOf("1:2")).isEmpty())
    }
}
