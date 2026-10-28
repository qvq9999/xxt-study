package com.xxt.study.feature.work

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.activity.compose.BackHandler
import android.webkit.ConsoleMessage
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.os.Message
import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.ai.LengthRule
import com.xxt.study.core.ai.LengthRules
import com.xxt.study.core.net.Http
import com.xxt.study.core.result.Cx
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.data.remote.chaoxing.QuestionParser
import com.xxt.study.data.remote.web.FillItem
import com.xxt.study.data.remote.web.FillScript
import com.xxt.study.data.repo.AnswerMapper
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import com.xxt.study.domain.Work
import com.xxt.study.feature.ai.AiSwitchSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

private enum class Stage { Idle, Reading, Thinking, Filling, Done }

/**
 * 简答题字数不达标时最多重写几次。
 *
 * 为什么要封顶：每重写一次就多花一次 token 和十几秒。一次不成很常见（模型会低估字数），
 * 两次还不成就说明这个模型对字数不敏感，再重试只是烧钱——不如如实标出来让人工补。
 */
private const val REWRITE_TRIES = 2

/** 页面里注入的"提交成功"信号前缀，见 [SUBMIT_WATCH] 与 WebChromeClient.onConsoleMessage */
private const val TAG_SUBMITTED = "XXT_SUBMITTED"

/**
 * 提交成功探测器（注入到学习通作答页里跑）。
 *
 * 为什么用 MutationObserver 而不是只查一次：
 * 学习通提交是 AJAX 局部刷新，页面不跳转，只把成功提示插进 DOM。
 * 只查一次会在提示出现之前就放弃；监听 DOM 变化才能第一时间抓住。
 *
 * 命中后主动滚回顶部——提交按钮在页面最下方，而成功提示和得分在最上方，
 * 不滚上去用户根本看不到（这就是"提交成功后显示太靠上看不全"）。
 */
private const val SUBMIT_WATCH = """
(function(){
  if (window.__xxtSubmitWatch) return;
  window.__xxtSubmitWatch = true;
  var re = /提交成功|作业已提交|已成功提交|提交完毕|已完成提交|已提交本次作业/;
  function check(){
    if (window.__xxtFired) return;
    var t = document.body ? document.body.innerText : '';
    if (re.test(t)) {
      window.__xxtFired = true;
      try { console.log('XXT_SUBMITTED'); } catch(e) {}
    }
  }
  try {
    new MutationObserver(check).observe(document.body, {
      childList: true, subtree: true, characterData: true
    });
  } catch(e) {}
  check();
})()
"""

/**
 * 作业作答页。
 *
 * 职责边界（刻意的）：**只把答案填进页面，不点提交**。
 * 学习通提交后不能改，最后一击必须是人来按——这是这个项目从第一天起定的规矩，
 * 所以在代码里根本不存在"提交"这个调用。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkAnswerScreen(
    work: Work,
    ai: AiClient,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var stage by remember { mutableStateOf(Stage.Idle) }
    var message by remember { mutableStateOf("页面加载完后点「AI 作答」") }

    /*
     * 用户在学习通页面里点了提交之后。
     *
     * 为什么需要这个状态：提交按钮在页面**底部**，而学习通的"提交成功"提示
     * （以及得分/批阅状态）出现在页面**顶部**。用户点完提交，视口还停在底部，
     * 抬头什么都看不到 —— 这就是"提交成功的显示太靠上看不全"。
     *
     * 检测到之后做三件事：滚回顶部、把底部的状态条收起来腾给网页、
     * 顶部明确告诉他已经提交了。
     */
    var submitted by remember { mutableStateOf(false) }

    // 链接不合法就别去 loadUrl：加载空串只会得到一片白，用户看不出发生了什么
    val urlProblem = remember(work) {
        val u = work.detailUrl.trim()
        when {
            u.isBlank() -> "作业链接是空的（列表行解析可能失败）"
            !u.startsWith("http://") && !u.startsWith("https://") ->
                "作业链接不是 http(s)：${u.take(60)}"
            else -> null
        }
    }
    var loading by remember { mutableStateOf(urlProblem == null) }
    var failed by remember { mutableStateOf(urlProblem) }

    // 作答结果弹窗：用户要看 AI 想了什么、给了什么、到底填进去没有
    var sheetOpen by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("准备中") }
    var reasoning by remember { mutableStateOf<String?>(null) }
    var stat by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<AnswerRow>>(emptyList()) }

    /*
     * 两段式交互：
     * 第一次点按钮 → 生成；之后再点 → 只打开结果窗口，不重新花钱请求。
     * 要重跑必须显式点窗口里的「重新生成」。
     */
    var generated by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    /** 已拿到的答案，按 qid 存；「生成」只补缺的题，不动已经答好的 */
    var answerCache by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    // 模型快捷切换：读密钥库要花钱，用版本号缓存，别在每帧重组里读
    var aiTick by remember { mutableStateOf(0) }
    var showAiSwitch by remember { mutableStateOf(false) }
    val savedModels = remember(aiTick) { ai.savedProviders() }
    val activeId = remember(aiTick) { ai.activeProviderId() }
    val activeLabel = remember(aiTick) { ai.activeSaved()?.label ?: "未配置模型" }
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    // 返回键先让页面后退，退到底才离开答题页——否则误触一次就把作答进度丢了
    BackHandler(enabled = true) {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    fun runGenerate(fresh: Boolean) {
        val web = webView ?: run {
            message = "页面还没准备好"
            return
        }
        if (generating) return
        if (ai.config() == null) {
            // 只改底部小字太不显眼，用户会以为"点了没反应"——直接把小窗打开说清楚
            sheetOpen = true
            phase = "还没配置 AI"
            stat = "去「我的 → 设置」，选一个服务商、填 API Key、扫描并选中模型后保存。"
            message = stat
            return
        }

        scope.launch {
            sheetOpen = true
            generating = true
            try {
            if (fresh) {
                rows = emptyList()
                answerCache = emptyMap()
                generated = false
                reasoning = null
                stat = ""
            }

            stage = Stage.Reading
            phase = "读取作答页"
            message = "正在读取题目"
            val html = readHtml(web)
            val questions = QuestionParser.parse(html)
            if (questions.isEmpty()) {
                stage = Stage.Idle
                phase = "没解析出题目"
                message = "没解析出题目：可能已交卷，或页面还在加载"
                return@launch
            }

            // 非全量时只补"上次没成功的题"，已经答好的不重复花钱
            val targets: List<Question> = if (fresh) questions else questions.filterIndexed { i, _ ->
                rows.firstOrNull { it.number == i + 1 }?.ok != true
            }
            if (targets.isEmpty()) {
                stage = Stage.Done
                phase = "已全部答完"
                generating = false
                return@launch
            }

            stage = Stage.Thinking
            phase = if (fresh) {
                "解析到 ${questions.size} 题，AI 正在作答"
            } else {
                "还有 ${targets.size} 题没答上，正在补"
            }
            message = phase
            val result = when (val r = ai.answer(targets)) {
                is Cx.Ok -> r.data
                is Cx.Fail -> {
                    stage = Stage.Idle
                    phase = "AI 作答失败"
                    message = "AI 作答失败：${r.detail}"
                    return@launch
                }
                Cx.NeedLogin -> {
                    stage = Stage.Idle
                    phase = "AI 作答失败"
                    message = "AI 作答不需要登录态"
                    return@launch
                }
            }
            reasoning = result.reasoning

            /*
             * 简答题字数校验。
             * 模型经常"写够要点就收笔"（要求 150 字给 90 字），只靠提示词约束不住。
             * 这里逐题验一遍，不达标就单题重写——不整卷重来，避免把答对的题改坏。
             */
            answerCache = answerCache + result.answers
            val effective = answerCache.toMutableMap()
            for ((idx, q) in questions.withIndex()) {
                if (q.type != QType.SHORT) continue
                val rule = LengthRules.parse(q.stem) ?: continue
                var best = answerCache[q.qid]?.joinToString(" ").orEmpty()
                if (best.isNotBlank() && rule.fits(best)) continue

                /*
                 * 重写最多两次。
                 *
                 * 一次不成很常见（模型会低估字数），但也不能无限重试——每次都花钱、花时间。
                 * 每次重写都把"你现在写了多少字、要求多少"直接告诉模型，命中率比只说"字数不够"高得多。
                 * 两份都不合格时留下字数更接近要求的那份，比留一个更短的强。
                 */
                for (attempt in 1..REWRITE_TRIES) {
                    if (rule.fits(best)) break
                    phase = if (best.isBlank()) {
                        "第 ${idx + 1} 题还在重写"
                    } else {
                        "第 ${idx + 1} 题 ${LengthRules.count(best)} 字（要求 ${rule.describe}），第 $attempt 次重写"
                    }
                    val r2 = ai.answerOne(q, rule, best)
                    if (r2 !is Cx.Ok) break // 网络/接口失败，别再烧时间，保留现有答案
                    if (lengthScore(r2.data, rule) >= lengthScore(best, rule)) best = r2.data
                    if (rule.fits(best)) break
                }
                if (best.isNotBlank()) effective[q.qid] = listOf(best)
            }

            // 先把 AI 给的答案列出来（此时还不知道能不能填进去）
            val planned = questions.mapIndexed { index, q ->
                val given = effective[q.qid]
                val solved = if (given == null) null else AnswerMapper.toAnswer(q, given)
                Triple(index + 1, q, solved)
            }

            val items = ArrayList<FillItem>()
            planned.forEach { (_, q, solved) ->
                val v = solved?.value ?: return@forEach
                items += FillItem(q.qid, q.type, splitValues(q.type, v))
            }

            if (items.isEmpty()) {
                stage = Stage.Idle
                phase = "AI 没给出可用答案"
                message = "AI 没给出可用答案，检查模型是否可用"
                rows = planned.map { (no, q, solved) ->
                    AnswerRow(no, q.typeLabel.ifBlank { typeName(q.type) }, q.stem.take(80),
                        solved?.note ?: "无答案", false)
                }
                return@launch
            }

            stage = Stage.Filling
            phase = "回填 ${items.size} 题"
            message = "正在回填 ${items.size} 题"
            val receipt = evalJs(web, FillScript.build(items)).orEmpty()
            var missed = parseMissed(receipt)

            // 富文本编辑器是**异步初始化**的：页面刚打开时实例还没建好，
            // 第一次写入可能落空。没填上的题等 400ms 再补一次，通常就成功了。
            if (missed.isNotEmpty()) {
                kotlinx.coroutines.delay(400)
                val retry = items.filter { it.qid in missed }
                if (retry.isNotEmpty()) {
                    val again = evalJs(web, FillScript.build(retry)).orEmpty()
                    val stillMissed = parseMissed(again)
                    missed = missed - (retry.map { it.qid }.toSet() - stillMissed)
                }
            }

            rows = planned.map { (no, q, solved) ->
                val value = solved?.value
                // 以页面回执为准：JS 里填失败的不算成功，不能再拿"我们认为的"糊弄用户
                val ok = value != null && q.qid !in missed
                // 字数仍不达标的，把实际情况写在答案后面，别让用户以为合格了
                val lenNote = if (value != null && q.type == QType.SHORT) {
                    LengthRules.parse(q.stem)?.let { rule ->
                        if (rule.fits(value)) null
                        else "（实际 ${LengthRules.count(value)} 字，要求 ${rule.describe}）"
                    }
                } else {
                    null
                }
                AnswerRow(
                    number = no,
                    typeLabel = q.typeLabel.ifBlank { typeName(q.type) },
                    stem = q.stem.take(80),
                    answer = readable(q, value, solved?.note) + (lenNote ?: ""),
                    ok = ok,
                )
            }

            val done = items.size - missed.size
            stage = Stage.Done
            generated = true
            generating = false
            phase = "完成：回填 $done/${items.size} 题"
            stat = buildString {
                append("回填 $done/${items.size} 题")
                val noAnswer = planned.count { it.third?.value == null }
                if (noAnswer > 0) append("，$noAnswer 题 AI 没给答案")
                if (missed.isNotEmpty()) append("，${missed.size} 题页面没接受（可能需要手动填）")
                append("。核对后请自己点提交。")
            }
            message = stat
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                /*
                 * 任何异常都必须把界面从"进行中"放出来。
                 *
                 * 之前有几条提前 return 的分支忘了复位 generating，按钮从此永久禁用
                 * （enabled = !generating），用户看到的就是"点 AI 作答没反应"，
                 * 连小窗里的「生成 / 重新生成」也一起点不动。用 finally 兜住是唯一可靠的做法。
                 */
                stage = Stage.Idle
                phase = "出错了"
                message = "AI 作答出错：${e.message ?: e::class.java.simpleName}"
            } finally {
                generating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons24.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(22.dp),
                    tint = Palette.sub,
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = work.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                    maxLines = 1,
                )
                Text(
                    // 提交之后把状态顶到标题下面，用户一眼就知道"已经交上去了"，
                    // 不用再往下找那条被收起来的状态栏
                    text = if (submitted) "✓ 作业已提交" else work.rawLabel,
                    fontSize = 11.sp,
                    color = if (submitted) Palette.ok else Palette.weak,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.brand)
                    .clickable {
                        /*
                         * 同一个按钮管开关：开着就关，关着就开；没生成过才真正发请求。
                         *
                         * 关键：**生成中也要可点**。以前这里是 enabled = !generating，
                         * 用户把弹窗划掉之后就再也点不动了 —— 按钮显示"思考中"却又禁用，
                         * 只能退出页面重进。现在点一下就把正在跑的过程窗口叫回来。
                         */
                        when {
                            sheetOpen -> sheetOpen = false
                            generating || generated -> sheetOpen = true
                            else -> runGenerate(fresh = true)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = when {
                        generating && stage == Stage.Reading -> "读取中"
                        generating && stage == Stage.Thinking -> "思考中"
                        generating && stage == Stage.Filling -> "回填中"
                        sheetOpen -> "收起"
                        generated -> "查看作答"
                        else -> "AI 作答"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.card,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // 作答页是 PC 版结构，必须用 PC UA（与登录页的移动 UA 不同）
                        settings.userAgentString = Http.UA
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // 页面里有 target=_blank / window.open，不关掉会被系统接手
                        settings.setSupportMultipleWindows(false)

                        // PC 版页面按屏宽压缩，字很小：开双指缩放（隐藏 +/- 按钮，只留手势）
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        /**
                         * 关键：**必须自己接管导航**。
                         * 不设 WebViewClient 时，页面里的 intent:// / tel: / 自定义 scheme
                         * 会被 WebView 直接交给系统，表现就是"点了作业跳手机浏览器"。
                         * 返回 true = 拦下不加载；返回 false = WebView 自己加载。
                         */
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url ?: return true
                                val scheme = url.scheme?.lowercase().orEmpty()
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                failed = null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                /*
                                 * 每页都挂一个"提交成功"观察器。
                                 *
                                 * 学习通的提交按钮在页面底部，点完是 AJAX 局部刷新（不跳页），
                                 * 所以浏览器不会自动滚回顶部——而"提交成功"的提示和得分
                                 * 都在页面最上面。不主动滚上去，用户点完提交抬头什么都看不到。
                                 */
                                view?.evaluateJavascript(SUBMIT_WATCH, null)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame != true) return
                                loading = false
                                val desc = error?.description?.toString().orEmpty()
                                val code = request.url?.toString().orEmpty()
                                failed = if (desc.isBlank()) "页面加载失败" else "$desc\n$code"
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame != true) return
                                val status = errorResponse?.statusCode ?: return
                                if (status < 400) return
                                loading = false
                                failed = "服务端返回 HTTP $status（可能是登录态失效或作业已截止）"
                            }

                            /**
                             * 渲染进程崩溃：不接管的话整个 App 直接闪退；
                             * 接管后只废掉这一个 WebView，给用户一条明确出路。
                             */
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                loading = false
                                failed = "页面渲染进程崩溃（多半是内存不足），请返回后重新进入"
                                runCatching { view?.destroy() }
                                webView = null
                                return true
                            }
                        }

                        // window.open 一律不开窗，交给当前页面忽略，绝不外跳
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean = false

                            /**
                             * 接收页面里注入的"提交成功"信号。
                             *
                             * 用 console 回调而不是 addJavascriptInterface：
                             * JsInterface 会把一个 Java 对象暴露给页面里所有脚本，
                             * 而我们只需要一个单向的一次性事件，没必要开那么大口子。
                             */
                            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                val text = msg?.message().orEmpty()
                                if (text.startsWith(TAG_SUBMITTED)) {
                                    submitted = true
                                    // 滚回顶部：提交结果、得分、批阅状态都在页面最上方
                                    webView?.evaluateJavascript(
                                        "window.scrollTo({top:0,behavior:'smooth'})",
                                        null,
                                    )
                                }
                                // 吞掉日志：页面自己的 console 输出对我们没意义
                                return true
                            }
                        }

                        if (urlProblem == null) loadUrl(work.detailUrl.trim())
                        webView = this
                    }
                },
                update = { webView = it },
            )

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Palette.brand,
                    trackColor = Palette.line,
                )
            }

            failed?.let { reason ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.bg.copy(alpha = 0.97f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Text(
                            text = "页面没能打开",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = reason,
                            fontSize = 11.sp,
                            color = Palette.sub,
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Palette.brand)
                                    .clickable {
                                        failed = null
                                        loading = true
                                        webView?.reload()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            ) {
                                Text("重新加载", fontSize = 13.sp, color = Palette.card)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Palette.brandSoft)
                                    .clickable { onBack() }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            ) {
                                Text("返回", fontSize = 13.sp, color = Palette.brand)
                            }
                        }
                    }
                }
            }
        }

        /*
         * 提交之后的底部状态条：整条让位给网页。
         *
         * 交完作业用户要看的是页面顶部的"提交成功 / 得分 / 批阅状态"，
         * 这时候还占着 40dp 显示"核对后请自己点提交"纯属挡路。
         * 状态已经挪到标题下面那行（✓ 作业已提交）。
         */
        if (!submitted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.card)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (stage == Stage.Done) Icons24.Check else Icons24.Alert,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (stage == Stage.Done) Palette.ok else Palette.weak,
                )
                Text(
                    text = message,
                    fontSize = 11.sp,
                    color = if (stage == Stage.Done) Palette.ok else Palette.sub,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                // 换模型不用退回设置页
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.bg)
                        .clickable { showAiSwitch = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "AI · $activeLabel",
                        fontSize = 10.sp,
                        color = if (savedModels.isEmpty()) Palette.weak else Palette.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 132.dp),
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            containerColor = Palette.card,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("AI 作答", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
                Spacer(Modifier.height(4.dp))
                Text(phase, fontSize = 12.sp, color = Palette.brand)
                if (stat.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stat, fontSize = 11.sp, color = Palette.sub, lineHeight = 17.sp)
                }

                reasoning?.let { think ->
                    Spacer(Modifier.height(12.dp))
                    Text("思考过程", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.sub)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = think.take(1500),
                        fontSize = 11.sp,
                        color = Palette.weak,
                        lineHeight = 17.sp,
                    )
                }

                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("逐题结果", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.sub)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(rows) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = if (row.ok) "✓" else "✗",
                                    fontSize = 12.sp,
                                    color = if (row.ok) Palette.ok else Palette.alarm,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "${row.number}. [${row.typeLabel}] ${row.stem}",
                                        fontSize = 11.sp,
                                        color = Palette.ink,
                                        lineHeight = 16.sp,
                                    )
                                    Text(
                                        text = row.answer,
                                        fontSize = 11.sp,
                                        color = if (row.ok) Palette.ok else Palette.alarm,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 「生成」只补没答上的题；已经答好的不重跑，省 token
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (generating) Palette.line else Palette.brandSoft)
                            .clickable(enabled = !generating) { runGenerate(fresh = false) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "生成",
                            fontSize = 13.sp,
                            color = if (generating) Palette.weak else Palette.brand,
                        )
                    }
                    // 「重新生成」清空重来，会重新花钱，所以文案写清楚
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (generating) Palette.line else Palette.brand)
                            .clickable(enabled = !generating) { runGenerate(fresh = true) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "重新生成",
                            fontSize = 13.sp,
                            color = if (generating) Palette.weak else Palette.card,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clickable { sheetOpen = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text("关闭", fontSize = 13.sp, color = Palette.sub)
                    }
                }
            }
        }
    }

    // ---------------- 模型切换 ----------------
    if (showAiSwitch) {
        AiSwitchSheet(
            saved = savedModels,
            activeId = activeId,
            onPick = { picked ->
                ai.setActive(picked.id)
                aiTick++
                showAiSwitch = false
                message = "已切换到 ${picked.label} · ${picked.model}"
            },
            onDismiss = { showAiSwitch = false },
            onOpenSettings = {
                showAiSwitch = false
                onOpenSettings()
            },
        )
    }
}

/**
 * 字数接近度：合格的排最前，其余按字数比（字数越多越接近下限）。
 *
 * 用它来比较"两次重写哪份更好"，避免越重写越短。
 */
private fun lengthScore(text: String, rule: LengthRule): Int {
    if (text.isBlank()) return -1
    return if (rule.fits(text)) Int.MAX_VALUE else LengthRules.count(text)
}

/** 弹窗里的一行：这道题 AI 给了什么、页面接受了没有 */
private data class AnswerRow(
    val number: Int,
    val typeLabel: String,
    val stem: String,
    val answer: String,
    val ok: Boolean,
)

/** 把提交值翻译成人话，方便核对 */
private fun readable(q: Question, value: String?, note: String?): String {
    if (value == null) return note ?: "无答案"
    return when (q.type) {
        QType.SINGLE, QType.MULTIPLE -> value
        QType.JUDGE -> if (value == "true") "对" else "错"
        else -> value.take(60)
    }
}

private fun typeName(t: QType): String = when (t) {
    QType.SINGLE -> "单选"
    QType.MULTIPLE -> "多选"
    QType.JUDGE -> "判断"
    QType.FILL -> "填空"
    QType.SHORT -> "简答"
    QType.UNKNOWN -> "未知"
}

/** 从 "FILLED:3/5 MISSED:1,2" 里取出页面明确拒绝的 qid */
internal fun parseMissed(receipt: String?): Set<String> {
    if (receipt.isNullOrBlank()) return emptySet()
    val idx = receipt.indexOf("MISSED:")
    if (idx < 0) return emptySet()
    return receipt.substring(idx + "MISSED:".length)
        .trim()
        .split(',')
        .filter { it.isNotBlank() }
        .toSet()
}

/** 把提交值拆成页面能匹配的数组：多选拆字母，其余整串 */
private fun splitValues(type: QType, value: String): List<String> = when (type) {
    QType.MULTIPLE -> value.uppercase().map { it.toString() }
    else -> listOf(value)
}

/**
 * 执行 JS 并解出字符串；失败返回 null。
 *
 * 必须带超时：`evaluateJavascript` 的回调在页面被销毁等情况下可能永远不来，
 * 一旦挂住，整个生成流程就卡在"读取中"再也不动。
 */
private suspend fun evalJs(web: WebView, script: String): String? =
    kotlinx.coroutines.withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(script) { v ->
                val text = runCatching {
                    Json.parseToJsonElement(v ?: "null").jsonPrimitive.content
                }.getOrNull()
                cont.resume(text)
            }
        }
    }

/**
 * 读整个作答页的 HTML。
 *
 * `evaluateJavascript` 对超大返回值会静默失败（回调里拿到 null），
 * 而学习通的作答页很容易到几百 KB。所以：空/过短就等一下重试一次，最后退到 body。
 */
private suspend fun readHtml(web: WebView): String {
    val script = "(function(){var d=document.documentElement;return d?d.outerHTML:'';})()"
    var html = evalJs(web, script).orEmpty()
    if (html.length > 500) return html
    kotlinx.coroutines.delay(350)
    html = evalJs(web, script).orEmpty()
    if (html.length > 500) return html
    return evalJs(web, "(function(){var b=document.body;return b?b.innerHTML:'';})()").orEmpty()
}
