package com.xxt.study.chaoxing

import com.xxt.study.core.net.VerifyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「验证码页无限循环」的回归测试。
 *
 * ## 当时的 bug 链
 *
 * ```
 * 用户点「已完成验证，继续」
 *   → VerifyGate.finish() → markPassed(): pending=null、冷却清 0
 *   → MainActivity 又调了一次 markPassed()（重复放行）
 *   → 业务收到 passed，立刻重试（冷却已经是 0，一点缓冲都没有）
 *   → 验证要是没真生效（验证码没提交 / 风控还没回落）→ 又撞
 *   → request() 又设 pending → 又弹出同一页 → 无限循环
 * ```
 *
 * 这里钉住三件事：
 * 1. 刚点完继续的 [VerifyGate.GUARD_MS] 内再撞风控，**不许再弹窗**；
 * 2. 弹窗已经在显示时，重复上报**不许把冷却无限延长**（旧实现每次都 startCooldown）；
 * 3. 用户主动要求重新验证时要能立刻弹，不能被保护期永久锁死。
 */
class VerifyLoopTest {

    private val t0 = 1_700_000_000_000L

    @Before
    fun reset() {
        VerifyGate.resetForTest()
    }

    @Test
    fun `第一次撞风控会弹窗`() {
        assertTrue(VerifyGate.request("https://mooc1.chaoxing.com/course/1", t0))
        assertNotNull(VerifyGate.pending.value)
    }

    @Test
    fun `弹窗期间重复上报不再弹窗`() {
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0)
        // 并发扫描时几十个请求会同时撞上，只有第一次生效
        assertFalse(VerifyGate.request("https://mooc1.chaoxing.com/b", t0 + 100))
        assertEquals("弹窗地址不该被后到的请求顶掉", "https://mooc1.chaoxing.com/a", VerifyGate.pending.value)
    }

    @Test
    fun `弹窗期间重复上报不会延长冷却`() {
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0)

        // 界面停留的这几秒里，后台可能还在零星撞风控
        VerifyGate.request("https://mooc1.chaoxing.com/b", t0 + 1000)
        VerifyGate.request("https://mooc1.chaoxing.com/c", t0 + 2000)

        // 冷却只该随时间流逝而减少；被重复上报重置的话，这里会是 COOLDOWN_MS（整 5 分钟）
        assertEquals(
            "重复上报绝不能把冷却重置——否则用户等 5 分钟永远等不完",
            VerifyGate.COOLDOWN_MS - 2000,
            VerifyGate.remainingCooldown(t0 + 2000),
        )
    }

    @Test
    fun `刚点完继续又撞风控不许再弹窗`() {
        // 用户点了「已完成验证，继续」
        VerifyGate.markPassed(t0)
        assertNull(VerifyGate.pending.value)

        // 业务立刻重试，又被拦
        val shown = VerifyGate.request("https://mooc1.chaoxing.com/x", t0 + 800)

        assertFalse("这就是无限循环的入口，必须拦住", shown)
        assertNull("不许再弹同一个页面", VerifyGate.pending.value)
    }

    @Test
    fun `保护期内再撞只给短冷却，别让用户等五分钟`() {
        VerifyGate.markPassed(t0)
        VerifyGate.request("https://mooc1.chaoxing.com/x", t0 + 1000)

        assertEquals(
            "验证没生效时给 1 分钟，让用户能自己再试一次",
            VerifyGate.GUARD_COOLDOWN_MS,
            VerifyGate.remainingCooldown(t0 + 1000),
        )
    }

    @Test
    fun `保护期过了以后恢复正常弹窗`() {
        VerifyGate.markPassed(t0)
        assertFalse("保护期内确实不弹", VerifyGate.request("https://mooc1.chaoxing.com/y", t0 + 1000))

        // 保护期和冷却都走完之后，同一门课再撞风控必须能正常弹出来，
        // 否则用户会被永久关在门外、连手动验证的机会都没有
        val afterAll = t0 + 1000 + VerifyGate.GUARD_COOLDOWN_MS + 1
        assertTrue(VerifyGate.request("https://mooc1.chaoxing.com/y", afterAll))
        assertNotNull(VerifyGate.pending.value)
    }

    @Test
    fun `用户主动要求重新验证时可以立刻弹`() {
        VerifyGate.markPassed(t0)
        assertFalse(VerifyGate.request("https://mooc1.chaoxing.com/z", t0 + 500))

        VerifyGate.approveAgain()
        assertTrue(
            "主动要求验证却被保护期挡住，用户会觉得按钮坏了",
            VerifyGate.request("https://mooc1.chaoxing.com/z", t0 + 600),
        )
    }

    @Test
    fun `保护期内的重复上报不会把冷却无限延长`() {
        VerifyGate.markPassed(t0)
        // 用户被拦住之后不甘心，每 10 秒手动刷新一次
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0 + 1_000)
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0 + 11_000)
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0 + 21_000)
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0 + 31_000)

        // 冷却必须还在"第一次被拦 + 60 秒"这个点上，不能被一次次刷新往后推
        assertEquals(
            "反复刷新不能把冷却一直往后推，否则永远等不到恢复",
            VerifyGate.GUARD_COOLDOWN_MS,
            VerifyGate.remainingCooldown(t0 + 1_000),
        )
        assertEquals(
            VerifyGate.GUARD_COOLDOWN_MS - 30_000,
            VerifyGate.remainingCooldown(t0 + 31_000),
        )
    }

    @Test
    fun `冷却结束的那一刻保护期必须已经过去`() {
        /*
         * 这两个时长的大小关系是个不变量，不是巧合。
         *
         * 保护期若 >= 冷却，用户等完冷却去刷新又会被保护期拦住、再进冷却，
         * 又是一个死循环——只是周期从"秒级"变成"分钟级"，同样出不来。
         */
        assertTrue(
            "GUARD_MS(${VerifyGate.GUARD_MS}) 必须小于 GUARD_COOLDOWN_MS(${VerifyGate.GUARD_COOLDOWN_MS})",
            VerifyGate.GUARD_MS < VerifyGate.GUARD_COOLDOWN_MS,
        )
    }

    @Test
    fun `等完冷却刷新就能重新验证`() {
        VerifyGate.markPassed(t0)
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0 + 1_000) // 被保护期拦，进 60 秒冷却
        VerifyGate.cancel()

        // 用户老老实实等到冷却结束再刷新：这次必须让他回到验证页
        val afterCooldown = t0 + 1_000 + VerifyGate.GUARD_COOLDOWN_MS + 1
        assertTrue(VerifyGate.request("https://mooc1.chaoxing.com/a", afterCooldown))
        assertNotNull(VerifyGate.pending.value)
    }

    @Test
    fun `取消只关窗口，冷却继续生效`() {
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0)
        VerifyGate.cancel()

        assertNull(VerifyGate.pending.value)
        assertTrue("取消不等于风控解除", VerifyGate.inCooldown(t0 + 1000))
    }

    @Test
    fun `接口地址会被换成首页避免白屏`() {
        VerifyGate.request("https://mooc1.chaoxing.com/api/courselist?view=json&api=1", t0)
        assertEquals(VerifyGate.FALLBACK_URL, VerifyGate.pending.value)
    }

    @Test
    fun `验证完成后冷却被清空`() {
        VerifyGate.request("https://mooc1.chaoxing.com/a", t0)
        assertTrue(VerifyGate.inCooldown(t0))

        VerifyGate.markPassed(t0 + 10)
        assertFalse("刚亲手验证过，不该还拦着", VerifyGate.inCooldown(t0 + 10))
    }
}
