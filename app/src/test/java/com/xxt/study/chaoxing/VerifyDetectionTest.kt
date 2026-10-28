package com.xxt.study.chaoxing

import com.xxt.study.core.net.VerifyGate
import com.xxt.study.data.remote.chaoxing.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风控页识别 + 冷却。
 *
 * 识别这一条是被真实页面打过脸的：学习通给的是「操作异常，请输入图片中的验证码」，
 * 旧词表里只有「请输入验证码」，中间多了"图片中的"四个字就整条失效，
 * 验证窗口永远弹不出来。所以这里必须用真实文案做断言。
 */
class VerifyDetectionTest {

    @Test
    fun `真实文案必须能识别`() {
        assertTrue(
            "学习通实际下发的是这句",
            Parsers.looksLikeVerify("<div>操作异常，请输入图片中的验证码</div>"),
        )
    }

    @Test
    fun `常见变体都能识别`() {
        listOf(
            "请输入验证码",
            "请填写验证码后继续",
            "拖动下方滑块完成验证",
            "请完成人机验证",
            "安全验证",
            "验证码已失效，请重新获取",
            "captcha",
        ).forEach {
            assertTrue("没识别出来：$it", Parsers.looksLikeVerify("<p>$it</p>"))
        }
    }

    @Test
    fun `正常页面不能被误判`() {
        listOf(
            "验证码登录",
            "验证码技术",
            "本节课讲解图形验证码的原理",
            "",
        ).forEach {
            assertFalse("不该误判：$it", Parsers.looksLikeVerify(it))
        }
    }

    @Test
    fun `探针脚本同样允许插词匹配`() {
        // 与 Parsers.VERIFY_RE 保持一致的证据：都带 .{0,8} 这种"中间可以有几个字"的写法
        val js = com.xxt.study.data.remote.web.ScanProbes.middle()
        assertTrue(js.contains(".{0,8}验证码"))
    }

    @Test
    fun `冷却期计算`() {
        val t0 = 1_000_000L
        VerifyGate.startCooldown(t0)
        assertEquals(VerifyGate.COOLDOWN_MS, VerifyGate.remainingCooldown(t0))
        assertTrue(VerifyGate.inCooldown(t0))
        assertEquals(0L, VerifyGate.remainingCooldown(t0 + VerifyGate.COOLDOWN_MS))
        assertFalse(VerifyGate.inCooldown(t0 + VerifyGate.COOLDOWN_MS))
    }

    @Test
    fun `冷却提示带剩余时间`() {
        val t0 = 2_000_000L
        VerifyGate.startCooldown(t0)
        val text = VerifyGate.cooldownText(t0)
        assertTrue("要说明还剩多久", text != null && text.contains("冷却"))
        // 到底了就没有提示
        assertNull(VerifyGate.cooldownText(t0 + VerifyGate.COOLDOWN_MS))
    }

    @Test
    fun `验证通过后冷却立刻解除`() {
        val t0 = 3_000_000L
        VerifyGate.startCooldown(t0)
        assertTrue(VerifyGate.inCooldown(t0))
        // 用户刚刚亲手完成验证，不该再拦着
        VerifyGate.markPassed()
        assertFalse(VerifyGate.inCooldown(t0))
    }
}
