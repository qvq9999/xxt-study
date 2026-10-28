package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.Parsers
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实抓包样本跑课程解析。
 *
 * 样本：`.workbuddy/research/samples/02-courselist-json.html`
 * （真实账号 backclazzdata 响应，41 门课）
 *
 * 这个测试存在的意义：课程名曾经全是空串，界面上一片"未命名课程"。
 * 根因是 JSON 里 courseid 的字段名其实叫 `id`，课程名埋在
 * `content.course.data[0].name`，而旧实现只在同一层找 `courseid`+`classid`。
 */
class CourseListRealDataTest {

    private fun sample(): String? {
        val candidates = listOf(
            "../.workbuddy/research/samples/02-courselist-json.html",
            "../../.workbuddy/research/samples/02-courselist-json.html",
            "E:/Codex wenjian/学习通/.workbuddy/research/samples/02-courselist-json.html",
        )
        for (p in candidates) {
            val f = File(p)
            if (f.exists()) return f.readText()
        }
        return null
    }

    @Test
    fun `真实响应里每门课都要有名字`() {
        val raw = sample()
        assumeTrue("没有真实样本，跳过", raw != null)
        val courses = Parsers.parseCourses(raw!!)
        println("解析到 ${courses.size} 门课：")
        courses.take(8).forEach { println("  [${it.courseId}/${it.classId}] cpi=${it.cpi} name='${it.name}'") }

        assertTrue("应该解析出多门课", courses.size > 1)
        val blank = courses.filter { it.name.isBlank() }
        assertTrue("有 ${blank.size} 门课没名字：$blank", blank.isEmpty())
        assertTrue("courseId 不能空", courses.all { it.courseId.isNotBlank() })
        assertTrue("classId 不能空", courses.all { it.classId.isNotBlank() })
        assertTrue("cpi 不能空", courses.all { it.cpi.isNotBlank() })
    }

    private fun assertTrue(msg: String, cond: Boolean) = org.junit.Assert.assertTrue(msg, cond)
}
