package com.xxt.study.chaoxing

import com.xxt.study.data.remote.chaoxing.VideoSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频上报签名单测。
 *
 * 期望值不是从 Kotlin 自己跑出来的（那样错了也照样通过），
 * 而是用一份独立的 Python 实现按 Java 原文算出来的，两边对上才算数。
 * 签名错了的现场表现是「HTTP 403」，从现象反推极难，所以必须在这里钉死。
 */
class VideoSignTest {

    @Test
    fun `enc 与独立实现逐字节一致`() {
        assertEquals(
            "da6533e7b45f4113f6fae628c77f1c4d",
            VideoSign.enc("100", "200", "job-1", "obj-1", playingTime = 0, duration = 600),
        )
        assertEquals(
            "bba09e1ae3a9b738eb4647298625e835",
            VideoSign.enc("1234567", "88888888", "work-99", "obj-abc", playingTime = 180, duration = 1200),
        )
    }

    @Test
    fun `秒与毫秒不能混用`() {
        // duration 传秒和传毫秒必须得到不同签名，否则说明乘法漏了
        val a = VideoSign.enc("1", "2", "j", "o", 10, 600)
        val b = VideoSign.enc("1", "2", "j", "o", 10, 600_000)
        assertTrue(a != b)
    }

    @Test
    fun `logUrl 拼全所有服务端必需参数`() {
        val url = VideoSign.logUrl(
            cpi = "123456",
            dtoken = "DT",
            clazzId = "777",
            courseId = "888",
            uid = "999",
            jobId = "job-1",
            objectId = "obj-1",
            otherInfo = "nodeId_1-cpi_123456",
            playingTime = 120,
            duration = 600,
            dtype = "Video",
            rt = "0.9",
            timestamp = 1700000000000L,
        )
        for (piece in listOf(
            "/multimedia/log/a/123456/DT",
            "clazzId=777",
            "playingTime=120",
            "duration=600",
            "clipTime=0_600",
            "objectId=obj-1",
            "jobid=job-1",
            "userid=999",
            "isdrag=3",
            "view=pc",
            "dtype=Video",
            "rt=0.9",
            "_t=1700000000000",
        )) {
            assertTrue("缺少参数 $piece", url.contains(piece))
        }
        assertTrue("otherInfo 不该被 URL 编码", url.contains("otherInfo=nodeId_1-cpi_123456"))
    }

    @Test
    fun `otherInfo 自带的 courseId 不重复追加`() {
        val withCourse = VideoSign.logUrl(
            "1", "DT", "2", "3", "4", "j", "o",
            otherInfo = "nodeId_1-cpi_2&courseId=3", playingTime = 0, duration = 60,
            dtype = "Video", rt = "1", timestamp = 0,
        )
        assertEquals(1, Regex("courseId=").findAll(withCourse).count())
    }

    @Test
    fun `otherInfo 缺 courseId 时补一个`() {
        val without = VideoSign.logUrl(
            "1", "DT", "2", "3", "4", "j", "o",
            otherInfo = "nodeId_1-cpi_2", playingTime = 0, duration = 60,
            dtype = "Video", rt = "1", timestamp = 0,
        )
        assertTrue(without.contains("otherInfo=nodeId_1-cpi_2&courseId=3&"))
    }

    @Test
    fun `每轮声称的秒数跟倍速成正比`() {
        repeat(50) {
            // 新语义：tick = 真实 60 秒 × 平台倍速（±2% 抖动）
            assertTrue("2 倍速下 60 秒应前进约 120 秒", VideoSign.tickSeconds(2.0) in 117..123)
            assertTrue("1 倍速下应前进约 60 秒", VideoSign.tickSeconds(1.0) in 58..62)
        }
    }
}
