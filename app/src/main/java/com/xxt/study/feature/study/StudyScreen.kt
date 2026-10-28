package com.xxt.study.feature.study

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.study.StudyController
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.Course
import com.xxt.study.domain.key
import com.xxt.study.feature.ai.AiSwitchSheet

/**
 * 刷课页。
 *
 * ## 课程页面为什么不做成弹窗
 *
 * 之前用 `ModalBottomSheet`：下滑手势和网页滚动抢，页面往下滑窗口跟着跳。
 * 改成 `Dialog`：不再跳了，但**点不动**——WebView 需要一个正常的宿主窗口，
 * 放进 Dialog 再反复摘挂，触摸事件会丢；而且当时没设 `WebChromeClient`，
 * 学习通那种 `window.open` / `alert` 驱动的"进入章节"会被静默丢弃。
 *
 * 现在是**同屏分层**：WebView 从进入页面到离开页面一直在组合里，
 * 只切 `visibility`。不摘挂、不重载、不换窗口，触摸走的就是最普通的那条路。
 *
 * ## 日志按课程隔离
 *
 * `StudyUiState.logs` 以课程为键。以前只有一份全局日志，刷完 A 课点开 B 课
 * 看到的是 A 的记录和计数，人会以为 B 已经在跑。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    course: Course,
    controller: StudyController,
    ai: AiClient,
    cookies: CookieStore,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val key = remember(course) { course.key }
    // 只有"当前任务就是这门课"时，进度和计数才是这门的
    val mine = state.belongsTo(key)
    val log = state.logFor(key)

    /** true = 显示课程网页，false = 显示日志。切换只改可见性，不销毁 WebView */
    var webMode by remember { mutableStateOf(false) }
    var showAiLog by remember { mutableStateOf(false) }
    var showAiSwitch by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    /** 可见页面里出现了验证码（我们自己检测，不依赖隐藏扫描） */
    var pageVerify by remember { mutableStateOf(false) }

    // 模型信息要读密钥库，用版本号缓存，别在每帧重组里读
    var aiTick by remember { mutableStateOf(0) }
    val savedModels = remember(aiTick) { ai.savedProviders() }
    val activeId = remember(aiTick) { ai.activeProviderId() }
    val activeLabel = remember(aiTick) { ai.activeSaved()?.label ?: "未配置模型" }

    val aiLines = log.filter {
        it.contains("测验") || it.contains("作答") || it.contains("提交") ||
            it.contains("解析到") || it.contains("回填") || it.contains("AI")
    }

    val courseUrl = remember(course) {
        "https://mooc1.chaoxing.com/visit/stucoursemiddle" +
            "?courseid=${course.courseId}&clazzid=${course.classId}" +
            "&cpi=${course.cpi}&ismooc2=1&v=2"
    }

    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 数据页是 PC 版结构，必须用 PC UA
            settings.userAgentString = Http.UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 学习通有些入口是 window.open 出来的，必须允许，否则点了没反应
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // PC 页面字小，允许双指放大
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webError = null
                    pageVerify = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // 在我们自己看得见的这个页面里也查一遍风控：验证码就长在这儿，
                    // 只靠隐藏扫描的探针是看不见的
                    view?.evaluateJavascript(VERIFY_PROBE) { v ->
                        pageVerify = v?.contains('1') == true
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    webError = error?.description?.toString().orEmpty().ifBlank { "页面加载失败" }
                }
            }

            /*
             * 必须设 WebChromeClient。
             *
             * 不设的时候：alert/confirm 被静默吞掉，window.open 直接返回 null。
             * 学习通"点章节 → 打开视频页"这类交互大量依赖它们，表现就是"页面点不动"。
             */
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val host = view ?: return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    // 不新开窗口（那会变成另一个界面/浏览器），让新窗口的内容在本 WebView 里打开
                    val temp = WebView(host.context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                request?.url?.toString()?.let { host.loadUrl(it) }
                                runCatching { v?.destroy() }
                                return true
                            }
                        }
                    }
                    transport.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    // 第一次进网页模式才真正发请求：没看过就不花这趟流量
    LaunchedEffect(webMode) {
        if (webMode && !loaded) {
            loaded = true
            webView.loadUrl(courseUrl)
        }
    }

    // 网页模式下返回键先退回日志，而不是直接退出刷课页
    BackHandler(enabled = webMode) { webMode = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        if (webMode) {
                            webMode = false
                            return@clickable
                        }
                        // 返回只是离开页面，任务还在前台服务里跑
                        if (state.running) {
                            Toast.makeText(ctx, "刷课在后台继续，通知栏可查看进度", Toast.LENGTH_SHORT).show()
                        }
                        onBack()
                    },
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
                    text = course.name.ifBlank { "未命名课程" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                    maxLines = 1,
                )
                Text(
                    text = when {
                        webMode -> "课程页面 · 仅供查看"
                        mine && state.running -> "刷课中，可切后台"
                        !mine && state.running -> "另一门课正在刷"
                        else -> "未运行"
                    },
                    fontSize = 11.sp,
                    color = if (state.running) Palette.ok else Palette.weak,
                )
            }
            if (webMode) {
                TinyAction("刷新页面") {
                    webError = null
                    webView.reload()
                }
            } else {
                // 快捷切换模型：不用为了换个模型退回设置页
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.card)
                        .clickable { showAiSwitch = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "AI · $activeLabel",
                        fontSize = 11.sp,
                        color = if (savedModels.isEmpty()) Palette.weak else Palette.sub,
                        maxLines = 1,
                    )
                }
            }
        }

        if (!webMode) {
            // ---- 状态卡 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.card)
                    .padding(14.dp),
            ) {
                Text(
                    text = when {
                        mine -> state.statusLine
                        state.running -> "另一门课正在刷：${state.courseName}"
                        log.isNotEmpty() -> "本课未在运行（下面是上一次的记录）"
                        else -> "本课未开始"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (mine) state.fraction.coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Palette.brand,
                    trackColor = Palette.line,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Count("完成", if (mine) state.done else 0, Palette.ok)
                    Count(
                        "失败",
                        if (mine) state.failed else 0,
                        if (mine && state.failed > 0) Palette.alarm else Palette.weak,
                    )
                    Count("跳过", if (mine) state.skipped else 0, Palette.weak)
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.running) {
                        Pill(text = "停止", primary = false, onClick = controller::stop)
                    } else {
                        Pill(
                            text = when {
                                mine && (state.finished || state.done + state.failed > 0) -> "重新开始"
                                log.isNotEmpty() -> "重新开始"
                                else -> "开始刷课"
                            },
                            primary = true,
                            onClick = { controller.start(course) },
                        )
                    }
                }

                if (mine) {
                    state.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(text = it, fontSize = 12.sp, color = Palette.alarm, lineHeight = 18.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 入口：课程页面 / AI 过程（日志已在正文，不再单开入口） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Entry("课程页面") { webMode = true }
                Entry("AI 过程", highlighted = aiLines.isNotEmpty()) { showAiLog = true }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 可见页面里撞上风控：就地提示，不用再弹一层窗
        if (webMode && pageVerify) {
            VerifyBanner(
                onSynced = { n ->
                    pageVerify = false
                    val tip = if (n > 0) "验证凭据已同步（$n 条），可以继续了" else "已标记完成，可以继续了"
                    Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                },
                cookies = cookies,
                url = courseUrl,
            )
            Spacer(Modifier.height(8.dp))
        }

        // ---- 正文：日志 / 网页（同一个位置，只切可见性） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = if (webMode) 0.dp else 20.dp)
                .clip(if (webMode) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp))
                .background(Palette.card),
        ) {
            if (!webMode) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "运行日志",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.sub,
                        )
                        Text(
                            text = if (log.isEmpty()) "" else " · ${log.size} 条",
                            fontSize = 10.sp,
                            color = Palette.weak,
                        )
                        Spacer(Modifier.weight(1f))
                        if (log.isNotEmpty()) {
                            Text("最新在上", fontSize = 10.sp, color = Palette.weak)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (log.isEmpty()) {
                        Text(
                            text = "这门课还没有运行记录。点「开始刷课」后会逐条出现任务点的处理结果。\n" +
                                "想看课程页的实际播放情况，点上面的「课程页面」。",
                            fontSize = 12.sp,
                            color = Palette.weak,
                            lineHeight = 18.sp,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(log) { line ->
                                Text(
                                    text = line,
                                    fontSize = 12.sp,
                                    color = when {
                                        line.startsWith("✗") -> Palette.alarm
                                        line.startsWith("✓") -> Palette.ok
                                        line.startsWith("▶") -> Palette.brand
                                        else -> Palette.sub
                                    },
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }

            /*
             * 这个 AndroidView **永远在组合里**，只切可见性。
             * 一旦让它随模式进出组合，WebView 就会被销毁重建 —— 页面白加载一次，
             * 而且重新挂到新父容器上之后触摸容易失效（"点不动"就是这么来的）。
             */
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                update = { it.visibility = if (webMode) View.VISIBLE else View.GONE },
            )

            if (webMode) {
                webError?.let { reason ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("页面没打开", fontSize = 14.sp, color = Palette.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(reason, fontSize = 11.sp, color = Palette.sub, lineHeight = 17.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    // ---------------- AI 过程 ----------------
    if (showAiLog) {
        ModalBottomSheet(
            onDismissRequest = { showAiLog = false },
            containerColor = Palette.card,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI 处理过程",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    TinyAction("关闭") { showAiLog = false }
                }
                Spacer(Modifier.height(8.dp))
                if (aiLines.isEmpty()) {
                    Text(
                        text = "这门课还没有 AI 相关记录。\n章节测验任务点开始后，这里会显示：解析题目 → AI 作答 → 回填 → 提交。",
                        fontSize = 11.sp,
                        color = Palette.weak,
                        lineHeight = 17.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(screenHeight * 0.5f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(aiLines) { line ->
                            Text(text = line, fontSize = 11.sp, color = Palette.sub, lineHeight = 17.sp)
                        }
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
                Toast.makeText(ctx, "已切换到 ${picked.label} · ${picked.model}", Toast.LENGTH_SHORT).show()
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
 * 可见页面里的风控提示条。
 *
 * 为什么不直接弹 [com.xxt.study.feature.verify.VerifyDialog]：用户已经站在验证页上了，
 * 再盖一层弹窗纯属多余。这里只需要一个"验证完了点我"的入口，负责把 Cookie 导回 OkHttp。
 */
@Composable
private fun VerifyBanner(
    cookies: CookieStore,
    url: String,
    onSynced: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.alarmSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "页面要求验证码",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.alarm,
            )
            Text(
                text = "在上面的页面里完成验证后点右边，凭据会同步过来",
                fontSize = 10.sp,
                color = Palette.sub,
                lineHeight = 15.sp,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Palette.brand)
                .clickable { onSynced(VerifyGate.finish(cookies, url)) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("已完成验证", fontSize = 12.sp, color = Palette.card)
        }
    }
}

/** 页面内风控探测：命中返回 '1' */
private const val VERIFY_PROBE =
    "(function(){var t=(document.body?document.body.innerText:'').slice(0,4000);" +
        "return /请(?:输入|填写).{0,8}验证码|拖动.{0,6}滑块|滑动验证|人机验证|安全验证|完成验证/i" +
        ".test(t)?'1':'0';})()"

/** 小窗入口按钮 */
@Composable
private fun Entry(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlighted) Palette.brandSoft else Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlighted) Palette.brand else Palette.sub,
        )
    }
}

/** 标题栏上的文字按钮 */
@Composable
private fun TinyAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Palette.brand,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Count(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$value", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = color)
        Text(
            text = label,
            fontSize = 11.sp,
            color = Palette.weak,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Palette.brand else Palette.brandSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Palette.card else Palette.brand,
        )
    }
}
