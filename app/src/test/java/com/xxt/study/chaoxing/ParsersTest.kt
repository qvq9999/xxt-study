package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.Parsers
import com.xxt.study.domain.WorkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器单测。
 * fixture 与 tools/probe-homework.mjs --selftest 同源，两边必须同步演进。
 * 平台改版时先在这里复现，再改 Parsers。
 */
class ParsersTest {

    // ---------- 隐藏 input：属性顺序不能假设 ----------

    @Test
    fun `隐藏 input 三种属性顺序都能抽到`() {
        val html = """
            <html><body>
            <input type="hidden" id="enc" value="E1">
            <input type="hidden" id="workEnc" value="W1">
            <input value="O1" id="openc" type="hidden">
            <input type="hidden" id="oldenc" value="">
            <input name="answer123" value="y">
            </body></html>
        """.trimIndent()
        val m = Parsers.parseHiddenInputs(html)
        assertEquals("E1", m["enc"])
        assertEquals("W1", m["workEnc"])
        assertEquals("O1", m["openc"])
        assertNull("空 value 不该入表", m["oldenc"])
        assertNull("没有 id 的 input 不该入表", m["answer123"])
    }

    @Test
    fun `空白 html 不抛异常`() {
        assertTrue(Parsers.parseHiddenInputs("").isEmpty())
        assertTrue(Parsers.parseWorkRows("").isEmpty())
        assertEquals(WorkStatus.UNKNOWN, Parsers.normalizeStatus(""))
    }

    // ---------- 作业行 ----------

    private val workListHtml = """
        <ul>
          <li onclick="goTask(this);" data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?courseId=1&amp;classId=2&amp;cpi=3&amp;workId=99&amp;answerId=88&amp;enc=abc" aria-label="实验2 ; 未交"><span>实验2</span></li>
          <li onclick="goTask(this);" data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=77&amp;answerId=66&amp;enc=def" aria-label="实验1 ; 待批阅"></li>
          <li>无关行，没有 data</li>
          <li data="/help/index">也不是作业</li>
        </ul>
    """.trimIndent()

    @Test
    fun `只收 chaoxing 直链的作业行`() {
        val rows = Parsers.parseWorkRows(workListHtml)
        assertEquals(2, rows.size)
    }

    @Test
    fun `从 data 直链拆出 workId answerId enc`() {
        val first = Parsers.parseWorkRows(workListHtml).first()
        assertEquals("99", first.workId)
        assertEquals("88", first.answerId)
        assertEquals("abc", first.enc)
    }

    @Test
    fun `标题取 aria-label 的分号前半段`() {
        val first = Parsers.parseWorkRows(workListHtml).first()
        assertEquals("实验2", first.title)
        assertEquals("实验2 ; 未交", first.rawLabel)
    }

    @Test
    fun `标题也兼容全角分号`() {
        val html = """<li data="https://mooc1.chaoxing.com/x?workId=1" aria-label="第三章测验；已完成"></li>"""
        val w = Parsers.parseWorkRows(html).first()
        assertEquals("第三章测验", w.title)
        assertEquals(WorkStatus.GRADED, w.status)
    }

    // ---------- 状态归一 ----------

    @Test
    fun `四种状态按优先级归一`() {
        assertEquals(WorkStatus.PENDING, Parsers.normalizeStatus("实验2 ; 未交"))
        assertEquals(WorkStatus.PENDING, Parsers.normalizeStatus("作业3 ; 未提交"))
        assertEquals(WorkStatus.SUBMITTED, Parsers.normalizeStatus("实验1 ; 待批阅"))
        assertEquals(WorkStatus.SUBMITTED, Parsers.normalizeStatus("作业2 ; 已交"))
        assertEquals(WorkStatus.GRADED, Parsers.normalizeStatus("第3章 ; 已完成"))
        assertEquals(WorkStatus.GRADED, Parsers.normalizeStatus("作业4 ; 已批阅"))
        assertEquals(WorkStatus.OVERDUE, Parsers.normalizeStatus("作业5 ; 已过期"))
        assertEquals(WorkStatus.UNKNOWN, Parsers.normalizeStatus("随便什么"))
    }

    @Test
    fun `已截止字样优先于未交`() {
        /*
         * 学习通会把两者写在同一行：「作业5 ; 未交（已过期）」。
         *
         * 旧实现先判"未交"，这条彻底交不了的作业就永远算在待办里——
         * 用户在界面上看到的就是"已截止的作业还挂在待提交"。
         * 现在先判"已截止"，它落到 OVERDUE，界面按"已截止"展示、不计红点。
         */
        assertEquals(WorkStatus.OVERDUE, Parsers.normalizeStatus("作业5 ; 未交（已过期）"))
        assertEquals(WorkStatus.OVERDUE, Parsers.normalizeStatus("作业5 ; 未交 已截止"))
        // 交过的就是交过的：哪怕同时写着已截止，也不该退回待办
        assertEquals(WorkStatus.SUBMITTED, Parsers.normalizeStatus("作业6 ; 已交（已截止）"))
    }

    @Test
    fun `剩余0分钟说明已经截止`() {
        val now = 1_700_000_000_000L
        // 有"剩余"但归零 → 硬证据
        assertEquals(0L, Parsers.remainMillis("作业5 ; 未交 剩余0分钟"))
        // 完全没有"剩余"字样 → 不知道截止时间（返回 null，和上面必须分清）
        assertNull(Parsers.remainMillis("作业5 ; 未交"))
        assertNull(Parsers.parseDeadline("作业5 ; 未交 剩余0分钟", now))
    }

    // ---------- 课程列表 ----------

    @Test
    fun `JSON 主源按 channelList 取课程并去重`() {
        val json = """
        {"channelList":[{"content":{"course":{"data":[
          {"courseid":111,"clazzid":222,"cpi":333,"name":"高等数学"},
          {"courseid":111,"clazzid":222,"cpi":333,"name":"高等数学(重复)"},
          {"courseid":444,"clazzid":555,"cpi":666,"name":"大学英语"}
        ]}}}]}
        """.trimIndent()
        val cs = Parsers.parseCourses(json)
        assertEquals(2, cs.size)
        assertEquals("111", cs[0].courseId)
        assertEquals("222", cs[0].classId)
        assertEquals("333", cs[0].cpi)
        assertEquals("高等数学", cs[0].name)
    }

    @Test
    fun `JSON 结构意外时靠深度遍历兜底`() {
        val json = """{"a":{"b":[{"courseId":"9","classId":"8","cpi":"7","title":"兜底课"}]}}"""
        val cs = Parsers.parseCourses(json)
        assertEquals(1, cs.size)
        assertEquals("兜底课", cs[0].name)
    }

    @Test
    fun `非法 JSON 返回空表而不是崩`() {
        assertTrue(Parsers.parseCourses("<html>not json</html>").isEmpty())
        assertTrue(Parsers.parseCourses("{}").isEmpty())
    }

    @Test
    fun `HTML 兜底源从链接抄四要素`() {
        val html = """
        <div class="course">
          <a href="https://mooc2-ans.chaoxing.com/stu?courseid=11&amp;clazzid=22&amp;cpi=33" title="线性代数"><span class="course-name">线性代数</span></a>
        </div>
        """.trimIndent()
        val cs = Parsers.parseCoursesFromHtml(html)
        assertEquals(1, cs.size)
        assertEquals("11", cs[0].courseId)
        assertEquals("22", cs[0].classId)
        assertEquals("33", cs[0].cpi)
        assertEquals("线性代数", cs[0].name)
    }

    // ---------- 详情 ----------

    @Test
    fun `详情页没有年份也要解析出截止时间`() {
        val d = Parsers.parseWorkDetail("<div>共 10 道题 满分: 100 作答时间: 10-08 08:00 至 10-15 23:59 智能分析 86 分</div>")
        assertEquals("10", d.questionCount)
        assertEquals("100", d.fullScore)
        assertEquals("86", d.score)
        assertNotNull(d.timeRange)
        assertEquals("10-15 23:59", d.endAt)
    }

    @Test
    fun `详情页带年份同样能解析`() {
        val d = Parsers.parseWorkDetail("作答时间: 2026-10-08 08:00 至 2026-10-15 23:59")
        assertEquals("2026-10-15 23:59", d.endAt)
    }

    @Test
    fun `详情字段缺失时留空不报错`() {
        val d = Parsers.parseWorkDetail("<div>本作业暂无说明</div>")
        assertNull(d.questionCount)
        assertNull(d.endAt)
    }

    // ---------- 拒绝与登录态 ----------

    @Test
    fun `服务端拒绝词能被识别`() {
        assertEquals("您没有权限", Parsers.detectFailure("您没有权限访问"))
        assertEquals("参数验证不通过", Parsers.detectFailure("<p>参数验证不通过</p>"))
        assertNull(Parsers.detectFailure("<div>作业列表</div>"))
    }

    @Test
    fun `被重定向到登录页判为失效`() {
        assertTrue(Parsers.isLoginRedirect("https://passport2.chaoxing.com/login?fid=1"))
        assertTrue(Parsers.isLoginRedirect("https://mooc1.chaoxing.com/x", "请先登录"))
        assertFalse(Parsers.isLoginRedirect("https://i.chaoxing.com/base", "<div>我的课程</div>"))
    }

    @Test
    fun `登录态信息可提取`() {
        val html = """<html><script>var fid = "8899";</script>
            <span id="siteName" title="某某大学"></span>
            <a aria-label="账号：138****0000"></a></html>"""
        assertEquals("8899", Parsers.parseFid(html))
        assertEquals("某某大学", Parsers.parseSiteName(html))
        assertEquals("138****0000", Parsers.parseAccount(html))
    }

    @Test
    fun `有 li 但没有 chaoxing 直链判为结构变化`() {
        assertTrue(Parsers.looksLikeChangedStructure("<ul><li>作业1</li></ul>"))
        assertFalse(Parsers.looksLikeChangedStructure(workListHtml))
        assertFalse("完全没有 li 应判为空列表", Parsers.looksLikeChangedStructure("<div>暂无作业</div>"))
    }
}
