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
    // 所有状态都跟着 url 走：换了一个验证地址必须从头开始，
    // 否则会拿着上一页的"已加载完"状态去显示新页面
    var loading by remember(url) { mutableStateOf(true) }
    var blank by remember(url) { mutableStateOf(false) }
    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var switchedToHome by remember(url) { mutableStateOf(false) }
    var importing by remember(url) { mutableStateOf(false) }

    val webView = remember(url) {
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

    /*
     * 跟着 webView 走而不是 Unit：url 变了会重建 WebView，旧的那个必须销毁，
     * 否则每换一次验证地址就漏一个 WebView（各自还带着一份会话）。
     */
    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    /**
     * 用户点了「已完成验证，继续」。
     *
     * 先问页面一句"你还在等验证码吗"再放行。
     *
     * 这是"验证码页无限循环"的第二道防线：用户很可能只点了我们这颗按钮，
     * 却没在学习通页面上**提交**验证码（输完就等着，以为点这里就等于提交）。
     * 那样 Cookie 根本没变，一继续必然又被拦，再弹一次同样的页面 —— 死循环。
     * 与其让他反复试，不如直接告诉他"验证码还没提交"。
     */
    fun finish() {
        if (importing) return
        importing = true
        webView.evaluateJavascript(STILL_WAITING) { raw ->
            if (raw?.trim('"') == "PEND") {
                importing = false
                Toast.makeText(
                    ctx,
                    "验证码还没提交：请先在页面里输入并点「确认/提交」，再点这里",
                    Toast.LENGTH_LONG,
                ).show()
                return@evaluateJavascript
            }
            // 导 Cookie + 标记完成，逻辑统一在 VerifyGate。
            //
            // 传**当前页面地址**而不是进来时那个 url：风控验证常常会把页面导到
            // passport2 之类的域上完成，新会话落在那个域；拿旧的触发地址去合并，
            // 会让失效的旧 JSESSIONID 覆盖掉刚拿到的新会话（"验证完还是用不了"的根因之一）。
            val n = VerifyGate.finish(cookies, webView.url ?: url)
            importing = false
            if (n > 0) {
                Toast.makeText(ctx, "验证凭据已同步（$n 条），正在继续", Toast.LENGTH_SHORT).show()
            }
            onPassed()
        }
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

/**
 * 点「继续」前的复检：页面是不是还停在"等你输验证码"的状态。
 *
 * 返回：
 * - `OK`   可以放行（不是验证页 / 已经验证成功 / 判断不出来）
 * - `PEND` 还在等验证码输入，别放行
 *
 * 宁可漏判也不误判：判不出来一律 `OK`。
 * 如果这里误判成 PEND，用户会卡在验证页出不去，那比多弹一次还难受——
 * 所以只在"页面明确是验证页"且"仍存在可见输入控件/滑块"时才拦。
 */
private const val STILL_WAITING =
    "(function(){try{" +
        "var t=(document.body?document.body.innerText:'').slice(0,4000);" +
        // 1) 明确成功 -> 放行
        "if(/验证成功|验证通过|校验通过|操作成功/.test(t)) return 'OK';" +
        // 2) 不是验证页 -> 放行（正常页面上也有搜索框，不能见到 input 就拦）
        "if(!/验证码|滑块|安全验证|人机验证/.test(t)) return 'OK';" +
        // 3) 是验证页：看还有没有可见的输入框或滑块容器
        "function vis(el){try{var r=el.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;" +
        "var s=getComputedStyle(el);return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}catch(e){return false;}}" +
        "var ins=document.querySelectorAll('input:not([type=hidden])');" +
        "for(var i=0;i<ins.length;i++){if(vis(ins[i])) return 'PEND';}" +
        "if(document.querySelector('.nc-container,.nc_wrapper,.slide-verify,.verify-slider,[class*=captcha]')) return 'PEND';" +
        "return 'OK';}catch(e){return 'OK';}})()"
