package com.xxt.study.chaoxing

import com.xxt.study.core.ai.AiPrompt
import com.xxt.study.core.ai.LengthRule
import com.xxt.study.domain.QOption
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词回归测试。
 *
 * 答案质量几乎全由提示词决定，而它以前是 `AiClient` 里的私有字符串，改坏了没人发现。
 * 这里把三条硬约束钉住：紧扣题干、带上字数区间、重写时给出具体字数差。
 */
class AiPromptTest {

    private fun q(
        stem: String,
        type: QType = QType.SHORT,
        options: List<QOption> = emptyList(),
    ) = Question(
        qid = "q1",
        number = 1,
        type = type,
        typeLabel = if (type == QType.SHORT) "论述题" else "单选题",
        stem = stem,
        options = options,
        blanks = 0,
    )

    @Test
    fun `简答题的字数要求要写进题目字段`() {
        val prompt = AiPrompt.user(listOf(q("结合材料谈谈你的看法（150-250字）")))
        assertTrue("没有把字数区间带进 prompt", prompt.contains("150-250 字"))
        assertTrue("缺少 length 字段", prompt.contains("length"))
    }

    @Test
    fun `没有字数要求的简答题不塞 length`() {
        val prompt = AiPrompt.user(listOf(q("简述什么是递归")))
        assertTrue(!prompt.contains("\"length\""))
    }

    @Test
    fun `选择题不带 length 但要带选项`() {
        val prompt = AiPrompt.user(
            listOf(
                q(
                    "下列说法正确的是",
                    type = QType.SINGLE,
                    options = listOf(
                        QOption("A", "A", "甲"),
                        QOption("B", "B", "乙"),
                    ),
                ),
            ),
        )
        assertTrue(prompt.contains("A. 甲"))
        assertTrue(!prompt.contains("\"length\""))
    }

    @Test
    fun `系统提示要求紧扣题干`() {
        assertTrue(AiPrompt.SYSTEM.contains("紧扣题干"))
        assertTrue("要禁止空泛套话", AiPrompt.SYSTEM.contains("套话"))
    }

    @Test
    fun `规则里要求遵守字数区间`() {
        val prompt = AiPrompt.user(listOf(q("写一段（200-300字）")))
        assertTrue(prompt.contains("200-300 字"))
        assertTrue("要说明按去空白字符计算", prompt.contains("去掉空白"))
    }

    @Test
    fun `重写提示要报出上次的字数`() {
        val text = AiPrompt.rewrite(q("谈谈你的看法（150-250字）"), LengthRule(150, 250), "字".repeat(98))
        assertTrue(text.contains("98 字"))
        assertTrue(text.contains("150-250 字"))
        assertTrue("要给出建议目标", text.contains("建议写到"))
    }

    @Test
    fun `建议字数取区间中上部`() {
        // 贴下限最容易因数标点差异掉出范围，所以往中间偏上取
        assertTrue(AiPrompt.targetWords(LengthRule(150, 250)) in 200..240)
        assertTrue(AiPrompt.targetWords(LengthRule(300, null)) >= 300)
        assertTrue(AiPrompt.targetWords(LengthRule(null, 500)) <= 500)
        assertTrue(AiPrompt.targetWords(LengthRule(null, null)) in 150..300)
    }
}
