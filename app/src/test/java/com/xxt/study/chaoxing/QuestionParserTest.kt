package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.QuestionParser
import com.xxt.study.domain.QType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题目解析单测。
 *
 * fixture 是 2026-10-27 从真实作业（Photoshop「蒙版」）抓下来的 .TiMu outerHTML，
 * 12 题：4 单选 + 3 填空 + 5 判断。不是手写样例，是线上真实结构。
 */
class QuestionParserTest {

    private val html: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("work-question.html")!!
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun `整卷解析出 12 道题`() {
        val qs = QuestionParser.parse(html)
        assertEquals(12, qs.size)
    }

    @Test
    fun `TiMu 是整卷容器而不是单题`() {
        // 这份 HTML 只有 1 个 .TiMu，却有 12 道题；按 TiMu 切题会只拿到第 1 题
        assertTrue(html.contains("TiMu"))
        assertEquals(12, QuestionParser.parse(html).size)
    }

    @Test
    fun `题型分布与真实页面一致`() {
        val qs = QuestionParser.parse(html)
        assertEquals(4, qs.count { it.type == QType.SINGLE })
        assertEquals(3, qs.count { it.type == QType.FILL })
        assertEquals(5, qs.count { it.type == QType.JUDGE })
    }

    @Test
    fun `qid 唯一且都非空`() {
        val qids = QuestionParser.parse(html).map { it.qid }
        assertEquals(12, qids.toSet().size)
        assertTrue(qids.none { it.isBlank() })
    }

    @Test
    fun `第一题是单选且题干与选项正确`() {
        val q = QuestionParser.parse(html).first()
        assertEquals("405975556", q.qid)
        assertEquals(QType.SINGLE, q.type)
        assertEquals("单选题", q.typeLabel)
        assertTrue("题干应含关键词，实际=${q.stem}", q.stem.contains("图层蒙版中的白色区域"))
        assertEquals(4, q.options.size)
        assertEquals("A", q.options[0].letter)
        assertEquals("A", q.options[0].value)
        assertEquals("显示", q.options[0].text)
        assertEquals("D", q.options[3].letter)
    }

    @Test
    fun `题干不带题号和题型标记`() {
        val q = QuestionParser.parse(html).first()
        assertTrue("题干不该以数字题号开头：${q.stem}", !q.stem.startsWith("1."))
        assertTrue("题干不该含(单选题)：${q.stem}", !q.stem.contains("(单选题)"))
    }

    @Test
    fun `判断题只有两个选项且正文是对错`() {
        val judge = QuestionParser.parse(html).first { it.type == QType.JUDGE }
        assertEquals("405975563", judge.qid)
        assertEquals(2, judge.options.size)
        assertEquals("对", judge.options[0].text)
        assertEquals("错", judge.options[1].text)
        // 关键：判断题的 data 是 true/false，而页面显示的是 A/B
        assertEquals("true", judge.options[0].value)
        assertEquals("false", judge.options[1].value)
        assertEquals("A", judge.options[0].letter)
        assertEquals("B", judge.options[1].letter)
    }

    @Test
    fun `判断题按正文归一成对错而不是看字母`() {
        val judge = QuestionParser.parse(html).first { it.type == QType.JUDGE }
        assertEquals(true, QuestionParser.normalizeJudge(judge.options[0]))
        assertEquals(false, QuestionParser.normalizeJudge(judge.options[1]))
    }

    @Test
    fun `填空题没有选项但有格数`() {
        val fill = QuestionParser.parse(html).first { it.type == QType.FILL }
        assertEquals("405975560", fill.qid)
        assertTrue("填空题不该有选项", fill.options.isEmpty())
        assertTrue("填空题应有格数，实际=${fill.blanks}", fill.blanks >= 1)
    }

    @Test
    fun `多选题合成样例能被识别`() {
        // 真实账号里没翻到多选题，用最小结构验证 qtype=1 / role=checkbox 的识别路径
        val synthetic = """
            <div class="questionLi fontLabel" typename="多选题" id="question900001" data="900001">
              <h3 class="mark_name colorDeep"><span class="colorShallow">(多选题)</span><p>以下哪些属于蒙版类型？</p></h3>
              <input type="hidden" id="answertype900001" name="answertype900001" value="1">
              <div class="stem_answer qtDetail">
                <div class="clearfix answerBg" onclick="addChoice(this);" role="checkbox" aria-label="A 图层蒙版选择" qid="900001" qtype="1">
                  <span data="A" class="choice900001 num_option fl">A</span>
                  <div class="fl answer_p"><p>图层蒙版</p></div>
                </div>
                <div class="clearfix answerBg" onclick="addChoice(this);" role="checkbox" aria-label="B 剪贴蒙版选择" qid="900001" qtype="1">
                  <span data="B" class="choice900001 num_option fl">B</span>
                  <div class="fl answer_p"><p>剪贴蒙版</p></div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val q = QuestionParser.parse(synthetic).single()
        assertEquals(QType.MULTIPLE, q.type)
        assertEquals(2, q.options.size)
        assertEquals("剪贴蒙版", q.options[1].text)
    }

    @Test
    fun `空内容不崩`() {
        assertTrue(QuestionParser.parse("").isEmpty())
        assertTrue(QuestionParser.parse("<div>没有题目</div>").isEmpty())
    }

    @Test
    fun `选项摘要格式稳定便于校验`() {
        val q = QuestionParser.parse(html).first()
        val prompt = q.optionsForPrompt()
        assertTrue(prompt.startsWith("A. 显示"))
        assertEquals(4, prompt.lines().size)
    }

    @Test
    fun `未知正文不误判对错`() {
        val o = com.xxt.study.domain.QOption(letter = "A", value = "A", text = "以上都不是")
        assertNull(QuestionParser.normalizeJudge(o))
        assertNotNull(QuestionParser.parse(html).firstOrNull())
    }
}
