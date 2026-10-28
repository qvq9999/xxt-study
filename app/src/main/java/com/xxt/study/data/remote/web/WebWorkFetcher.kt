package com.xxt.study.data.remote.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.xxt.study.core.net.Http
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.Parsers
import com.xxt.study.domain.Course
import com.xxt.study.domain.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * 用隐藏 WebView 取作业列表。
 *
 * 为什么必须用浏览器内核：实测同一条课程中间页 URL，浏览器能 302 到课程页并带出 workEnc，
 * 而 Node/OkHttp 即使 Cookie 完全一致也返回登录页（服务端按 TLS/请求特征区分）。
 *
 * ⚠️ 这个类出过一次很隐蔽的故障，结论写在这里，不重复踩：
 *
 * **池化复用的 WebView 里，`loadUrl()` 之后的一小段时间内 `document` 还是上一门课的。**
 * 旧实现靠 `onPageFinished` 置位的布尔量判断"页面换好了"，但
 *   - 上一门课的 `onPageFinished` 可能在新 `loadUrl` 之后才回来（把标志又置成 true）；
 *   - `about:blank` 的完成回调同理；
 * 于是探针会在**上一门课的文档**上跑，把它的 workEnc 当成这一门课的，
 * 或者把中间的 302 页判成"没有 workEnc"。表现就是：
 * **首次启动（池子冷、每个 WebView 都是新造的）结果准确，之后每次刷新都乱。**
 *
 * 现在不再依赖任何时间点标志，改成**在页面内自证身份**：
 * 探针第一件事是检查 `location.href` 里有没有本门课的 courseId / 是不是目标页，
 * 不是就返回空串继续等。文档级的事实不会说谎，这条路径彻底消除了竞态。
 *
 * 性能上的四条优化都保留：
 * 1. 拦掉静态资源（图片/字体/音视频）——只读 DOM 里一个 hidden input 和几个 li，不需要渲染；
 * 2. 不等 `onPageFinished`，改轮询目标节点，DOM 一就绪就走；
 * 3. WebView 复用池，省掉 41 次 Chromium 内核初始化；
 * 4. 由调用方并发（[com.xxt.study.data.repo.StudyRepo]），池容量即并发上限。
 */
class WebWorkFetcher(
    private val appContext: Context,
    poolSize: Int = DEFAULT_POOL,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pool = WebViewPool(poolSize)

    /**
     * 取一门课的作业列表。
     *
     * 两道保险：
     * 1. 正常走 [WebViewPool]（省掉 Chromium 内核初始化，41 门课快很多）；
     * 2. 池里的实例失败了，就**换一个全新实例再试一次**。
     *
     * 第 2 条是刻意留的兜底：池化最大的风险就是"文档还是上一门课的"，
     * 而全新 WebView 没有这个包袱。只有失败的课才付这个代价（建 + 销毁一个 WebView，
     * 几百毫秒），成功路径一分钱不花。宁可慢，也不能让人看到"全部超时"。
     */
    suspend fun fetch(course: Course): Cx<List<Work>> = withContext(Dispatchers.Main) {
        // 冷却期一个请求都不发：风控是按请求特征累积的，越刷越紧
        VerifyGate.cooldownText()?.let { return@withContext Cx.Fail(Cx.Kind.VERIFY, it) }

        /*
         * 课程之间抖一下再开下一个页面。
         *
         * 纯顺序加载也会被看出规律（每门课间隔几乎一样），加 250~650ms 随机间隔
         * 让节奏更接近人在翻页。代价很小：一次全量扫描多十几秒。
         */
        delay(Random.nextLong(THROTTLE_MIN_MS, THROTTLE_MAX_MS))

        val pooled = pool.acquire()
        val first = try {
            fetchOn(pooled, course)
        } finally {
            pool.release(pooled)
        }
        // 登录态失效、无权限、被限流都是"结论"而不是"故障"，换实例重试没意义，只会拖长时间
        val kind = (first as? Cx.Fail)?.kind
        if (kind == null || kind !in RETRYABLE) return@withContext first

        val fresh = createWebView()
        try {
            fetchOn(fresh, course)
        } finally {
            runCatching { fresh.destroy() }
        }
    }

    /** 在指定 WebView 上跑完两跳；两跳都用同一份浏览上下文，Cookie/跳转链才连续 */
    private suspend fun fetchOn(web: WebView, course: Course): Cx<List<Work>> {
        // ---- 第一跳：课程中间页，目标 = workEnc + location 里的 stuenc ----
        val middle = "https://mooc1.chaoxing.com/visit/stucoursemiddle" +
            "?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2"
        navigate(web, middle)

        val seen = StringBuilder()
        val probe = pollJs(web, ScanProbes.middle(), seen) ?: return timeout("课程页", seen)
        when {
            probe.startsWith(TAG_VERIFY) -> {
                // 把风控页的地址交给界面，让用户在学习通原版页面上完成验证
                VerifyGate.request(probe.removePrefix(TAG_VERIFY))
                return Cx.Fail(Cx.Kind.VERIFY, "学习通触发了风控，需要你手动完成验证")
            }
            probe == TAG_LOGIN -> return Cx.NeedLogin
            probe.startsWith(TAG_NONE) -> {
                val detail = probe.removePrefix(TAG_NONE)
                val hint = when {
                    detail.contains("频繁") -> "访问太频繁被拦，稍候重试"
                    detail.contains("无权限") -> "无权限访问该课程"
                    detail.contains("不存在") -> "课程不存在或已删除"
                    else -> "落地页里没有 workEnc"
                }
                return Cx.Fail(Cx.Kind.PARSE, "$hint：${detail.take(150)}")
            }
        }
        // OK|<href>|<workEnc>
        val parts = probe.split('|', limit = 3)
        if (parts.size < 3) return Cx.Fail(Cx.Kind.PARSE, "课程页返回格式异常")
        val href = parts[1]
        val workEnc = parts[2]
        val stuenc = Regex("[?&]enc=([0-9a-fA-F]+)").find(href)?.groupValues?.get(1).orEmpty()
        if (stuenc.isBlank() || workEnc.isBlank()) {
            return Cx.Fail(
                Cx.Kind.PARSE,
                "课程页没拿到 enc（stuenc=${stuenc.take(6)}…, workEnc=${workEnc.take(6)}…）",
            )
        }

        // ---- 第二跳：作业列表 ----
        val listUrl = "https://mooc1.chaoxing.com/mooc2/work/list" +
            "?courseId=${course.courseId}&classId=${course.classId}&cpi=${course.cpi}" +
            "&ut=s&t=${System.currentTimeMillis()}" +
            "&stuenc=$stuenc&enc=$workEnc"
        navigate(web, listUrl)

        seen.setLength(0)
        val rowsProbe = pollJs(web, ScanProbes.rows(), seen) ?: return timeout("作业列表", seen)
        when {
            rowsProbe.startsWith(TAG_VERIFY) -> {
                VerifyGate.request(rowsProbe.removePrefix(TAG_VERIFY))
                return Cx.Fail(Cx.Kind.VERIFY, "学习通触发了风控，需要你手动完成验证")
            }
            rowsProbe == TAG_LOGIN -> return Cx.NeedLogin
            rowsProbe == TAG_DENIED -> return Cx.Fail(Cx.Kind.DENIED, "作业列表被拒：无权限的操作")
            rowsProbe == TAG_EMPTY -> return Cx.Ok(emptyList())
        }
        return Cx.Ok(parseRows(rowsProbe))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // 数据页是 PC 版结构，必须用 PC UA（登录页才用移动 UA）
        settings.userAgentString = Http.UA
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 页面上没有我们要显示的像素，关掉省 CPU
        settings.blockNetworkImage = true

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!BLOCKED.containsMatchIn(url)) return null
                // 空响应比"不拦截"更快：不下载、不解析、不解码
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }
    }

    /** 池用完了就销毁，App 退出或登出时调用 */
    fun shutdown() = pool.shutdown()

    /**
     * 清掉 WebView 的 Cookie。
     *
     * 登出时必须做：只清自己的 CookieStore 不够，隐藏 WebView 还带着上一份会话，
     * 换个账号登录会串数据。这是"登出后数据还是上一个号"的根因。
     */
    fun clearCookies() {
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
    }

    private fun timeout(what: String, seen: StringBuilder): Cx.Fail {
        val tail = seen.toString().removePrefix(TAG_WAIT).take(120)
        val extra = if (tail.isBlank()) "" else "，最后看到：$tail"
        return Cx.Fail(Cx.Kind.NETWORK, "$what 等待超时（${POLL_TIMEOUT_MS / 1000}s）$extra")
    }

    /**
     * 导航。
     *
     * 关键在**先给当前这份文档打标记，再 loadUrl**。
     * 新文档不会继承 `window.__xxtNav`，探针据此就能分清"还是上一门课的旧页面"和"我要的新页面"。
     * 这个顺序不能反：标记一旦落到新文档上，那一页就永远等不到结果（全课超时）。
     */
    private suspend fun navigate(web: WebView, url: String) {
        // 标记只是"尽力而为"：给它一个短超时，拿不到就照常导航。
        // 拿不到时最坏是这一次可能读到旧文档，探针里还有 5 秒有效期的兜底。
        js(web, ScanProbes.MARK, MARK_TIMEOUT_MS)
        web.loadUrl(url)
    }

    /**
     * 轮询直到拿到可用的结论。
     *
     * `NONE` / `EMPTY` 这些"没有目标元素"的结论要**稳一会儿再认**：
     * 302 链里可能有一跳是 HTML 中间页，它 `complete` 时同样没有 workEnc，
     * 立刻下结论就会把一门正常的课判成"没有 workEnc"。
     * 观察到状态保持 [DONE_HOLD_MS] 不变、或者整体超时，才认可它。
     *
     * [seen] 记录最后一次现场，超时的时候能告诉用户"卡在哪个页面"。
     */
    private suspend fun pollJs(web: WebView, script: String, seen: StringBuilder): String? {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var held: String? = null
        var heldAt = 0L
        while (System.currentTimeMillis() < deadline) {
            val v = js(web, script)
            if (!v.isNullOrBlank()) {
                when {
                    v.startsWith(TAG_WAIT) -> {
                        if (seen.length < 300) {
                            seen.setLength(0)
                            seen.append(v)
                        }
                    }
                    v.startsWith(TAG_NONE) || v == TAG_EMPTY -> {
                        val now = System.currentTimeMillis()
                        if (held == null) {
                            held = v
                            heldAt = now
                        } else if (now - heldAt >= DONE_HOLD_MS) {
                            return v
                        }
                    }
                    else -> return v
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        // 没有"更好的结论"但有"页面完整却没目标元素"的证据：按证据走，别报一个没意义的超时
        return held
    }

    /** 执行 JS 并解出字符串结果 */
    private suspend fun js(web: WebView, script: String, timeoutMs: Long = JS_TIMEOUT_MS): String? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    web.evaluateJavascript(script) { value ->
                        val text = runCatching {
                            json.parseToJsonElement(value ?: "null").jsonPrimitive.content
                        }.getOrNull()
                        if (cont.isActive) cont.resume(text)
                    }
                }
            }.getOrNull()
        }

    private fun parseRows(rowsJson: String): List<Work> {
        if (rowsJson.isBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(rowsJson).jsonArray.mapNotNull { el ->
                val obj = el.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content.orEmpty()
                val url = obj["url"]?.jsonPrimitive?.content.orEmpty()
                // 与 HTML 路径共用同一套行解析，避免两条路各写一份规则
                Parsers.workFrom(label, url)
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- WebView 池 ----------------

    /**
     * 复用池。
     *
     * 池容量 = 并发上限：要 3 个并发就 3 个 WebView，不多不少。
     * 锁只在取还实例时持有，页面加载期间不占锁，所以是真并发。
     *
     * ⚠️ release 里**不再** loadUrl("about:blank")。原因见类注释：
     * 空白页的加载是异步的，它的完成回调会落在下一次 loadUrl 之后，
     * 把"新页面已就绪"这件事搅乱；而且多一次导航本身就多几百毫秒。
     * 跨课程串数据现在由探针校验 location.href 兜住，不需要清场。
     */
    private inner class WebViewPool(private val size: Int) {

        private val free = ArrayDeque<WebView>()
        private val gate = Mutex()

        suspend fun acquire(): WebView = gate.withLock {
            free.removeFirstOrNull() ?: create()
        }

        suspend fun release(web: WebView) {
            // 只停加载：让上一门课还在跑的请求停下来，不制造新的导航
            runCatching { web.stopLoading() }
            gate.withLock {
                if (free.size < size) free.addLast(web) else runCatching { web.destroy() }
            }
        }

        /** 销毁所有空闲实例；登出/退出时调用。destroy 必须在主线程，调用方保证 */
        fun shutdown() {
            for (w in free) runCatching { w.destroy() }
            free.clear()
        }

        private fun create(): WebView = createWebView()
    }

    private companion object {
        /**
         * 并发数：**1**。
         *
         * 3 路并发在服务端看来就是同一账号三个浏览器同时开页面，是最明显的脚本特征。
         * 串行扫描慢一些，但换来的是不触发风控——比"快但被封"划算。
         */
        const val DEFAULT_POOL = 1

        /**
         * 单跳最长等待。
         *
         * 12 秒是实测的折中：正常课程页 1~3 秒就出 DOM，超时多半是真的打不开。
         * 原来 20 秒 × 41 门课（失败时）会把一次扫描拖到几分钟。
         */
        const val POLL_TIMEOUT_MS = 12_000L
        const val POLL_INTERVAL_MS = 150L

        /** 课程之间的随机间隔，模拟人工翻页节奏 */
        const val THROTTLE_MIN_MS = 250L
        const val THROTTLE_MAX_MS = 650L

        /** "页面完整但没有目标元素"要保持这么久才认可，躲开 302 链里的 HTML 中间页 */
        const val DONE_HOLD_MS = 3_000L
        const val JS_TIMEOUT_MS = 4_000L

        /** 打标记的超时：必须在导航前完成，不值得为它多等；拿不到就算了 */
        const val MARK_TIMEOUT_MS = 800L

        /** 值得换个干净实例再试一次的错误类型 */
        val RETRYABLE = setOf(Cx.Kind.NETWORK, Cx.Kind.PARSE, Cx.Kind.UNKNOWN)

        const val TAG_WAIT = ScanProbes.TAG_WAIT
        const val TAG_LOGIN = ScanProbes.TAG_LOGIN
        const val TAG_NONE = ScanProbes.TAG_NONE
        const val TAG_EMPTY = ScanProbes.TAG_EMPTY
        const val TAG_DENIED = ScanProbes.TAG_DENIED
        const val TAG_VERIFY = ScanProbes.TAG_VERIFY

        /**
         * 静态资源黑名单。
         *
         * 只拦"确定不影响 DOM"的类型：图片、字体、音视频。
         * **不能拦 JS 和 XHR**——作业行的 HTML 虽然由服务端渲染，
         * 但课程页有一部分结构是脚本填的，拦了会拿到空 DOM。
         * CSS 也不拦：体积小、且有页面靠 CSS 选择器隐藏/显示节点。
         */
        val BLOCKED = Regex(
            "\\.(png|jpe?g|gif|webp|bmp|ico|svg|woff2?|ttf|otf|eot|mp4|webm|mp3|m4a|wav|avi)(\\?|#|$)",
            RegexOption.IGNORE_CASE,
        )

        /** 探针脚本见 [ScanProbes]：它是扫描正确性的核心，抽出去是为了能单测 */
    }
}
