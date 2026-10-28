package com.xxt.study.feature.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xxt.study.App
import com.xxt.study.core.net.Http
import com.xxt.study.core.result.Cx
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 登录页：把学习通的**手机版**网页登录嵌进来，用户自己登，我们只取 Cookie。
 *
 * 三个必须注意的点（都是踩出来的）：
 * 1. 用移动端 UA。PC UA 会拿到桌面布局（实测 /login 没有 viewport meta），
 *    WebView 按 980px 排版再缩到屏宽，输入框小到手指点不到，内容还挤在上半屏。
 * 2. useWideViewPort / loadWithOverviewMode 必须关，否则 WebView 无视页面的 viewport meta。
 * 3. 根布局加 imePadding，否则键盘弹起来把输入框盖住。
 *
 * 为什么不用账号密码直连：密码要在客户端加密再提交，等于必须存密码；
 * 遇到验证码或双因子直接死。走网页登录，验证码、扫码、双因子都由用户自己过。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {

    val context = LocalContext.current
    val container = remember { (context.applicationContext as App).container }
    var hint by remember { mutableStateOf("用手机号或扫码登录，成功后自动进入") }
    var diag by remember { mutableStateOf("") }
    // 出问题时把提示变红：这条小字是用户唯一能自助判断"卡在哪一步"的线索
    var bad by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(retryTick) {
        CookieManager.getInstance().setAcceptCookie(true)
        var lastSig = ""
        var lastAt = 0L
        while (true) {
            val pairs = readChaoxingCookie()
            val header = pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // 手机版登录不一定下发 _uid，有的只给 UID；两个都认，再兜底 fid+uf。
            // 以前的实现只看 _uid，一旦账号拿到的不是这个名字就永远不触发，表现就是"卡在网页上"。
            val looksLoggedIn =
                pairs["_uid"].orEmpty().isNotBlank() ||
                    pairs["UID"].orEmpty().isNotBlank() ||
                    (pairs["fid"].orEmpty().isNotBlank() && pairs["uf"].orEmpty().isNotBlank())

            if (looksLoggedIn) {
                val sig = header.hashCode().toString()
                val now = System.currentTimeMillis()
                // 同一组 Cookie 每 5 秒最多验一次：不会空转，也不会因一次失败就永久卡死
                if (sig != lastSig || now - lastAt > 5_000) {
                    lastSig = sig
                    lastAt = now
                    hint = "已检测到登录凭据，正在验证"
                    bad = false

                    val n = withContext(Dispatchers.IO) { container.cookies.importHeader(header) }
                    if (n <= 0) {
                        hint = "登录凭据保存失败，点右上角重试"
                        diag = "Cookie ${pairs.size} 个，导入 0 个"
                        bad = true
                    } else {
                        when (val v = container.repo.session()) {
                            is Cx.Ok -> {
                                onLoggedIn()
                                return@LaunchedEffect
                            }
                            is Cx.NeedLogin -> {
                                hint = "验证未通过，稍后自动重试"
                                diag = "Cookie ${pairs.size} 个 / 导入 $n / 接口判定需登录"
                                bad = true
                            }
                            is Cx.Fail -> {
                                hint = "验证失败：${v.detail}"
                                diag = "Cookie ${pairs.size} 个 / 导入 $n"
                                bad = true
                            }
                        }
                    }
                }
            } else {
                diag = if (pairs.isEmpty()) "" else "已拿到 ${pairs.size} 个 Cookie，还没出现账号标识"
            }
            delay(800)
        }
    }

    // 登录页里点错链接时，返回键应该退回上一页而不是直接退出 App
    BackHandler(enabled = canGoBack) {
        webView?.takeIf { it.canGoBack() }?.goBack()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 标题只占一行，高度全部让给页面——之前标题太厚，页面被顶得靠上
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "登录学习通",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.ink,
                )
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = if (bad) Palette.alarm else Palette.weak,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (diag.isNotBlank()) {
                    Text(
                        text = diag,
                        fontSize = 10.sp,
                        color = Palette.weak,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { webView?.reload() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons24.Refresh,
                    contentDescription = "重新加载",
                    modifier = Modifier.size(22.dp),
                    tint = Palette.sub,
                )
            }
            // 手动兜底：自动检测万一没命中，用户点这个强制重新导入+验证
            Text(
                text = "重试",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { retryTick++ }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true

                    // 关键：让页面的 viewport meta 生效，按设备宽度排版
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false

                    // 关键：移动端 UA，学习通会跳到 /mlogin 的手机版表单
                    settings.userAgentString = Http.MOBILE_UA
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 学习通部分页面仍引用 http 的图片/脚本，强制拦截会把页面打散
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        /**
                         * 手机版登录成功后服务端把 refer 写死成 http://i.mooc.chaoxing.com，
                         * 明文跳转会被系统拦掉（net::ERR_CLEARTEXT_NOT_PERMITTED），
                         * WebView 就显示「网页无法打开」。这里直接把主框架请求升级成 https。
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return true
                            // 手机版登录成功后服务端把 refer 写死成 http://i.mooc.chaoxing.com，
                            // 明文跳转会被系统拦掉（net::ERR_CLEARTEXT_NOT_PERMITTED），
                            // WebView 就显示「网页无法打开」。这里直接把主框架请求升级成 https。
                            if (url.startsWith("http://", ignoreCase = true)) {
                                view?.loadUrl(url.replaceFirst("http://", "https://"))
                                return true
                            }
                            // 非 http(s) 一律拦下：tel: / intent:// / 各家 App 的自定义 scheme
                            // 如果放行，WebView 会交给系统，表现为"点一下就跳出 App"
                            val scheme = request?.url?.scheme?.lowercase().orEmpty()
                            return scheme != "https" && scheme != "http"
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            val desc = error?.description?.toString().orEmpty()
                            val bad = request.url.toString()
                            hint = "加载失败：$desc"
                            // 万一还是撞上明文限制，换 https 再试一次
                            if (desc.contains("CLEARTEXT", true) && bad.startsWith("http://")) {
                                view?.loadUrl(bad.replaceFirst("http://", "https://"))
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                            canGoBack = view?.canGoBack() == true
                            if (view == null) return
                            // 不在这里重置 hint：验证失败的红色提示会被下一次页面加载冲掉，
                            // 用户就看不到卡在哪一步了（这是踩过的坑）
                            // 学习通用 ratio.js 按 clientWidth/750 设 rem。若它在本 WebView 的
                            // viewport 生效前就跑了一次，比例会算大、页面横向溢出。
                            // 补发 resize 让它按真实宽度重算一次。
                            view.resyncLayout()
                            view.postDelayed({ view.resyncLayout() }, 400)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canGoBack = view?.canGoBack() == true
                        }
                    }
                    loadUrl(PASSPORT)
                    webView = this
                }
            },
            update = { webView = it },
        )

        Text(
            text = "只保存登录凭据，不保存密码；凭据用系统密钥加密存在本机",
            fontSize = 10.sp,
            color = Palette.weak,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

