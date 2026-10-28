package com.xxt.study.feature.study

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import com.xxt.study.core.net.Http
import com.xxt.study.core.study.StudyController
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.Course

/**
 * 刷课页。
 *
 * 结构：**进度卡 + 最近日志**，课程页面 / 完整日志 / AI 过程都收进小窗。
 *
 * ⚠️ 这里踩过一个坑，写在最前面：
 * 原来「实时页面 / 运行日志」是两个互斥的分支，切到日志时那个 `AndroidView` 会离开组合，
 * 再切回来时 `factory` 重新执行、`loadUrl` 再跑一遍 —— 用户点一次"实时页面"就等于刷新一次网页，
 * 视频进度、滚动位置全丢，影响正常操作。
 *
 * 现在 WebView 用 `remember` 建成**一份**，从进入页面活到离开页面；
 * 开关小窗只是把它挂到另一个 ViewGroup 再挂回来，**全程不再调 loadUrl**。
 * 想重新加载有明确的「刷新」按钮，是用户主动行为。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    course: Course,
    controller: StudyController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    var showWeb by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }

    // 课程主页：会 302 到 stu 页面，能看到章节列表和任务点状态
    val courseUrl = remember(course) {
        "https://mooc1.chaoxing.com/visit/stucoursemiddle" +
            "?courseid=${course.courseId}&clazzid=${course.classId}" +
            "&cpi=${course.cpi}&ismooc2=1&v=2"
    }

    // 章节测验是全自动跑的：把和 AI 有关的记录挑出来，单独给一个窗口看
    val aiLines = state.log.filter {
        it.contains("测验") || it.contains("作答") || it.contains("提交") ||
            it.contains("解析到") || it.contains("回填") || it.contains("AI")
    }

    /*
     * 页面实例只建一次。注意不能在 factory 里 loadUrl —— 那正是"每次点开都刷新"的来源。
     */
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
            settings.setSupportMultipleWindows(false)
            // PC 页面字小，允许双指放大
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            webViewClient = object : WebViewClient() {
                // 非 http(s) 一律拦下，别把页面里的 scheme 抛给系统
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webError = null
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

    // 第一次打开小窗才真正发请求：没看过就不花这趟流量
    LaunchedEffect(showWeb) {
        if (showWeb && !loaded) {
            loaded = true
            webView.loadUrl(courseUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        // 返回只是离开页面，任务还在前台服务里跑；不说一声用户会以为点了返回就停了
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
            Column(Modifier.padding(start = 4.dp)) {
                Text(
                    text = course.name.ifBlank { "未命名课程" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Text(
                    text = if (state.running) "刷课中，可切后台" else "未运行",
                    fontSize = 11.sp,
                    color = if (state.running) Palette.ok else Palette.weak,
                )
            }
        }

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
                text = state.statusLine,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Palette.brand,
                trackColor = Palette.line,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Count("完成", state.done, Palette.ok)
                Count("失败", state.failed, if (state.failed > 0) Palette.alarm else Palette.weak)
                Count("跳过", state.skipped, Palette.weak)
            }

            Spacer(Modifier.height(12.dp))
            Row {
                if (state.running) {
                    Pill(text = "停止", primary = false, onClick = controller::stop)
                } else {
                    Pill(
                        text = if (state.finished || state.done + state.failed > 0) "重新开始" else "开始刷课",
                        primary = true,
                        onClick = { controller.start(course) },
                    )
                }
            }

            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(text = it, fontSize = 12.sp, color = Palette.alarm, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 三个入口，都开小窗（不切换主内容，所以不会重载网页） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Entry("课程页面") { showWeb = true }
            Entry("全部日志") { showLog = true }
            Entry("AI 过程", highlighted = aiLines.isNotEmpty()) { showAi = true }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 主区域：最近几条日志，扫一眼就知道跑到哪了 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.card)
                .padding(14.dp),
        ) {
            if (state.log.isEmpty()) {
                Text(
                    text = "开始后这里会逐条出现任务点的处理结果。\n" +
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
                    items(state.log.takeLast(60).reversed()) { line ->
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

        Spacer(Modifier.height(12.dp))
    }

    // ---------------- 课程页面小窗 ----------------
    if (showWeb) {
        ModalBottomSheet(
            onDismissRequest = { showWeb = false },
            containerColor = Palette.card,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "课程页面",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    TinyAction("刷新") {
                        webError = null
                        webView.reload()
                    }
                    TinyAction("关闭") { showWeb = false }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight * 0.72f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.bg),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            // 小窗反复开关会把这个 View 挂到不同的父容器上，
                            // 挂之前先摘掉旧父级，否则会抛 "already has a parent"
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            webView
                        },
                    )
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
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "这是你自己会话下的课程页，只用于查看，不参与上报；上报仍走后台请求，两者互不影响。",
                    fontSize = 10.sp,
                    color = Palette.weak,
                    lineHeight = 15.sp,
                )
            }
        }
    }

    // ---------------- 全部日志小窗 ----------------
    if (showLog) {
        ModalBottomSheet(
            onDismissRequest = { showLog = false },
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
                    Text("运行日志", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
                    Text(" · ${state.log.size} 条", fontSize = 11.sp, color = Palette.weak)
                    Spacer(Modifier.weight(1f))
                    TinyAction("关闭") { showLog = false }
                }
                Spacer(Modifier.height(8.dp))
                if (state.log.isEmpty()) {
                    Text("还没有记录。", fontSize = 12.sp, color = Palette.weak)
                } else {
                    LazyColumn(modifier = Modifier.height(screenHeight * 0.6f)) {
                        items(state.log) { line ->
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
    }

    // ---------------- AI 过程小窗 ----------------
    if (showAi) {
        ModalBottomSheet(
            onDismissRequest = { showAi = false },
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
                    Text("AI 处理过程", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
                    Spacer(Modifier.weight(1f))
                    TinyAction("关闭") { showAi = false }
                }
                Spacer(Modifier.height(8.dp))
                if (aiLines.isEmpty()) {
                    Text(
                        text = "还没有 AI 相关记录。\n章节测验任务点开始后，这里会显示：解析题目 → AI 作答 → 回填 → 提交。",
                        fontSize = 11.sp,
                        color = Palette.weak,
                        lineHeight = 17.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(screenHeight * 0.5f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(aiLines.takeLast(80)) { line ->
                            Text(text = line, fontSize = 11.sp, color = Palette.sub, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

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

/** 小窗标题栏上的文字按钮 */
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
