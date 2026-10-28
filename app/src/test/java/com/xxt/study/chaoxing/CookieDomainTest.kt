package com.xxt.study.chaoxing

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OkHttp Cookie 域名匹配规则。
 *
 * 这组测试的来源是一次真实故障：`CookieStore.importHeader` 用 `".$host"` 构造 domain，
 * 而 OkHttp 的 `Cookie.Builder.domain()` **直接拒绝带前导点的域名**，抛
 * IllegalArgumentException。异常被 runCatching 吞掉，于是每条 Cookie 都构建失败、
 * 导入 0 条 → 登录通过了但一个 Cookie 都带不上 → 服务端返回登录页 → 界面永远停在登录页。
 *
 * 现场表现是"登录成功了却进不去主界面"，从现象完全看不出是域名格式问题，
 * 所以把规则钉死在这里，别再退回去。
 */
class CookieDomainTest {

    private fun cookie(domain: String, hostOnly: Boolean = false) = Cookie.Builder()
        .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
        .path("/")
        .name("_uid")
        .value("123")
        .build()

    @Test
    fun `带前导点的域名会直接抛异常_这就是登录卡住的根因`() {
        var thrown = false
        try {
            cookie(".chaoxing.com")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("OkHttp 不接受带前导点的域名，importHeader 的 domain 不能加点", thrown)
    }

    @Test
    fun `父域 cookie 覆盖所有学习通子域`() {
        val c = cookie("chaoxing.com")
        for (host in listOf(
            "i.chaoxing.com",
            "mooc1.chaoxing.com",
            "mooc2-ans.chaoxing.com",
            "mooc1-api.chaoxing.com",
            "passport2.chaoxing.com",
            "mooc1-1.chaoxing.com",
        )) {
            assertTrue("$host 应该带上父域 cookie", c.matches("https://$host/base".toHttpUrl()))
        }
    }

    @Test
    fun `父域 cookie 不会发给别的站`() {
        val c = cookie("chaoxing.com")
        assertFalse(c.matches("https://api.deepseek.com/v1/chat/completions".toHttpUrl()))
        assertFalse(c.matches("https://evil-chaoxing.com.cn/x".toHttpUrl()))
    }

    @Test
    fun `hostOnly 的裸域不匹配子域`() {
        val c = cookie("i.chaoxing.com", hostOnly = true)
        assertTrue(c.matches("https://i.chaoxing.com/base".toHttpUrl()))
        assertFalse(c.matches("https://mooc1.chaoxing.com/x".toHttpUrl()))
    }
}
