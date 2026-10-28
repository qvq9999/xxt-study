package com.xxt.study.feature.verify

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.ui.Palette

/**
 * 人工验证浮层。
 *
 * 用**学习通原版页面**让用户自己完成验证，而不是自己画一个验证码框：
 * 风控的题型（图形码、滑块、点选）由服务端下发，只有原版页面才能正确渲染和签名，
 * 自己复刻必然对不上。
 *
 * 验证完成后要做两件事，缺一不可：
 * 1. 把 WebView 的 Cookie **导回 [CookieStore]** —— OkHttp 走的是自己的 CookieJar，
 *    看不到 WebView 里新落下的风控 Cookie，不导回的话验证通过了后面的请求照样被拒；
 * 2. 通知业务方重试（见 [com.xxt.study.core.net.VerifyGate.markPassed]）。
 *
 * 刻意不做"自动识别是否通过"：验证页和我方页面长得很像，自动判定一旦误判，
 * 就会在用户还没输完时把窗口关掉。宁可让用户点一下「已完成验证」。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyDialog(
    url: String,
    cookies: CookieStore,
    onPassed: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 触发风控的是 PC 版请求，这里也要用同一套 UA，否则拿到的不是同一份会话
            settings.userAgentString = Http.UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
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
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    loading = false
                }
            }
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
        // 导 Cookie + 标记完成，逻辑统一放在 VerifyGate，避免这几处各写一份
        val n = VerifyGate.finish(cookies, url)
        importing = false
        if (n > 0) {
            Toast.makeText(ctx, "验证凭据已同步（$n 条），正在继续", Toast.LENGTH_SHORT).show()
        }
        onPassed()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 10.dp, vertical = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.card),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "需要完成验证",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.ink,
                        )
                        Spacer(Modifier.weight(1f))
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "学习通触发了风控，需要你在这个页面里手动完成验证（输验证码 / 拖滑块）。" +
                            "下面是学习通原版页面，做完后点底部的「已完成验证，继续」。",
                        fontSize = 11.sp,
                        color = Palette.sub,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Palette.bg),
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
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.brandSoft)
                            .clickable { webView.reload() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("重新加载", fontSize = 13.sp, color = Palette.brand)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (importing) Palette.line else Palette.brand)
                            .clickable(enabled = !importing) { finish() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (importing) "同步中…" else "已完成验证，继续",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (importing) Palette.weak else Palette.card,
                        )
                    }
                }
            }
        }
    }
}
