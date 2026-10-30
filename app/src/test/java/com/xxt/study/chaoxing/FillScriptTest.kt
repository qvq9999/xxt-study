package com.xxt.study.chaoxing

import com.xxt.study.data.remote.web.FillItem
import com.xxt.study.data.remote.web.FillScript
import com.xxt.study.domain.QType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回填脚本生成。
 *
 * 这段 JS 直接改页面状态，出错的后果是"看起来填了、提交时为空"，
 * 所以关键约束都要钉死：qid 从哪取、用 data 还是显示字母、必须 click()。
 */
class FillScriptTest {

    @Test
    fun `空列表不执行任何操作`() {
        assertEquals("0", FillScript.build(emptyList()))
    }

    @Test
    fun `单选生成 choice 项且值带引号`() {
        val js = FillScript.build(listOf(FillItem("405975556", QType.SINGLE, listOf("A"))))
        assertTrue(js.contains("qid:\"405975556\""))
        assertTrue(js.contains("kind:\"choice\""))
        assertTrue(js.contains("values:[\"A\"]"))
    }

    @Test
    fun `多选把多个字母都放进 values`() {
        val js = FillScript.build(listOf(FillItem("1", QType.MULTIPLE, listOf("A", "C"))))
        assertTrue(js.contains("values:[\"A\",\"C\"]"))
    }

    @Test
    fun `判断题用 true false 而不是字母`() {
        val js = FillScript.build(listOf(FillItem("2", QType.JUDGE, listOf("true"))))
        assertTrue(js.contains("values:[\"true\"]"))
        assertTrue("判断题不能走文本框分支", js.contains("kind:\"choice\""))
    }

    @Test
    fun `填空和简答走文本分支`() {
        for (t in listOf(QType.FILL, QType.SHORT)) {
            val js = FillScript.build(listOf(FillItem("3", t, listOf("水的化学式是 H2O"))))
            assertTrue(js.contains("kind:\"text\""))
            assertTrue(js.contains("H2O"))
        }
    }

    @Test
    fun `脚本按 qid 找选项框并读 span 的 data`() {
        val js = FillScript.build(listOf(FillItem("9", QType.SINGLE, listOf("B"))))
        // 选择器必须限定 qid，否则会把别的题一起点掉
        assertTrue(js.contains("'.answerBg[qid=\"'+it.qid+'\" ]'".replace(" ]", "]")) || js.contains(".answerBg[qid="))
        // 答案比对必须用 span[data]：判断题的 data 是 true/false，显示字母是 A/B
        assertTrue(js.contains("span[data]"))
        assertTrue(js.contains("getAttribute('data')"))
        // 必须 click，不能只改 class
        assertTrue(js.contains(".click()"))
    }

    @Test
    fun `填空写 id 为 answer+qid 的输入框并派发事件`() {
        val js = FillScript.build(listOf(FillItem("77", QType.FILL, listOf("x"))))
        assertTrue(js.contains("getElementById('answer'+qid)"))
        assertTrue(js.contains("dispatchEvent(new Event('input'"))
    }

    @Test
    fun `qid 里的引号会被转义，不会截断脚本`() {
        val js = FillScript.build(listOf(FillItem("a\"b", QType.SINGLE, listOf("A"))))
        assertTrue(js.contains("a\\\"b"))
    }

    @Test
    fun `多题按顺序拼成一个脚本`() {
        val js = FillScript.build(
            listOf(
                FillItem("1", QType.SINGLE, listOf("A")),
                FillItem("2", QType.MULTIPLE, listOf("B", "C")),
                FillItem("3", QType.FILL, listOf("答案")),
            ),
        )
        assertTrue(js.contains("qid:\"1\""))
        assertTrue(js.contains("qid:\"2\""))
        assertTrue(js.contains("qid:\"3\""))
    }

    @Test
    fun `文本题必须优先走 UEditor 而不是直接改 textarea`() {
        // 用户实测：简答题显示"已回填"但输入框没字——学习通简答题用的是 UEditor，
        // 编辑区在 iframe / contenteditable 里，隐藏 textarea 只是同步用的一份影子。
        val js = FillScript.build(listOf(FillItem("88", QType.SHORT, listOf("答案"))))
        assertTrue("要先查 UEditor 实例", js.contains("UE.instants"))
        assertTrue("要调 setContent", js.contains("setContent"))
        assertTrue("要兼容 contenteditable", js.contains("contenteditable"))
        assertTrue("要兼容 iframe 编辑区", js.contains("contentDocument"))
        assertTrue("最后才兜底原生控件", js.contains("getElementById('answer'+qid)"))
    }

    @Test
    fun `回执里的 MISSED 能被解析出来`() {
        org.junit.Assert.assertEquals(
            setOf("11", "22"),
            com.xxt.study.feature.work.parseMissed("FILLED:3/5 MISSED:11,22"),
        )
        assertTrue(com.xxt.study.feature.work.parseMissed("FILLED:5/5 MISSED:").isEmpty())
        assertTrue(com.xxt.study.feature.work.parseMissed(null).isEmpty())
    }

    @Test
    fun `部分填空的题能被解析出来`() {
        org.junit.Assert.assertEquals(
            setOf("33"),
            com.xxt.study.feature.work.parsePartial("FILLED:3/5 MISSED:11,22 PARTIAL:33"),
        )
        assertTrue(com.xxt.study.feature.work.parsePartial("FILLED:5/5 MISSED: PARTIAL:").isEmpty())
        assertTrue(com.xxt.study.feature.work.parsePartial(null).isEmpty())
    }

    @Test
    fun `多空填空把空数带进脚本`() {
        val js = FillScript.build(listOf(FillItem("405955126", QType.FILL, listOf("a", "b", "c", "d"), 4)))
        assertTrue("空数要透传给 JS", js.contains("blanks:4"))
        assertTrue("一空一个答案，不能拼成一句", js.contains("values:[\"a\",\"b\",\"c\",\"d\"]"))
        // 定位必须按"第N空"那一块走，且要认 answerEditor<qid><n> 的 id 规则
        assertTrue(js.contains("div.Answer"))
        assertTrue(js.contains("answerEditor"))
    }

    @Test
    fun `多空填空绝不用“含 qid 就算命中”的模糊匹配`() {
        // 旧实现的根因：按 hint.indexOf(qid) 找编辑器，四个空全命中第 1 个，
        // 于是所有答案糊进第 1 空。模糊匹配只允许留在单值兜底路径里。
        val js = FillScript.build(listOf(FillItem("405955126", QType.FILL, listOf("a", "b"), 2)))
        assertTrue("必须按空序号精确匹配", js.contains("answerEditor'+qid"))
        assertTrue("多空分支要按 i 取第 i 个答案", js.contains("values[i]"))
        assertTrue("剩下的空要报出来", js.contains("PARTIAL:"))
    }
}
