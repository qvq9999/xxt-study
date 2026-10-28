package com.xxt.study.core.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "需要人工验证"的总开关。
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
 */
object VerifyGate {

    /** 需要打开的页面地址；null 表示当前不需要验证 */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 用户完成验证的信号：业务侧收到后重试即可 */
    private val _passed = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val passed: SharedFlow<Long> = _passed.asSharedFlow()

    /** 兜底页面：拿不到具体地址时，打开学习通首页，风控页通常也会在这里出现 */
    const val FALLBACK_URL = "https://mooc1.chaoxing.com/"

    /**
     * 报告"这里被风控了"。
     *
     * 并发扫描时会有几十个请求同时撞上，只有第一次生效——弹一个窗口就够了，
     * 弹出 41 个只会把界面卡死。
     */
    fun request(url: String): Boolean {
        if (_pending.value != null) return false
        _pending.value = url.ifBlank { FALLBACK_URL }
        return true
    }

    /** 用户在验证窗口里点了关闭/取消：不重试，保留原来的失败提示 */
    fun cancel() {
        _pending.value = null
    }

    /** 用户完成验证：清掉待办并广播，让扫描/刷课自己接着跑 */
    fun markPassed() {
        _pending.value = null
        _passed.tryEmit(System.currentTimeMillis())
    }
}
