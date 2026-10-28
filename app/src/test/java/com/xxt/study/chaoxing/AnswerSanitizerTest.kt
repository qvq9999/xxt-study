package com.xxt.study.chaoxing

import com.xxt.study.core.ai.AnswerSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 答案清洗回归测试。
 *
 * 锁的是一个真实投诉：**AI 作答简答题时，有时把提示词写进作答框，而不是写答案**。
 * 成因是模型把"要求"当成了答案返回（尤其是单题重写，那条消息里几乎全是要求，
 * 而要求本身的字数常常刚好落在区间内），于是被当成合格答案回填。
 *
 * 这里的用例分两类：
 * 1. 该丢的必须丢 —— 各种形态的"要求复述 / 照抄题干 / JSON 残留"；
 * 2. 该留的必须留 —— 正常答案、带"答案："前缀的正常答案、含日常用词（如"综上所述"）的答案。
 *    第 2 类同样重要：清洗过狠会把好答案杀掉，用户看到的是"这题没答案"，一样是故障。
 */
class AnswerSanitizerTest {

    // ---------- 该丢的 ----------

    @Test
    fun `整段复述规则要丢弃`() {
        val raw = """
            逐条回应题干的每一问，写成能直接提交的完整段落，不要只列要点
            字数要求：150-250 字（按去掉空白后的字符数计算）
            请写到区间中上部，贴下限容易掉出范围
        """.trimIndent()
        assertNull("这就是提示词本身，不能回填", AnswerSanitizer.sanitize(raw))
    }

    @Test
    fun `单题重写的典型复述要丢弃`() {
        val raw = "请只输出答案正文：不要题号、不要引号、不要解释、不要 Markdown。字数要求：150-250 字"
        assertNull(AnswerSanitizer.sanitize(raw))
    }

    @Test
    fun `把 JSON 结构当答案返回要丢弃`() {
        val raw = """{"answers":[{"id":"q1","answer":["示例答案"]}]}"""
        assertNull(AnswerSanitizer.sanitize(raw))
    }

    @Test
    fun `照抄题干要丢弃`() {
        val stem = "结合材料，说明为什么说实践是检验真理的唯一标准"
        // 只把题干抄了一遍、后面补几个字凑长度
        assertNull(AnswerSanitizer.sanitize("$stem，这是很重要的问题。", stem))
        assertNull(AnswerSanitizer.sanitize(stem, stem))
    }

    @Test
    fun `剥掉外壳后只剩要求的也要丢弃`() {
        val raw = "```\n答案：\n只输出答案正文，不要题号\n```"
        assertNull(AnswerSanitizer.sanitize(raw))
    }

    // ---------- 该留的 ----------

    @Test
    fun `正常答案原样保留`() {
        val text = "实践是检验真理的唯一标准，因为真理的本性是主观认识与客观实际相符合，" +
            "而只有实践才能把主观认识与客观实际联系起来并加以对照。"
        assertEquals(text, AnswerSanitizer.sanitize(text))
    }

    @Test
    fun `只剥前缀和字数尾巴，内容不动`() {
        val raw = "答案：“实践是检验真理的唯一标准。（共 24 字）”。"
        val out = AnswerSanitizer.sanitize(raw)
        assertNotNull(out)
        assertTrue("前缀「答案：」要去掉", !out!!.startsWith("答案"))
        assertTrue("字数尾巴要去掉", !out.contains("共 24 字"))
        assertTrue("正文要留着", out.contains("检验真理"))
    }

    @Test
    fun `代码围栏包住答案时只取内容`() {
        val out = AnswerSanitizer.sanitize("```\n递归是函数调用自身的解决问题的方法。\n```")
        assertEquals("递归是函数调用自身的解决问题的方法。", out)
    }

    @Test
    fun `日常用词不算复述要求`() {
        // 「综上所述」「不要解释」这类词在正常答案里也会出现，不能因此杀掉答案
        val text = "综上所述，这一政策在实施过程中需要注意三点：一是信息公开，" +
            "二是程序正当，三是救济渠道畅通。只有三者兼备，制度才能真正落地。"
        assertEquals(text, AnswerSanitizer.sanitize(text))
    }

    @Test
    fun `短题干不该把正常答案误判成照抄`() {
        // 题干很短（"简述递归"）时答案以"递归"开头很自然，不能判成照抄
        val out = AnswerSanitizer.sanitize("递归是指一个函数直接或间接调用自身的过程。", "简述递归")
        assertNotNull(out)
        assertFalse(out!!.isBlank())
    }

    @Test
    fun `多行答案里混一行要求时只剔掉那一行`() {
        val raw = """
            字数要求：200-300 字
            实践是检验真理的唯一标准，因为只有实践能把主观认识与客观实际对照起来。
            离开实践，认识是否正确就无从验证，理论也会变成空洞的说辞。
        """.trimIndent()
        val out = AnswerSanitizer.sanitize(raw)
        assertNotNull("其余正文要保留", out)
        assertTrue("要求那一行要剔掉", !out!!.contains("字数要求"))
        assertTrue(out.contains("检验真理"))
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(AnswerSanitizer.sanitize(""))
        assertNull(AnswerSanitizer.sanitize("   \n  "))
    }
}
