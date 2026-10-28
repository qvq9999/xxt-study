package com.xxt.study.chaoxing

import com.xxt.study.data.remote.web.ScanProbes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描探针脚本的回归测试。
 *
 * 这些脚本是"课程扫描结果准不准"的全部依据，出问题时表现为
 * **首次启动正常、之后每次刷新又慢又错**（或者反过来，全课超时），肉眼极难发现，
 * 所以把关键约束钉死。
 */
class ScanProbesTest {

    @Test
    fun `两份探针都必须先看旧文档标记`() {
        for (js in listOf(ScanProbes.middle(), ScanProbes.rows())) {
            assertTrue("缺少旧文档标记判断，池化复用时会读到上一门课的文档", js.contains("window.__xxtNav"))
            // 必须在登录判定之前就把旧文档拦掉
            assertTrue(
                "旧文档判断要在 LOGIN 之前，否则会拿上一门课的页面下结论",
                js.indexOf("__xxtNav") < js.indexOf("'LOGIN'"),
            )
        }
    }

    @Test
    fun `标记带时间戳且只写一个变量`() {
        assertEquals(
            "标记必须是当前时间，探针靠有效期兜住「迟到落错文档」的反噬",
            "(function(){try{window.__xxtNav=Date.now();}catch(e){}return '1';})()",
            ScanProbes.MARK,
        )
    }

    @Test
    fun `旧文档守卫必须带有效期`() {
        for (js in listOf(ScanProbes.middle(), ScanProbes.rows())) {
            // 没有有效期 → 标记一旦迟到落到新文档，那一页就永远等不到结果（整次扫描全部超时）
            assertTrue("缺少有效期判断", js.contains("Date.now()-__t)<5000"))
        }
    }

    @Test
    fun `空文档不能当结论`() {
        for (js in listOf(ScanProbes.middle(), ScanProbes.rows())) {
            // 新建 WebView 的初始文档是 about:blank，complete 且没有目标元素，
            // 不挡掉就会把每门课都判成"没有 workEnc"
            assertTrue("缺少 about:blank 守卫", js.contains("about:blank"))
        }
    }

    @Test
    fun `探针不许再靠 URL 猜页面类型`() {
        // 踩过的坑：要求 URL 含 studentcourse / work 才下结论，
        // 结果真实落地页不含这些词，整次扫描变成"全部等待超时"。
        for (js in listOf(ScanProbes.middle(), ScanProbes.rows())) {
            assertFalse("不许再按 studentcourse 判断页面类型", js.contains("studentcourse"))
            assertFalse("不许再按 work 路径判断页面类型", js.contains("h.indexOf('work')"))
            assertFalse("不许再要求 URL 里出现 courseId", js.contains("indexOf(courseId"))
        }
    }

    @Test
    fun `课程页探针优先返回 workEnc`() {
        val js = ScanProbes.middle()
        assertTrue(js.contains("getElementById('workEnc')"))
        assertTrue(js.contains("'OK|'"))
        // workEnc 命中要在"没有 workEnc"的结论之前
        assertTrue(js.indexOf("'OK|'") < js.indexOf("'NONE|'"))
    }

    @Test
    fun `作业探针优先读到行 其次才判空`() {
        val js = ScanProbes.rows()
        assertTrue(js.contains("li[data]"))
        assertTrue("没行且已 complete 才返回 EMPTY", js.indexOf("'EMPTY'") < js.indexOf("JSON.stringify"))
        // 只有 li 数量为 0 的分支里才允许给 EMPTY
        val emptyIdx = js.indexOf("return 'EMPTY';")
        val hasRowsIdx = js.indexOf("var out=[];")
        assertTrue("EMPTY 判定必须在取行之前", emptyIdx in 1 until hasRowsIdx)
    }

    @Test
    fun `未就绪时返回 WAIT 而不是空串`() {
        for (js in listOf(ScanProbes.middle(), ScanProbes.rows())) {
            assertTrue("要带上落地 URL 与 readyState，超时后才能定位", js.contains("'WAIT|'+h+'|'+document.readyState"))
            assertFalse("不能再出现裸 return ''，否则超时信息里什么都没有", js.contains("return '';"))
        }
    }
}
