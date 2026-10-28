package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.QuizParser
import com.xxt.study.data.repo.AnswerMapper
import com.xxt.study.domain.QOption
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节测验卷面解析 + 答案映射单测。
 *
 * 测验页的 DOM 结构和作业页完全不同（singleQuesId vs questionLi），
 * 两套解析器必须各测各的，混用会只解析出第一题或者一题都解不出。
 */
class QuizParserTest {

    private val html = """
        <html><body>
        <form id="form1" action="/work/doHomeWorkNew">
          <input type="hidden" name="workId" value="12345" />
          <input type="hidden" name="ktoken" value="KT" />
          <input type="hidden" name="enc" value="ENC" />
          <input type="hidden" name="answer123" value="" />

          <div class="singleQuesId" data="101">
            <div class="TiMu" data="0">
              <div class="Zy_TItle"><div class="clearfix"><i>1.</i><div class="fl">中国的首都是（单选题）</div></div></div>
              <ul>
                <li>A. 北京</li>
                <li>B. 上海</li>
                <li>C. 广州</li>
              </ul>
            </div>
          </div>

          <div class="singleQuesId" data="102">
            <div class="TiMu" data="1">
              <div class="Zy_TItle">下列属于水果的有（多选题）</div>
              <ul><li>A、苹果</li><li>B、香蕉</li><li>C、土豆</li></ul>
            </div>
          </div>

          <div class="singleQuesId" data="103">
            <div class="TiMu" data="3">
              <div class="Zy_TItle">地球是圆的（判断题）</div>
              <ul><li>A 对</li><li>B 错</li></ul>
            </div>
          </div>

          <div class="singleQuesId" data="104">
            <div class="TiMu" data="2">
              <div class="Zy_TItle">水的化学式是____（填空题）</div>
            </div>
          </div>
        </form>
        </body></html>
    """.trimIndent()

    @Test
    fun `四道题都解析出来且题型正确`() {
        val page = QuizParser.parse(html)
        assertEquals(4, page.questions.size)
        assertEquals(QType.SINGLE, page.questions[0].type)
        assertEquals(QType.MULTIPLE, page.questions[1].type)
        assertEquals(QType.JUDGE, page.questions[2].type)
        assertEquals(QType.FILL, page.questions[3].type)
    }

    @Test
    fun `qid 来自 data 属性`() {
        val page = QuizParser.parse(html)
        assertEquals(listOf("101", "102", "103", "104"), page.questions.map { it.qid })
    }

    @Test
    fun `题干去掉题号和题型标记`() {
        val page = QuizParser.parse(html)
        assertEquals("中国的首都是", page.questions[0].stem)
        assertEquals("下列属于水果的有", page.questions[1].stem)
    }

    @Test
    fun `选项字母与正文分离`() {
        val page = QuizParser.parse(html)
        val opts = page.questions[0].options
        assertEquals(3, opts.size)
        assertEquals("A", opts[0].letter)
        assertEquals("北京", opts[0].text)
        // 顿号、空格两种分隔都要认
        assertEquals("苹果", page.questions[1].options[0].text)
        assertEquals("对", page.questions[2].options[0].text)
    }

    @Test
    fun `隐藏域排除答题位`() {
        val page = QuizParser.parse(html)
        assertEquals("12345", page.form["workId"])
        assertEquals("KT", page.form["ktoken"])
        assertNull("answer 开头的不能预填", page.form["answer123"])
    }

    @Test
    fun `填空题没有选项`() {
        val page = QuizParser.parse(html)
        assertTrue(page.questions[3].options.isEmpty())
    }

    @Test
    fun `答案位计数作为填空数`() {
        val withBlanks = """
            <form>
            <div class="singleQuesId" data="201">
              <div class="TiMu" data="2"><div class="Zy_TItle">两空 __ __</div></div>
              <input name="answer201" value="" />
              <input name="answer201_1" value="" />
            </div>
            </form>
        """.trimIndent()
        val page = QuizParser.parse(withBlanks)
        assertEquals(2, page.questions.single().blanks)
    }

    @Test
    fun `字体解码函数会作用到题干和选项`() {
        // 只换内容不碰题型标记，否则标记被改坏就测不出解码是否生效
        val decoded = QuizParser.parse(html) { text ->
            text.replace("北京", "Beijing").replace("中国", "China")
        }
        assertEquals("China的首都是", decoded.questions[0].stem)
        assertEquals("Beijing", decoded.questions[0].options[0].text)
    }

    @Test
    fun `提交题型码与页面一致`() {
        assertEquals("0", QuizParser.typeCodeOf(QType.SINGLE))
        assertEquals("1", QuizParser.typeCodeOf(QType.MULTIPLE))
        assertEquals("2", QuizParser.typeCodeOf(QType.FILL))
        assertEquals("3", QuizParser.typeCodeOf(QType.JUDGE))
        assertEquals("4", QuizParser.typeCodeOf(QType.SHORT))
    }

    @Test
    fun `空页不炸`() {
        assertTrue(QuizParser.parse("").isEmpty)
        assertTrue(QuizParser.parse("<html><body>无题</body></html>").isEmpty)
    }

    // ---------------- 答案映射 ----------------

    private fun q(
        type: QType,
        letters: List<String> = listOf("A", "B", "C", "D"),
        texts: List<String> = listOf("甲", "乙", "丙", "丁"),
    ) = Question(
        qid = "1",
        number = 1,
        type = type,
        typeLabel = "",
        stem = "题干",
        options = letters.mapIndexed { i, l -> QOption(l, l, texts.getOrElse(i) { "" }) },
        blanks = 0,
    )

    @Test
    fun `单选取一个字母`() {
        val a = AnswerMapper.toAnswer(q(QType.SINGLE), listOf("B"))
        assertEquals("B", a.value)
    }

    @Test
    fun `单选给了不存在的字母要判无效`() {
        val a = AnswerMapper.toAnswer(q(QType.SINGLE), listOf("Z"))
        assertNull(a.value)
        assertNotNull(a.note)
    }

    @Test
    fun `多选按字母序拼接`() {
        val a = AnswerMapper.toAnswer(q(QType.MULTIPLE), listOf("C", "A"))
        assertEquals("AC", a.value)
    }

    @Test
    fun `判断题 true false 直接用`() {
        assertEquals("true", AnswerMapper.toAnswer(q(QType.JUDGE), listOf("true")).value)
        assertEquals("false", AnswerMapper.toAnswer(q(QType.JUDGE), listOf("false")).value)
    }

    @Test
    fun `判断题给字母时按选项正文换算`() {
        val judge = q(QType.JUDGE, letters = listOf("A", "B"), texts = listOf("对", "错"))
        assertEquals("true", AnswerMapper.toAnswer(judge, listOf("A")).value)
        assertEquals("false", AnswerMapper.toAnswer(judge, listOf("B")).value)
    }

    @Test
    fun `判断题模型直接说对错也能吃下`() {
        assertEquals("true", AnswerMapper.toAnswer(q(QType.JUDGE), listOf("正确")).value)
        assertEquals("false", AnswerMapper.toAnswer(q(QType.JUDGE), listOf("错误")).value)
    }

    @Test
    fun `填空多个空按顺序拼`() {
        val a = AnswerMapper.toAnswer(q(QType.FILL, letters = emptyList()), listOf("H2O", "水"))
        assertEquals("H2O 水", a.value)
    }

    @Test
    fun `模型什么都没给时不留假答案`() {
        assertNull(AnswerMapper.toAnswer(q(QType.SINGLE), emptyList()).value)
        assertNull(AnswerMapper.toAnswer(q(QType.FILL), listOf("  ")).value)
    }
}
