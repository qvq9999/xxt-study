package com.xxt.study.core.net

import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "需要人工验证"的总开关 + 风控冷却。
 *
 * 学习通的风控会突然要求输验证码/拖滑块。这种页面用 OkHttp 是过不去的——
 * 必须有人在一个真实的浏览上下文里点一下，而且验证结果以 Cookie 的形式落下来。
 * 所以流程是：
 *
 * ```
 * 请求被风控 → request(页面地址) → 界面弹出原版页面 → 用户手动完成
 *            → 把 WebView 的 Cookie 导回 CookieStore → markPassed() → 各业务自动重试
 * ```
 *
 * 为什么用全局单例而不是挂在某个 ViewModel 上：触发点分散在扫描（隐藏 WebView）、
 * 刷课（OkHttp）、课程列表（OkHttp）三处，而界面只有一个（MainActivity 的浮层）。
 * 谁先撞上谁负责举手，界面只关心"现在要不要弹"。
 *
 * ## 冷却
 *
 * 撞上一次风控说明"最近打得太密"。如果验证完立刻继续按原节奏打，大概率马上又撞。
 * 所以 request 的同时进入 [COOLDOWN_MS] 冷却，期间所有业务入口直接拒绝、一个请求都不发。
 * 这是止损，不是惩罚——风控是按请求特征累积的，越刷越紧。
 */
object VerifyGate {

    /** 冷却时长：5 分钟。够风控计数回落，又不至于让人等到以为卡死 */
    const val COOLDOWN_MS = 5 * 60_000L

    /**
     * 「验证后保护期」：用户点了"已完成验证"之后这么久之内再撞风控，
     * **不再弹同一个页面**，只进冷却 + 抛一次 [suspected] 信号。
     *
     * 这是"验证码页无限循环"的直接修法。原来的行为是：
     *   markPassed() 把冷却清成 0 → 业务立刻重试 → 验证要是没真生效（验证码没提交、
     *   或风控还没回落）→ 又撞 → request() 又设 pending → 又弹出同一页 → 无限循环。
     *
     * 现在这段时间里 request() 只做两件事：进短冷却、把"验证疑似没生效"告诉界面。
     * 用户看到的是"稍等再试"，而不是被同一个页面反复糊脸。
     *
     * ⚠️ **必须小于 [GUARD_COOLDOWN_MS]**：这两者谁大谁小不是随便定的——
     * 如果保护期比分给用户的冷却还长，用户等完冷却去刷新，又会被保护期拦住再进冷却，
     * 又是一个循环。留 15 秒余量保证"冷却结束时保护期一定已经过了"。
     */
    const val GUARD_MS = 45_000L

    /** 保护期内再撞风控时的冷却：比 5 分钟短，让用户能自己再试一次 */
    const val GUARD_COOLDOWN_MS = 60_000L

    /** 需要打开的页面地址；null 表示当前不需要验证 */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 用户完成验证的信号：业务侧收到后重试即可 */
    private val _passed = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val passed: SharedFlow<Long> = _passed.asSharedFlow()

    /**
     * "验证疑似没生效"：刚点完继续就又被风控。
     *
     * 界面收到后给一句明确提示，别让用户以为是软件坏了。
     * 是一次性信号（发出后立刻复位），不是持续状态。
     */
    private val _suspected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val suspected: SharedFlow<Unit> = _suspected.asSharedFlow()

    /** 兜底页面：拿不到具体地址时，打开学习通首页，风控页通常也会在这里出现 */
    const val FALLBACK_URL = "https://mooc1.chaoxing.com/"

    @Volatile
    private var cooldownUntil = 0L

    /** 上一次"用户宣称验证完成"的时间，用来判断是不是刚放行又撞 */
    @Volatile
    private var lastPassedAt = 0L

    /**
     * 报告"这里被风控了"。
     *
     * 并发扫描时会有几十个请求同时撞上，只有第一次生效——弹一个窗口就够了，
     * 弹出 41 个只会把界面卡死。
     *
     * @return true = 弹窗了；false = 已经在弹 / 刚验证完不值得再弹
     */
    fun request(url: String, now: Long = System.currentTimeMillis()): Boolean {
        if (_pending.value != null) {
            // 已经在弹了，不要再动冷却——否则界面停留期间冷却被无限延长
            return false
        }

        // 刚验证完又撞：不弹，改成短冷却 + 举手
        if (isGuarded(now)) {
            /*
             * 冷却只在"当前不在冷却期"时才设置。
             *
             * 如果每次上报都重置，用户每 10 秒手动刷新一次，冷却就会被一直推到
             * "现在 + 60 秒"——他永远等不到恢复，等于被永久关在门外。
             */
            if (!inCooldown(now)) cooldownUntil = now + GUARD_COOLDOWN_MS
            _suspected.tryEmit(Unit)
            return false
        }

        startCooldown(now)
        _pending.value = pageUrlOf(url)
        return true
    }

    /** 是否处在"验证后保护期"内（刚点过继续，还没来得及证明自己真的过了） */
    fun isGuarded(now: Long = System.currentTimeMillis()): Boolean =
        lastPassedAt > 0 && now - lastPassedAt in 1 until GUARD_MS

    /**
     * 只放"能当网页打开"的地址进去。
     *
     * 触发的往往是一个接口（课程列表 `backclazzdata?view=json`、视频上报 `multimedia/log`…），
     * 把接口地址丢进 WebView 只会得到一片白——真发生过：冷启动刷新误判后弹窗全白。
     * 认不出是页面的一律换成学习通首页，用户在首页一样能完成验证。
     */
    private fun pageUrlOf(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return FALLBACK_URL
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            return FALLBACK_URL
        }
        val lower = u.lowercase()
        val apiLike = lower.contains("/api/") ||
            lower.contains("api=1") ||
            lower.contains("view=json") ||
            lower.contains("courselistdata") ||
            lower.contains("multimedia/log") ||
            lower.contains("knowledge/cards") ||
            lower.contains(".json")
        return if (apiLike) FALLBACK_URL else u
    }

    /** 用户在验证窗口里点了关闭/取消：不重试，保留原来的失败提示。冷却继续生效 */
    fun cancel() {
        _pending.value = null
    }

    /**
     * 用户完成验证并已把 Cookie 导回 [cookies]。
     *
     * 冷却要一起清掉：用户刚刚亲手证明了自己不是脚本，这时候还拦着不让干活说不过去。
     * 但会记下时间点，[GUARD_MS] 内如果再次被拦，就不再反复弹窗（见 [request]）。
     */
    fun markPassed(now: Long = System.currentTimeMillis()) {
        lastPassedAt = now
        cooldownUntil = 0
        _pending.value = null
        _passed.tryEmit(now)
    }

    /**
     * 用户主动要求"再让我验证一次"（界面上的重试入口）。
     *
     * 清掉保护期与冷却，下一次 request 正常弹窗。
     */
    fun approveAgain() {
        lastPassedAt = 0
        cooldownUntil = 0
    }

    /**
     * 把 WebView 里的 Cookie 导回 OkHttp 的 CookieJar，然后标记验证完成。
     *
     * 这一步是整条链路最容易漏的地方：OkHttp 走自己的 CookieJar，看不见 WebView 里
     * 新落下的风控 Cookie。不导回的话，用户明明验证成功了，后面的请求照样被拒——
     * 症状就是"点完继续又弹验证"，因为下一次请求依然被服务端拦。
     *
     * 取 Cookie 时**按多个入口域各取一遍再合并**：风控页经常在 `passport2` 或
     * `i.chaoxing.com` 下落 Cookie，只按最初触发风控的那个页面地址取，可能一条都取不到。
     *
     * @return 导入了多少条，0 表示 WebView 那边没有新 Cookie（可能本来就不需要）
     */
    fun finish(cookies: CookieStore, url: String): Int {
        runCatching { CookieManager.getInstance().flush() }

        val merged = COOKIE_PROBE_URLS.asSequence()
            .plus(url)
            .mapNotNull { u -> runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull() }
            .filter { it.isNotBlank() }
            .joinToString("; ")

        val n = runCatching { cookies.importHeader(merged, url) }.getOrDefault(0)
        markPassed()
        return n
    }

    /** 导 Cookie 时挨个探一遍的入口域，见 [finish] */
    private val COOKIE_PROBE_URLS = listOf(
        "https://mooc1.chaoxing.com/",
        "https://passport2.chaoxing.com/",
        "https://i.chaoxing.com/",
        "https://mooc2-ans.chaoxing.com/",
    )

    // ---------------- 冷却 ----------------

    fun startCooldown(now: Long = System.currentTimeMillis()) {
        cooldownUntil = now + COOLDOWN_MS
    }

    /** 还剩多少毫秒；0 表示不在冷却期 */
    fun remainingCooldown(now: Long = System.currentTimeMillis()): Long =
        (cooldownUntil - now).coerceAtLeast(0L)

    fun inCooldown(now: Long = System.currentTimeMillis()): Boolean = remainingCooldown(now) > 0

    /** 给用户看的冷却提示；不在冷却期返回 null */
    fun cooldownText(now: Long = System.currentTimeMillis()): String? {
        val left = remainingCooldown(now)
        if (left <= 0) return null
        val totalSec = (left + 999) / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val human = if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
        return "学习通触发了风控，冷却 $human 后再试（这段时间不再发请求，避免越刷越紧）"
    }

    /**
     * 单测用：把单例状态抹回初始。
     *
     * 这是个 object，测试之间会互相污染（前一个用例 markPassed 过，
     * 后一个用例的 request 就会被保护期挡住），所以每个用例开头都清一次。
     */
    internal fun resetForTest() {
        cooldownUntil = 0
        lastPassedAt = 0
        _pending.value = null
    }
}
