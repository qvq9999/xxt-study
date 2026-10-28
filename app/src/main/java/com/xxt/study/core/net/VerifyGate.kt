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

    /** 需要打开的页面地址；null 表示当前不需要验证 */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 用户完成验证的信号：业务侧收到后重试即可 */
    private val _passed = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val passed: SharedFlow<Long> = _passed.asSharedFlow()

    /** 兜底页面：拿不到具体地址时，打开学习通首页，风控页通常也会在这里出现 */
    const val FALLBACK_URL = "https://mooc1.chaoxing.com/"

    @Volatile
    private var cooldownUntil = 0L

    /**
     * 报告"这里被风控了"。
     *
     * 并发扫描时会有几十个请求同时撞上，只有第一次生效——弹一个窗口就够了，
     * 弹出 41 个只会把界面卡死。
     */
    fun request(url: String): Boolean {
        startCooldown()
        if (_pending.value != null) return false
        _pending.value = url.ifBlank { FALLBACK_URL }
        return true
    }

    /** 用户在验证窗口里点了关闭/取消：不重试，保留原来的失败提示。冷却继续生效 */
    fun cancel() {
        _pending.value = null
    }

    /**
     * 用户完成验证并已把 Cookie 导回 [cookies]。
     *
     * 冷却要一起清掉：用户刚刚亲手证明了自己不是脚本，这时候还拦着不让干活说不过去。
     */
    fun markPassed() {
        cooldownUntil = 0
        _pending.value = null
        _passed.tryEmit(System.currentTimeMillis())
    }

    /**
     * 把 WebView 里的 Cookie 导回 OkHttp 的 CookieJar，然后标记验证完成。
     *
     * 这一步是整条链路最容易漏的地方：OkHttp 走自己的 CookieJar，看不见 WebView 里
     * 新落下的风控 Cookie。不导回的话，用户明明验证成功了，后面的请求照样被拒。
     *
     * @return 导入了多少条，0 表示 WebView 那边没有新 Cookie（可能本来就不需要）
     */
    fun finish(cookies: CookieStore, url: String): Int {
        runCatching { CookieManager.getInstance().flush() }
        val header = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        val n = runCatching { cookies.importHeader(header, url) }.getOrDefault(0)
        markPassed()
        return n
    }

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
}
