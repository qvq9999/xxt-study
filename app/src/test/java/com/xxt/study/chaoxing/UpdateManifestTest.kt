package com.xxt.study.chaoxing

import com.xxt.study.core.update.UpdateAvailable
import com.xxt.study.core.update.UpdateManifest
import com.xxt.study.core.update.UpdateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置更新：清单解析 + 更新源顺序 + 日志简写。
 *
 * 这几块全是纯函数，正好能在没有真机的情况下锁死行为——
 * 更新功能最怕的就是"发出去的包自己不会更新"。
 */
class UpdateManifestTest {

    private val good = """
    {
      "versionCode": 16,
      "versionName": "0.6.0",
      "sizeBytes": 2590000,
      "sha256": "9f2c1b7a5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3",
      "notes": ["作业状态修正", "内置更新", "版本升级清一次缓存"],
      "packageName": "com.xxt.study",
      "url": "https://github.com/qvq9999/xxt-study/releases/download/v0.6.0/app-release.apk"
    }
    """.trimIndent()

    @Test
    fun `正常清单能解析出全部字段`() {
        val m = UpdateManifest.parse(good)
        assertNotNull(m)
        assertEquals(16, m!!.versionCode)
        assertEquals("0.6.0", m.versionName)
        assertEquals(2_590_000L, m.sizeBytes)
        assertEquals(3, m.notes.size)
        assertTrue(m.url.endsWith("app-release.apk"))
    }

    @Test
    fun `镜像返回的 HTML 错误页不算清单`() {
        // 镜像挂掉/限流时经常是 200 + 一张 HTML 页面，按 JSON 解析会抛异常
        assertNull(UpdateManifest.parse("<html><body>502 Bad Gateway</body></html>"))
        assertNull(UpdateManifest.parse(""))
    }

    @Test
    fun `版本号缺失或为 0 的清单作废`() {
        assertNull(UpdateManifest.parse("""{"versionName":"9.9.9","url":"https://a/b.apk"}"""))
        assertNull(
            UpdateManifest.parse(
                """{"versionCode":0,"versionName":"9.9.9","url":"https://a/b.apk"}"""
            )
        )
    }

    @Test
    fun `包名对不上就不采用`() {
        val other = good.replace("com.xxt.study", "com.evil.app")
        assertNull("别的应用的清单不能拿来更新", UpdateManifest.parse(other))
    }

    @Test
    fun `没有下载地址的清单作废`() {
        val noUrl = """{"versionCode":16,"versionName":"0.6.0","url":""}"""
        assertNull(UpdateManifest.parse(noUrl))
    }

    @Test
    fun `更新日志最多三条且截断`() {
        val m = UpdateManifest(
            versionCode = 1,
            versionName = "1.0",
            url = "https://a/b.apk",
            notes = listOf(
                "第一条",
                "",
                "  第三条  ",
                "这一条特别特别长，长到足以把弹窗撑成两屏，必须在展示前就砍掉一半以上的字数才行",
                "第四条不该出现",
            ),
        )
        val notes = m.shortNotes()
        assertEquals("最多三条", 3, notes.size)
        assertEquals("空行被丢掉、首尾空白被去掉", "第三条", notes[1])
        assertTrue("超长的一条要带省略号", notes[2].endsWith("…"))
        assertTrue("截断后不该超过 36 字", notes[2].length <= 36)
        assertTrue("第四条被丢掉", notes.none { it.startsWith("第四条") })
    }

    @Test
    fun `大小格式化`() {
        assertEquals("未知大小", UpdateAvailable.formatBytes(0))
        assertEquals("2.5 MB", UpdateAvailable.formatBytes(2_621_440))
        assertEquals("512 KB", UpdateAvailable.formatBytes(524_288))
    }

    // ---------- 更新源 ----------

    @Test
    fun `默认源是国内镜像，GitHub 只做兜底`() {
        val routes = UpdateSource.manifestRoutes()
        assertTrue("至少有一个镜像", routes.size >= 2)
        // 2026-10-28 实测：ghproxy.net / gh-proxy.com 返回 200，ghfast.top 连不上
        assertEquals("第一个必须是能连上的镜像", "ghproxy.net", routes.first().name)
        assertEquals("最后一个才是直连", "GitHub", routes.last().name)
        assertTrue(routes.last().url.startsWith("https://raw.githubusercontent.com/"))
        // 镜像地址 = 前缀 + 原始地址
        assertTrue(routes.first().url.endsWith(UpdateSource.ORIGINAL_MANIFEST))
    }

    @Test
    fun `安装包也优先走镜像`() {
        val apk = "https://github.com/qvq9999/xxt-study/releases/download/v0.6.0/app.apk"
        val routes = UpdateSource.downloadRoutes(apk)
        assertEquals(UpdateSource.MIRRORS.size + 1, routes.size)
        assertEquals("https://ghproxy.net/$apk", routes.first().url)
        assertEquals(apk, routes.last().url)
    }

    @Test
    fun `没有下载地址时不会造出空路由`() {
        assertTrue(UpdateSource.downloadRoutes("   ").isEmpty())
    }

    @Test
    fun `源名只取域名，便于在弹窗里说明走了哪个源`() {
        assertEquals("ghfast.top", UpdateSource.hostOf("https://ghfast.top/"))
        assertEquals("ghproxy.net", UpdateSource.hostOf("https://ghproxy.net"))
    }

    @Test
    fun `版本名去掉 debug 后缀`() {
        assertEquals("0.6.0", UpdateSource.cleanVersionName("0.6.0-debug"))
        assertEquals("0.6.0", UpdateSource.cleanVersionName("0.6.0"))
    }
}
