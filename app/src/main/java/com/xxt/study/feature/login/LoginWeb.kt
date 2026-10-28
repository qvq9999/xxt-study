package com.xxt.study.feature.login

import android.webkit.CookieManager
import android.webkit.WebView

/** 学习通登录页入口 */
internal const val PASSPORT = "https://passport2.chaoxing.com/login"

/**
 * 把几个域的 Cookie 都抓一遍再合并成 map。
 * 只读一个域容易漏（登录会在 passport2 / chaoxing / i.chaoxing 之间来回跳）。
 */
internal fun readChaoxingCookie(): Map<String, String> {
    val cm = CookieManager.getInstance()
    val hosts = listOf(
        "https://chaoxing.com",
        "https://i.chaoxing.com",
        "https://mooc1.chaoxing.com",
        "https://passport2.chaoxing.com",
    )
    val pairs = LinkedHashMap<String, String>()
    for (h in hosts) {
        val raw = runCatching { cm.getCookie(h) }.getOrNull() ?: continue
        for (part in raw.split(';')) {
            val item = part.trim()
            val i = item.indexOf('=')
            if (i <= 0) continue
            val k = item.substring(0, i)
            if (k.isNotEmpty()) pairs[k] = item.substring(i + 1)
        }
    }
    return pairs
}

/**
 * 学习通的页面宽度靠 `ratio.js` 按 `clientWidth / 750` 算根字号，
 * 且只在 `resize` 时重算。若它在本 WebView 的 viewport 生效前先跑了一次，
 * 比例会算大、页面横向溢出（表现为内容被裁到右边界外）。
 * 加载完成后按真实宽度重设一次并触发 resize。
 */
internal fun WebView.resyncLayout() {
    runCatching {
        evaluateJavascript(
            "(function(){var w=window.innerWidth||document.documentElement.clientWidth;" +
                "document.documentElement.style.fontSize=(100*w/750)+'px';" +
                "window.dispatchEvent(new Event('resize'));})()",
            null,
        )
    }
}
