package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.StudyParsers
import com.xxt.study.domain.JobKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节树与任务点解析单测。
 *
 * fixture 结构按 SuperAutoStudy 的 Decode.java 反推（那份代码在真实账号上跑通过），
 * 两边的 selector 必须保持一致；平台改版先在这里复现。
 */
class StudyParsersTest {

    // ---------- 章节目录 ----------

    private val chapterHtml = """
        <html><body>
        <div class="chapter_unit">
          <div class="chapter_head">第一章 绪论</div>
          <ul>
            <li class="chapter_item">
              <div class="chapter_item_c" id="cur1001">
                <a class="clicktitle" title="1.1 课程导论">1.1 课程导论</a>
                <input class="knowledgeJobCount" value="3" />
              </div>
            </li>
            <li class="chapter_item">
              <div class="chapter_item_c" id="cur1002">
                <a class="clicktitle" title="1.2 基本概念">1.2 基本概念</a>
                <input class="knowledgeJobCount" value="2" />
              </div>
            </li>
          </ul>
        </div>
        <div class="chapter_unit">
          <div class="chapter_head">第二章 进阶</div>
          <ul>
            <li class="chapter_item">
              <div class="chapter_item_c" id="cur1003">
                <a class="clicktitle" title="2.1 尚未解锁">2.1 尚未解锁</a>
                <span class="bntHoverTips">学完上一章解锁</span>
              </div>
            </li>
          </ul>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `章节目录抽出知识点并识别未解锁`() {
        val tree = StudyParsers.parseCourseTree(chapterHtml)
        assertEquals(3, tree.nodes.size)
        assertEquals("1001", tree.nodes[0].id)
        assertEquals("1.1 课程导论", tree.nodes[0].title)
        assertEquals(3, tree.nodes[0].jobCount)
        assertEquals(2, tree.nodes[1].jobCount)
        assertTrue("有解锁提示应标记 hasLocked", tree.hasLocked)
        assertTrue("锁定章节节点自身也要标 locked", tree.nodes[2].locked)
        assertEquals(6, tree.totalJobs)
    }

    @Test
    fun `知识点 id 挂在 li 上也能认`() {
        val html = """
            <div class="chapter_unit"><ul>
              <li id="cur7777"><a class="clicktitle">另一种排版</a>
                <input class="knowledgeJobCount" value="1" /></li>
            </ul></div>
        """.trimIndent()
        val tree = StudyParsers.parseCourseTree(html)
        assertEquals(1, tree.nodes.size)
        assertEquals("7777", tree.nodes[0].id)
        assertEquals("另一种排版", tree.nodes[0].title)
    }

    @Test
    fun `空页不炸`() {
        assertTrue(StudyParsers.parseCourseTree("").nodes.isEmpty())
        assertFalse(StudyParsers.parseCourseTree("<html></html>").hasLocked)
    }

    // ---------- mArg 抠取 ----------

    @Test
    fun `mArg 嵌套对象不会被内层花括号截断`() {
        val html = """
            <script>
              mArg = {"defaults":{"cpi":"123"},"attachments":[{"jobid":"j1","property":{"name":"含 } 的标题"}}]};
              var other = {a:1};
            </script>
        """.trimIndent()
        val body = StudyParsers.extractMArg(html)
        assertNotNull(body)
        assertTrue("整个 attachments 都要在", body!!.contains("jobid"))
        assertTrue("字符串里的花括号不该被当结构", body.contains("含 } 的标题"))
        assertTrue(body.endsWith("}"))
    }

    @Test
    fun `没有 mArg 返回 null`() {
        assertNull(StudyParsers.extractMArg("<html><body>nothing</body></html>"))
    }

    @Test
    fun `空格不能被压掉`() {
        val html = """<script>mArg = {"defaults":{"cpi":"1"},"attachments":[{"jobid":"j1","job":true,"type":"video","mid":"m","property":{"name":"第 1 章 视频"}}]};</script>"""
        val jobs = StudyParsers.parseJobs(html)
        assertEquals("第 1 章 视频", jobs.jobs.single().name)
    }

    // ---------- 任务点 ----------

    private val cardsHtml = """
        <html><body>
        <script>
          mArg = {
            "defaults":{"knowledgeid":"9001","cpi":"123456","ktoken":"KT","cardid":"C1",
                        "defenc":"DEF","mtEnc":"MT","qnenc":"QN","reportTimeInterval":58},
            "attachments":[
              {"jobid":"job-1","type":"video","job":true,"objectId":"obj-1","mid":"mid-1","aid":"1",
               "otherInfo":"nodeId_9001-cpi_123","property":{"name":"1.1 视频"}},
              {"jobid":"job-2","type":"document","job":true,"jtoken":"jt-2","enc":"enc-2","mid":"mid-2",
               "otherInfo":"nodeId_9001-","property":{"objectid":"obj-2","name":"讲义 PDF"}},
              {"jobid":"job-3","type":"workid","job":true,"enc":"enc-3","otherInfo":"nodeId_9001-",
               "property":{"title":"章节小测"}},
              {"jobid":"job-4","type":"video","job":true,"isPassed":true,"objectId":"obj-4","mid":"mid-4",
               "otherInfo":"x","property":{"name":"已看完的视频"}},
              {"jobid":"job-5","type":"video","job":true,"objectId":"obj-5","otherInfo":"x",
               "property":{"name":"转码失败的视频"}},
              {"jobid":"job-6","type":"read","job":false,"jtoken":"jt-6",
               "otherInfo":"nodeId_9001-","property":{"title":"阅读材料","id":"obj-6","read":false}},
              {"jobid":"job-7","type":"read","job":false,"jtoken":"jt-7",
               "property":{"title":"已读材料","id":"obj-7","read":true}},
              {"jobid":"job-8","type":"vote","job":true,"property":{"title":"问卷"}},
              {"jobid":"job-1","type":"video","job":true,"objectId":"obj-1","mid":"mid-1",
               "otherInfo":"nodeId_9001-cpi_123","property":{"name":"1.1 视频"}}
            ]
          };
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `任务点按类型分流并过滤已完成与转码失败`() {
        val r = StudyParsers.parseJobs(cardsHtml)
        assertFalse(r.notOpen)
        assertEquals("重复的 job-1 只留一条；已过/缺 mid/问卷/已读全部剔除", 4, r.jobs.size)

        val video = r.jobs.first { it.jobId == "job-1" }
        assertEquals(JobKind.VIDEO, video.kind)
        assertEquals("obj-1", video.objectId)
        assertEquals("mid-1", video.mid)
        assertEquals("1.1 视频", video.displayName)

        val doc = r.jobs.first { it.jobId == "job-2" }
        assertEquals(JobKind.DOCUMENT, doc.kind)
        assertEquals("obj-2", doc.objectId)
        assertEquals("jt-2", doc.jtoken)

        val quiz = r.jobs.first { it.jobId == "job-3" }
        assertEquals(JobKind.QUIZ, quiz.kind)
        assertEquals("enc-3", quiz.enc)
        assertEquals("章节小测", quiz.displayName)

        val read = r.jobs.first { it.jobId == "job-6" }
        assertEquals(JobKind.READ, read.kind)
        assertEquals("obj-6", read.objectId)

        assertTrue("已看完的不该再出现", r.jobs.none { it.jobId == "job-4" })
        assertTrue("转码失败的不该再出现", r.jobs.none { it.jobId == "job-5" })
        assertTrue("问卷不该处理", r.jobs.none { it.jobId == "job-8" })
    }

    @Test
    fun `defaults 全部字段到位`() {
        val d = StudyParsers.parseJobs(cardsHtml).defaults
        assertEquals("9001", d.knowledgeId)
        assertEquals("123456", d.cpi)
        assertEquals("KT", d.ktoken)
        assertEquals("C1", d.cardId)
        assertEquals("DEF", d.defenc)
        assertEquals("MT", d.mtEnc)
        assertEquals("QN", d.qnenc)
        assertEquals(58, d.reportInterval)
    }

    @Test
    fun `章节未开放要标记而不是报错`() {
        val r = StudyParsers.parseJobs("<html><body>章节未开放</body></html>")
        assertTrue(r.notOpen)
        assertTrue(r.jobs.isEmpty())
    }

    @Test
    fun `没有 mArg 时返回空列表`() {
        val r = StudyParsers.parseJobs("<html><body><div>nothing</div></body></html>")
        assertTrue(r.jobs.isEmpty())
        assertEquals(60, r.defaults.reportInterval)
    }
}
