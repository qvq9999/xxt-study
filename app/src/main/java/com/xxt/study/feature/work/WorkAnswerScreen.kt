package com.xxt.study.feature.work

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.xxt.study.core.ai.AnswerSanitizer
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
import com.xxt.study.data.repo.SolvedAnswer
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import com.xxt.study.domain.Work
import com.xxt.study.feature.ai.AiSwitchDialog
import kotlinx.coroutines.delay
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

/** 页面滚动位置变化的信号前缀（带 0/1） */
private const val TAG_SCROLL = "XXT_SCROLL:"

/**
 * 把被裁剪的容器撑开。
 *
 * 用户反馈：「提交完作业，结果只能看到一半，划到顶部也只有一半，像是网页本身显示有问题」——
 * 这个判断是对的。学习通的结果面板常常放在一个**固定高度 + overflow:hidden** 的容器里
 * （老 PC 版页面用 table/frame 布局），内容超出去就被裁掉：
 * 页面本身不滚动、内容也拿不到，滚到哪儿都是"一半"。
 *
 * 做法：找出**内容确实溢出、而且被设为 hidden** 的容器，把高度限制与 hidden 一起去掉。
 * 只动这一类元素——正常排版的容器（overflow 可见）一律不碰，避免把页面搞乱。
 *
 * 提交之后页面是只读的，改它的布局不会影响作答，所以这一步是安全的。
 * 返回修正了几处，用回执告诉用户到底发生了什么（而不是"点了没反应"）。
 */
private const val UNCLIP = """
(function(){
  var fixed = 0;
  function repair(doc){
    var els;
    try { els = doc.querySelectorAll('div,section,article,aside,td,iframe,ul,ol'); } catch(e) { return; }
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      try {
        var cs = doc.defaultView.getComputedStyle(el);
        var clipY = el.scrollHeight - el.clientHeight > 12 &&
                    (cs.overflowY === 'hidden' || cs.overflow === 'hidden');
        var clipX = el.scrollWidth - el.clientWidth > 12 &&
                    (cs.overflowX === 'hidden' || cs.overflow === 'hidden');
        if (!clipY && !clipX) continue;
        if (clipY) {
          el.style.maxHeight = 'none';
          el.style.height = 'auto';
          el.style.overflowY = 'visible';
        }
        if (clipX) {
          el.style.maxWidth = '100%';
          el.style.overflowX = 'auto';
        }
        fixed++;
      } catch(e) {}
    }
  }
  repair(document);
  // 同源 iframe 里的内容也一起处理（跨域的访问会抛异常，忽略即可）
  try {
    var frames = document.querySelectorAll('iframe');
    for (var k = 0; k < frames.length; k++) {
      try {
        var d = frames[k].contentDocument;
        if (d && d.body) {
          frames[k].style.height = 'auto';
          frames[k].style.minHeight = '600px';
          repair(d);
        }
      } catch(e) {}
    }
  } catch(e) {}
  try { document.documentElement.style.overflowX = 'auto'; } catch(e) {}
  return '' + fixed;
})()
"""

/**
 * 提交成功探测器（注入到学习通作答页里跑）。
 *
 * 为什么用 MutationObserver 而不是只查一次：
 * 学习通提交是 AJAX 局部刷新，页面不跳转，只把成功提示插进 DOM。
 * 只查一次会在提示出现之前就放弃；监听 DOM 变化才能第一时间抓住。
 *
 * 三路兜底（少一路就会出现"提交了但 App 不知道"）：
 * 1. MutationObserver —— 正常路径，插节点/改文字都能抓到；
 * 2. 1.2 秒轮询 —— 有些写法（改属性、iframe 内部渲染）不产生我们监听的变化；
 * 3. 劫持 alert / confirm —— **这一路是必须的**：学习通部分版本提交完直接
 *    `alert('提交成功')`，而 alert 是同步阻塞的，JS 线程被卡住，
 *    MutationObserver 的回调根本轮不到执行——只有从 alert 入口才能提前发信号。
 */
private const val SUBMIT_WATCH = """
(function(){
  if (window.__xxtSubmitWatch) return;
  window.__xxtSubmitWatch = true;

  var re = /提交成功|已提交成功|作业已提交|已成功提交|提交完毕|已完成提交|已提交本次作业|交卷成功|提交作业成功|您已提交/;

  function fire(tag){
    if (window.__xxtFired) return;
    window.__xxtFired = true;
    try { console.log('XXT_SUBMITTED:' + (tag || '')); } catch(e) {}
  }

  function check(){
    if (window.__xxtFired) return;
    var t = document.body ? document.body.innerText : '';
    if (t && re.test(t)) fire('text');
  }

  try {
    new MutationObserver(check).observe(document.body, {
      childList: true, subtree: true, characterData: true
    });
  } catch(e) {}

  try {
    var rawAlert = window.alert;
    window.alert = function(msg){
      try { if (re.test(String(msg || ''))) fire('alert'); } catch(e) {}
      return rawAlert.apply(window, arguments);
    };
  } catch(e) {}

  try {
    var rawConfirm = window.confirm;
    window.confirm = function(msg){
      try { if (re.test(String(msg || ''))) fire('confirm'); } catch(e) {}
      return rawConfirm.apply(window, arguments);
    };
  } catch(e) {}

  try { setInterval(check, 1200); } catch(e) {}

  check();
})()
"""

/**
 * 把页面滚回最顶端。
 *
 * 为什么不能只写 `window.scrollTo(0,0)`：
 * 学习通作答页把内容放在一个内部滚动容器里（外层 window 根本不动），
 * 对它调 scrollTo 等于什么都没做——这正是"提交成功了但看不到提示"的原因。
 * 所以这里逐层清零：window → documentElement → body → 所有子滚动容器。
 *
 * 不用 `behavior:'smooth'`：提交后要的是**立刻**看到结果，动画那一秒纯属添乱。
 */
private const val SCROLL_TOP = """
(function(){
  function zero(el){
    try { if (el && el.scrollTop) el.scrollTop = 0; } catch(e) {}
  }
  try { window.scrollTo(0, 0); } catch(e) {}
  zero(document.documentElement);
  zero(document.body);
  try {
    var all = document.querySelectorAll('div,section,main,article,td,ul,ol');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.scrollTop > 0 && el.scrollHeight - el.clientHeight > 8) el.scrollTop = 0;
    }
  } catch(e) {}
})()
"""

/**
 * 上报"页面是否已经往下滚了"，用来决定要不要显示回到顶部的浮动按钮。
 *
 * scroll 事件不冒泡，所以必须 capture=true 才能拿到内部容器的滚动。
 * 只上报"跨过阈值"的变化，中间过程一律吞掉，避免每帧刷一次日志。
 */
private const val SCROLL_REPORT = """
(function(){
  if (window.__xxtScrollReport) return;
  window.__xxtScrollReport = true;
  var last = -1, timer = null;
  function offset(){
    var a = window.pageYOffset || 0;
    var d = (document.documentElement && document.documentElement.scrollTop) || 0;
    var b = (document.body && document.body.scrollTop) || 0;
    return Math.max(a, d, b);
  }
  function report(){
    var v = offset();
    var down = v > 240 ? 1 : (v < 80 ? 0 : -1);
    if (down === -1 || down === last) return;
    last = down;
    try { console.log('XXT_SCROLL:' + down); } catch(e) {}
  }
  window.addEventListener('scroll', function(){
    if (timer) return;
    timer = setTimeout(function(){ timer = null; report(); }, 400);
  }, true);
  report();
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

    /** 页面是否已经往下滚过一段（决定要不要显示"回到顶部"浮动键） */
    var pageScrolledDown by remember { mutableStateOf(false) }

    /** 提交后的补充提示（"已展开 3 处被裁剪的区域"这类），显示在提交状态条里 */
    var submitHint by remember { mutableStateOf<String?>(null) }

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
     * 生成过程（阶段级）。
     *
     * 我们的接口是非流式的：一次请求拿完整 JSON，中间拿不到任何 token。
     * 所以"思考过程"只能做到阶段 + 已等待秒数——比原地一个 phase 字符串强得多，
     * 用户能看出"在跑"还是"卡住了"。真正逐字流要改接口为 SSE，代价是整个链路重写，
     * 这里先给到阶段级，够用且不骗人。
     */
    var steps by remember { mutableStateOf<List<GenStep>>(emptyList()) }
    var elapsedSec by remember { mutableStateOf(0L) }

    /*
     * 对话式优化（0.9.1 取代了原来的"改提示词"）。
     *
     * - chat        对话记录：用户提的要求、AI 的回应、系统注记，按顺序排
     * - chatInput   输入框当前内容
     * - chatHint    空输入点发送时的提示（放在这里而不是 Toast：弹窗里 Toast 常被挡）
     * - undoStack   每轮优化前压一份旧答案，供【撤销本次优化】回退
     * - questionsCache 最近一次解析出的题目，优化时直接复用，不重新读页面
     */
    var chat by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var chatHint by remember { mutableStateOf<String?>(null) }
    var undoStack by remember { mutableStateOf<List<Map<String, List<String>>>>(emptyList()) }
    var questionsCache by remember { mutableStateOf<List<Question>>(emptyList()) }

    /*
     * 两段式交互：
     * 第一次点按钮 → 生成；之后再点 → 只打开结果窗口，不重新花钱请求。
     * 要重跑必须显式点窗口里的「重新生成」。
     */
    var generated by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    // 生成中才走时钟：停下来就冻结在最后那个秒数上，不会继续跳
    LaunchedEffect(generating) {
        if (!generating) return@LaunchedEffect
        elapsedSec = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsedSec += 1
        }
    }

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

    /**
     * 把页面滚回最顶端。
     *
     * 为什么要滚三次：提交成功后学习通是**异步**把结果面板插进来的，
     * 第一次滚的时候页面可能还没长高、没被撑开，滚了个空。
     * 0 / 400 / 1200 毫秒各来一次，等它插完再补一刀。
     */
    fun scrollPageToTop() {
        val w = webView ?: return
        listOf(0L, 400L, 1200L).forEach { wait ->
            scope.launch {
                if (wait > 0) delay(wait)
                runCatching { w.evaluateJavascript(SCROLL_TOP, null) }
            }
        }
    }

    /**
     * 展开页面里被裁剪的容器，然后滚到顶部。
     *
     * @param quiet true = 自动执行；没改到任何东西就保持安静，别在状态条上刷没用的字
     */
    fun unclipPage(quiet: Boolean = false) {
        val w = webView ?: return
        scope.launch {
            val n = evalJs(w, UNCLIP)?.trim()?.toIntOrNull()
            scrollPageToTop()
            val info = when {
                n == null -> "已尝试展开页面内容"
                n > 0 -> "已展开 $n 处被裁剪的区域"
                else -> "没发现被裁剪的区域；如果还是显示不全，试试双指缩小页面"
            }
            if (!quiet || n == null || n > 0) submitHint = info
        }
    }

    /** 检测到提交成功后：置状态 + 把结果顶到视野里 + 撑开被裁掉的部分 */
    fun onSubmitted() {
        if (!submitted) submitted = true
        scrollPageToTop()
        /*
         * 提交后等一会儿自动撑开被裁剪的容器。
         *
         * 结果面板是异步插进来的，撑早了没有目标；等 1.4 秒基本已经插完。
         * 这一步不改任何数据，只是把 overflow:hidden + 固定高度 的容器放开，
         * 让"只能看到一半"的结果完整显示出来。
         */
        scope.launch {
            delay(1400)
            unclipPage(quiet = true)
        }
    }

    /**
     * 把一版答案回填到页面，并刷新"答案区 + 状态"。
     *
     * 首次作答和多轮优化走的是同一段逻辑——优化完也只是"换一版答案再回填一次"，
     * 必须共用；否则两条路的行状态、字数提示迟早会不一致。
     *
     * 这里不处理异常：调用方用 try/finally 兜住 generating。
     */
    suspend fun fillAndReport(
        web: WebView,
        questions: List<Question>,
        effective: Map<String, List<String>>,
        /** true = 这一版是多轮优化出来的，文案要说"优化"而不是"作答" */
        optimized: Boolean = false,
    ): Int {
        // 先把 AI 给的答案列出来（此时还不知道能不能填进去）
        val planned = questions.mapIndexed { index, q ->
            val given = effective[q.qid]
            val solved = if (given == null) null else AnswerMapper.toAnswer(q, given)
            /*
             * 最后一道闸。
             *
             * 上游（AiClient）已经洗过一遍，这里再洗一遍是刻意的**双保险**：
             * 一旦哪条路径漏了清洗，代价是把提示词写进作答框、学生直接提交——
             * 比"这题没答案"的代价大得多。清洗不过就当作没答案，交给人工补。
             */
            val guarded = solveTextGuard(q, solved)
            Triple(index + 1, q, guarded)
        }

        val items = ArrayList<FillItem>()
        planned.forEach { (_, q, solved) ->
            val v = solved?.value ?: return@forEach
            // 填空按"空"传分量：页面上 N 个空是 N 个独立编辑框，拼成一句就只能落进第 1 空
            items += FillItem(q.qid, q.type, valuesOf(q, solved, v), blanksOf(q, solved))
        }

        if (items.isEmpty()) {
            steps = steps.stepDone(2).stepFail(3, "没有可用答案")
            stage = Stage.Idle
            phase = "AI 没给出可用答案"
            message = "AI 没给出可用答案，检查模型是否可用"
            rows = planned.map { (no, q, solved) ->
                AnswerRow(no, q.typeLabel.ifBlank { typeName(q.type) }, q.stem,
                    solved?.note ?: "无答案", false)
            }
            return -1
        }

        steps = steps.stepDone(2).stepNow(3)
        stage = Stage.Filling
        phase = "回填 ${items.size} 题"
        message = "正在回填 ${items.size} 题"
        val receipt = evalJs(web, FillScript.build(items)).orEmpty()
        var missed = parseMissed(receipt)
        var partial = parsePartial(receipt)

        // 富文本编辑器是**异步初始化**的：页面刚打开时实例还没建好，
        // 第一次写入可能落空。没填上的题等 400ms 再补一次，通常就成功了。
        if (missed.isNotEmpty()) {
            kotlinx.coroutines.delay(400)
            val retry = items.filter { it.qid in missed }
            if (retry.isNotEmpty()) {
                val again = evalJs(web, FillScript.build(retry)).orEmpty()
                val stillMissed = parseMissed(again)
                missed = missed - (retry.map { it.qid }.toSet() - stillMissed)
                // 补填后有空被填满的，从"部分完成"里去掉
                partial = partial - (retry.map { it.qid }.toSet() - parsePartial(again))
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
            // 多空题只填上了部分空：必须说出来，否则用户以为交上去就是满分
            val gapNote = if (q.qid in partial && solved != null) {
                val got = solved.parts.count { it.isNotBlank() }
                "（共 ${blanksOf(q, solved)} 空，只填上 $got 空，其余请手动补）"
            } else {
                null
            }
            AnswerRow(
                number = no,
                typeLabel = q.typeLabel.ifBlank { typeName(q.type) },
                // 不截断题干与答案：弹窗里本来就滚得动，截断只会让人看不到全貌
                stem = q.stem,
                answer = readable(q, value, solved?.note, solved?.parts.orEmpty()) +
                    (gapNote ?: "") + (lenNote ?: ""),
                ok = ok,
            )
        }

        val done = items.size - missed.size
        steps = steps.stepDone(3, "回填 $done/${items.size}")
        stage = Stage.Done
        generated = true
        phase = if (optimized) "优化完成：$done/${items.size} 题已更新" else "完成：回填 $done/${items.size} 题"
        stat = buildString {
            if (optimized) append("优化后回填 $done/${items.size} 题") else append("回填 $done/${items.size} 题")
            val noAnswer = planned.count { it.third?.value == null }
            if (noAnswer > 0) append("，$noAnswer 题 AI 没给答案")
            if (missed.isNotEmpty()) append("，${missed.size} 题页面没接受（可能需要手动填）")
            if (partial.isNotEmpty()) append("，${partial.size} 道填空题有空没填上")
            append("。核对后请自己点提交。")
        }
        message = stat
        return done
    }

    /**
     * 跑一轮作答。
     *
     * @param fresh true = 清空已答的、整卷重来；false = 只补上次没成功的题
     */
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
            steps = listOf(
                GenStep("读取作答页", GenStepState.NOW),
                GenStep("解析题目", GenStepState.WAIT),
                GenStep("AI 生成答案", GenStepState.WAIT),
                GenStep("回填到页面", GenStepState.WAIT),
            )
            try {
            if (fresh) {
                rows = emptyList()
                answerCache = emptyMap()
                // 整卷重来后，旧的撤销点与旧对话都失去意义，留着只会误导
                undoStack = emptyList()
                chat = if (chat.isEmpty()) chat else chat + ChatTurn(ChatRole.NOTE, "已重新生成，之前的要求不再作为上下文")
                generated = false
                reasoning = null
                stat = ""
            }

            stage = Stage.Reading
            phase = "读取作答页"
            message = "正在读取题目"
            val html = readHtml(web)
            steps = steps.stepDone(0).stepNow(1)
            val questions = QuestionParser.parse(html)
            // 缓存下来给多轮优化用：优化时页面可能已经变了，别重新解析
            questionsCache = questions
            if (questions.isEmpty()) {
                steps = steps.stepFail(1, "没解析到题目")
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

            steps = steps.stepDone(1).stepNow(2, "共 ${targets.size} 题")
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
                    steps = steps.stepFail(2, "接口失败")
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

            fillAndReport(web, questions, effective)
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
                steps = steps.stepFailNow()
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

        /*
         * 提交成功后的状态条。
         *
         * 为什么不只靠"把页面滚到顶部"：滚动是**页面的事**，页面结构一变就可能滚不动，
         * 而用户必须知道"交上去了"。所以 App 自己也要给一条明确结论，
         * 并且旁边留一个「回到顶部」——滚不动时他自己点一下也能到。
         */
        if (submitted) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.okSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons24.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Palette.ok,
                    )
                    Text(
                        text = "作业已提交，结果在页面最上方",
                        fontSize = 11.sp,
                        color = Palette.ok,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                    )
                }

                submitHint?.let { hint ->
                    Text(
                        text = hint,
                        fontSize = 10.sp,
                        color = Palette.sub,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

                /*
                 * 两个动作分开放，别挤成一个。
                 *
                 * 「显示完整内容」针对的是**页面自己把结果裁掉了**（老 PC 版把结果放在
                 * 固定高度 + overflow:hidden 的容器里，滚到哪儿都只有一半）；
                 * 「回到顶部」针对的是**视口还在底部**。两种原因不同，混在一个按钮里
                 * 会让用户以为点了没反应。
                 */
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubmitChip("显示完整内容") { unclipPage() }
                    SubmitChip("回到顶部") { scrollPageToTop() }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                                // 顺带上报滚动位置，用来决定要不要显示"回到顶部"浮动键
                                view?.evaluateJavascript(SCROLL_REPORT, null)
                                /*
                                 * 如果这一页是"提交后跳过来的结果页"，页面顶上来就已经有结论了，
                                 * 但 onPageFinished 时视口可能还停在上一个页面的位置——
                                 * 这里补一次滚动，别让用户对着中间一段发呆。
                                 *
                                 * 同时再撑一次被裁剪的容器：新页面是一份新的 DOM，
                                 * 上一页改过的样式不会带过来。
                                 */
                                if (submitted) {
                                    scrollPageToTop()
                                    unclipPage(quiet = true)
                                }
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
                                when {
                                    text.startsWith(TAG_SUBMITTED) -> onSubmitted()
                                    text.startsWith(TAG_SCROLL) ->
                                        pageScrolledDown = text.removePrefix(TAG_SCROLL).trim() == "1"
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

            /*
             * 回到顶部的浮动键。
             *
             * 作答页是 PC 版结构，一屏只放得下几道题，用户滚到最底下的提交按钮时
             * 已经离开页面顶部很远了。提交结果、得分都在顶部——**自动滚动可能失败
             * （页面把内容放在内部容器里、或者结构一变），但用户自己点一下永远能回去**。
             */
            if (pageScrolledDown && failed == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Palette.card.copy(alpha = 0.94f))
                        .clickable { scrollPageToTop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↑",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.sub,
                    )
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

    /** 撤销/优化开始时把四个阶段重置成"已完成前三步、正在回填" */
    fun stepPhaseReset() {
        steps = listOf(
            GenStep("读取作答页", GenStepState.DONE),
            GenStep("解析题目", GenStepState.DONE, "共 ${questionsCache.size} 题"),
            GenStep("AI 按你的要求修改", GenStepState.DONE),
            GenStep("回填到页面", GenStepState.NOW),
        )
    }

    /**
     * 一轮"按要求优化"。
     *
     * 流程刻意做得可预期：
     * 1. 输入为空 / 还没答案 / 没解析出题目 —— 直接在输入框下面给一句话，不发请求；
     * 2. 用户那句话先进对话记录（他能看到自己提了什么）；
     * 3. 把**当前答案**整卷丢给 AI 改，模型返回整卷 → 整体覆盖；
     * 4. 覆盖前先把旧版压进 [undoStack]，所以随时能撤销；
     * 5. 覆盖后立刻重新回填页面，答案区与网页里都是新的那版。
     */
    fun runOptimize() {
        if (generating) return
        val text = chatInput.trim()
        val web = webView
        when {
            text.isEmpty() -> {
                // 明确说"为什么没发出去"，而不是让发送键静默失败
                chatHint = "先输入一句要改什么，比如「简答再短一点」"
                return
            }
            answerCache.isEmpty() -> {
                chatHint = "还没有答案可以改，先点【开始作答】生成一版"
                return
            }
            questionsCache.isEmpty() -> {
                chatHint = "题目还没读到，先点【重新生成】读一次页面"
                return
            }
            web == null -> {
                chatHint = "作答页不在了，返回重进这一页再试"
                return
            }
        }

        chatHint = null
        val history = chat.filter { it.role == ChatRole.USER }.map { it.text }
        chat = chat + ChatTurn(ChatRole.USER, text)
        chatInput = ""

        scope.launch {
            sheetOpen = true
            generating = true
            steps = listOf(
                GenStep("读取作答页", GenStepState.DONE),
                GenStep("解析题目", GenStepState.DONE, "共 ${questionsCache.size} 题"),
                GenStep("AI 按你的要求修改", GenStepState.NOW),
                GenStep("回填到页面", GenStepState.WAIT),
            )
            try {
                phase = "AI 正在按你的要求修改"
                message = phase
                when (val r = ai.refine(questionsCache, answerCache, history, text)) {
                    is Cx.Ok -> {
                        // 只覆盖模型实际返回的题：没提到的题保住上一版，不会被空值冲掉
                        val merged = LinkedHashMap(answerCache)
                        r.data.answers.forEach { (qid, v) -> if (v.isNotEmpty()) merged[qid] = v }
                        undoStack = undoStack + answerCache
                        answerCache = merged
                        steps = steps.stepDone(2, "改了 ${r.data.answers.size} 题")
                        val done = fillAndReport(web, questionsCache, merged, optimized = true)
                        chat = chat + ChatTurn(
                            ChatRole.NOTE,
                            if (done >= 0) "已更新 ${r.data.answers.size} 题并回填，可以继续提要求"
                            else "AI 改完了，但页面没接受任何答案，看看是不是已经交卷",
                        )
                    }
                    is Cx.Fail -> {
                        steps = steps.stepFailNow()
                        phase = "优化失败"
                        chat = chat + ChatTurn(ChatRole.NOTE, "这次没改成：${r.detail}")
                    }
                    Cx.NeedLogin -> {
                        steps = steps.stepFailNow()
                        phase = "优化失败"
                        chat = chat + ChatTurn(ChatRole.NOTE, "优化不需要登录态，请检查 AI 配置")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                steps = steps.stepFailNow()
                phase = "优化出错"
                chat = chat + ChatTurn(
                    ChatRole.NOTE,
                    "优化出错：${e.message ?: e::class.java.simpleName}（答案还是上一版，没被改坏）",
                )
            } finally {
                generating = false
            }
        }
    }

    /**
     * 撤销上一轮优化。
     *
     * 只回退**答案**，不回退对话记录——用户要能看到"我撤销了什么"，
     * 否则点一下画面变了、记录里还写着"已更新 3 题"，看着像出错。
     */
    fun undoOptimize() {
        if (generating) return
        val prev = undoStack.lastOrNull() ?: return
        val web = webView ?: return
        undoStack = undoStack.dropLast(1)
        answerCache = prev
        chat = chat + ChatTurn(ChatRole.NOTE, "已撤销上一轮优化，回到之前那版答案")
        scope.launch {
            sheetOpen = true
            generating = true
            try {
                stepPhaseReset()
                fillAndReport(web, questionsCache, prev, optimized = true)
            } finally {
                generating = false
            }
        }
    }

    /*
     * AI 作答窗口（0.9.0 起是居中弹窗；0.9.1 把"改提示词"换成了对话式优化）。
     *
     * 这里只把状态与回调接起来：状态、生成过程、思考过程、逐题结果、优化对话都在弹窗里。
     */
    if (sheetOpen) {
        AiAnswerModal(
            phase = phase,
            stat = stat,
            modelLabel = "当前模型：$activeLabel",
            steps = steps,
            elapsedSec = elapsedSec,
            reasoning = reasoning,
            rows = rows,
            generating = generating,
            chat = chat,
            chatInput = chatInput,
            chatHint = chatHint,
            canUndo = undoStack.isNotEmpty(),
            onChatInput = {
                chatInput = it
                // 一动手就清掉上一次的"没输入"提示，别让红字一直挂着
                if (chatHint != null) chatHint = null
            },
            onSend = { runOptimize() },
            onUndo = { undoOptimize() },
            onRegenerate = { runGenerate(fresh = true) },
            onDismiss = { sheetOpen = false },
        )
    }

    // ---------------- 模型切换 ----------------
    if (showAiSwitch) {
        AiSwitchDialog(
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
 * 阶段推进的三个小工具。
 *
 * 用不可变 List 复制而不是可变状态：弹窗每帧读的都是同一份快照，
 * 不会出现"列表在遍历时被改了"的诡异现象。
 */
internal fun List<GenStep>.stepDone(i: Int, detail: String = ""): List<GenStep> =
    mapIndexed { idx, s ->
        if (idx == i) s.copy(state = GenStepState.DONE, detail = detail.ifBlank { s.detail }) else s
    }

internal fun List<GenStep>.stepNow(i: Int, detail: String = ""): List<GenStep> =
    mapIndexed { idx, s -> if (idx == i) s.copy(state = GenStepState.NOW, detail = detail) else s }

internal fun List<GenStep>.stepFail(i: Int, detail: String = ""): List<GenStep> =
    mapIndexed { idx, s -> if (idx == i) s.copy(state = GenStepState.FAIL, detail = detail) else s }

/** 把"当前进行中"的那一步标成失败；没有进行中的就不动 */
internal fun List<GenStep>.stepFailNow(): List<GenStep> =
    map { s -> if (s.state == GenStepState.NOW) s.copy(state = GenStepState.FAIL) else s }

/**
 * 字数接近度：合格的排最前，其余按字数比（字数越多越接近下限）。
 *
 * 用它来比较"两次重写哪份更好"，避免越重写越短。
 */
private fun lengthScore(text: String, rule: LengthRule): Int {
    if (text.isBlank()) return -1
    return if (rule.fits(text)) Int.MAX_VALUE else LengthRules.count(text)
}

/**
 * 把提交值翻译成人话，方便核对。
 *
 * 这里**不做截断**。之前简答/填空只给前 60 字，用户想核对第 3 段写了什么就得去网页里翻；
 * 现在整段给出来，长了由弹窗滚动——"看得全"比"行矮"重要。
 */
private fun readable(
    q: Question,
    value: String?,
    note: String?,
    parts: List<String> = emptyList(),
): String {
    if (value == null) return note ?: "无答案"
    return when (q.type) {
        QType.SINGLE, QType.MULTIPLE -> value
        QType.JUDGE -> if (value == "true") "对" else "错"
        // 多空题一行一空显示：用户核对时要能一眼看出"第几空填的是什么"
        QType.FILL -> if (parts.size > 1) {
            parts.mapIndexed { i, p -> "第${i + 1}空：${p.ifBlank { "（空）" }}" }.joinToString("\n")
        } else {
            value
        }
        else -> value
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

/** 从 "FILLED:3/5 MISSED:1,2 PARTIAL:9" 里取出"只填了一部分空"的 qid */
internal fun parsePartial(receipt: String?): Set<String> {
    if (receipt.isNullOrBlank()) return emptySet()
    val idx = receipt.indexOf("PARTIAL:")
    if (idx < 0) return emptySet()
    return receipt.substring(idx + "PARTIAL:".length)
        .trim()
        .split(',')
        .filter { it.isNotBlank() }
        .toSet()
}

/**
 * 文本题（填空/简答）的最后一道清洗闸。
 *
 * 选择题的答案只有字母，不存在"把提示词抄进去"的问题；文本题才是重灾区。
 * 复用 [AnswerSanitizer]：剥外壳 → 剔掉复述要求的行 → 仍然像要求/照抄题干的一律丢弃。
 *
 * 填空题**按空逐条清洗**：整题拼起来洗会把"某空像复述"扩大成"整题无答案"，
 * 而且空与空之间本来就不该互相影响。
 */
private fun solveTextGuard(q: Question, solved: SolvedAnswer?): SolvedAnswer? {
    val v = solved?.value ?: return solved
    if (q.type != QType.SHORT && q.type != QType.FILL) return solved

    if (q.type == QType.FILL && solved.parts.isNotEmpty()) {
        val cleaned = solved.parts.map { p ->
            if (p.isBlank()) "" else AnswerSanitizer.sanitize(p, q.stem).orEmpty()
        }
        if (cleaned.none { it.isNotBlank() }) {
            return SolvedAnswer(q.qid, null, "答案像是在复述要求或照抄题干，已丢弃（请手动补答）")
        }
        if (cleaned == solved.parts) return solved
        return SolvedAnswer(
            qid = q.qid,
            value = cleaned.filter { it.isNotBlank() }.joinToString(" "),
            note = solved.note,
            parts = cleaned,
        )
    }

    val cleaned = AnswerSanitizer.sanitize(v, q.stem)
        ?: return SolvedAnswer(q.qid, null, "答案像是在复述要求或照抄题干，已丢弃（请手动补答）")
    return if (cleaned == v) solved else SolvedAnswer(q.qid, cleaned, solved.note)
}

/** 提交状态条上的小按钮（浅色底 + 品牌色字），两个并排放 */
@Composable
private fun SubmitChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.brand,
        )
    }
}

/**
 * 回填用的分量。
 *
 * 填空必须用 [SolvedAnswer.parts]（一空一个元素）；拿拼合串回填会把所有答案
 * 塞进第 1 个框——多空题填不对的根本原因就在这里。
 */
private fun valuesOf(q: Question, solved: SolvedAnswer, value: String): List<String> = when (q.type) {
    QType.MULTIPLE -> solved.parts.ifEmpty { value.uppercase().map { it.toString() } }
    QType.FILL -> solved.parts.ifEmpty { listOf(value) }
    else -> listOf(value)
}

/**
 * 填空题的空数。
 *
 * **以页面 `tiankongsize` 为准**（页面知道真实有几空），解析不到（0）时才退回答案个数。
 * 反过来（用答案个数顶掉页面）会出事：单空题模型偶尔拆成 4 句，就会被误判成 4 空题。
 */
private fun blanksOf(q: Question, solved: SolvedAnswer): Int =
    if (q.type == QType.FILL) {
        (if (q.blanks > 0) q.blanks else solved.parts.size).coerceAtLeast(1)
    } else {
        1
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
