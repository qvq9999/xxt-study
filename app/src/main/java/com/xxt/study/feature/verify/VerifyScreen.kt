package com.xxt.study.feature.verify

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.ui.Palette

/**
 * 人工验证页。
 *
 * ## 为什么是整页而不是弹窗
 *
 * 最早这里是 `Dialog` + WebView。实测**渲染出来是一片白**：WebView 需要一个正常的
 * 宿主窗口，塞进 Dialog 窗口里加载不出内容（同一个坑在刷课页的课程页上也踩过）。
 * 现在改成和普通页面一样直接铺在 Activity 的窗口里，由 MainActivity 在
 * "需要验证"时整页替换——验证本来就是阻塞态，整页也说得通。
 *
 * ## 为什么不再自己判断"验证是否通过"
 *
 * 验证页和我方页面长得像，自动判定一旦误判就会在用户还没输完时把页面切走。
 * 所以只给一个「已完成验证，继续」的按钮，由人来判断。
 *
 * ## 白屏兜底
 *
 * 触发验证的地址有时候是个接口（课程列表 JSON、视频上报），打开就是空白。
 * 除了 [VerifyGate] 在入口处把接口地址换成首页外，这里再加一道：
 * 页面加载完如果几乎没有文字，自动改打开学习通首页一次。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyScreen(
    url: String,
    cookies: CookieStore,
    onPassed: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var blank by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var switchedToHome by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 触发风控的是 PC 版请求，这里用同一套 UA，拿到的才是同一份会话
            settings.userAgentString = Http.UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
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

                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    loading = true
                    pageError = null
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    loading = false
                    /*
                     * 三种结果：
                     *  V = 就是验证页（哪怕文字很少也不能当空白处理，否则会把验证码页切走）
                     *  E = 几乎没文字，多半是把接口地址当页面打开了
                     *  K = 正常页面
                     */
                    view?.evaluateJavascript(VERIFY_OR_BLANK) { v ->
                        when (v?.trim('"')) {
                            "E" -> {
                                blank = true
                                if (!switchedToHome) {
                                    switchedToHome = true
                                    view.loadUrl(VerifyGate.FALLBACK_URL)
                                }
                            }
                            "V" -> blank = false
                            else -> blank = false
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    loading = false
                    pageError = error?.description?.toString().orEmpty().ifBlank { "页面加载失败" }
                }
            }

            // 有了它 alert/confirm 才会弹，window.open 也不会被静默丢弃
            webChromeClient = WebChromeClient()

            loadUrl(url)
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

    fun finish() {
        importing = true
        // 导 Cookie + 标记完成，逻辑统一在 VerifyGate
        val n = VerifyGate.finish(cookies, url)
        importing = false
        if (n > 0) {
            Toast.makeText(ctx, "验证凭据已同步（$n 条），正在继续", Toast.LENGTH_SHORT).show()
        }
        onPassed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding()
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "需要完成验证",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Text(
                    text = "学习通触发了风控。这是原版页面，手动完成验证后点底部按钮。",
                    fontSize = 11.sp,
                    color = Palette.sub,
                    lineHeight = 16.sp,
                )
            }
            Text(
                text = "取消",
                fontSize = 12.sp,
                color = Palette.weak,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.card),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
            )

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = Palette.brand,
                    trackColor = Palette.line,
                )
            }

            val problem = pageError ?: if (blank) "这个地址打开是空白的" else null
            problem?.let { reason ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("页面没打开", fontSize = 14.sp, color = Palette.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(reason, fontSize = 11.sp, color = Palette.sub, lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Palette.brandSoft)
                            .clickable {
                                blank = false
                                pageError = null
                                switchedToHome = true
                                webView.loadUrl(VerifyGate.FALLBACK_URL)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("打开学习通首页", fontSize = 12.sp, color = Palette.brand)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.brandSoft)
                    .clickable {
                        blank = false
                        pageError = null
                        webView.reload()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("重新加载", fontSize = 12.sp, color = Palette.brand)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (importing) Palette.line else Palette.brand)
                    .clickable(enabled = !importing) { finish() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (importing) "同步中…" else "已完成验证，继续",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (importing) Palette.weak else Palette.card,
                )
            }
        }

        Text(
            text = "当前地址：${url.take(80)}",
            fontSize = 9.sp,
            color = Palette.weak,
            maxLines = 1,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
    }
}

/** 判断当前页是"验证页 / 空白 / 正常"，见 onPageFinished */
private const val VERIFY_OR_BLANK =
    "(function(){var t=(document.body?document.body.innerText:'').trim();" +
        "if(/请(?:输入|填写).{0,8}验证码|拖动.{0,8}滑块|滑动验证|人机验证|请.{0,4}(?:安全验证|完成验证)/i" +
        ".test(t)) return 'V';" +
        "return t.length<12?'E':'K';})()"
