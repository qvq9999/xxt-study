package com.xxt.study.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HttpText(val code: Int, val finalUrl: String, val body: String)

/**
 * 学习通请求通道。
 *
 * 三件事保证不惹风控：
 * 1. 全局串行（一次只发一个请求）
 * 2. 相邻请求至少间隔 [MIN_GAP_MS]
 * 3. 统一 PC 版 UA——PC 页面才带 enc 参数，移动版结构完全不同
 */
class Http(val cookies: CookieStore) {

    private val gate = Mutex()
    private var lastAt = 0L

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    suspend fun get(url: String, referer: String? = null): HttpText = withContext(Dispatchers.IO) {
        gate.withLock {
            val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastAt)
            if (wait > 0) Thread.sleep(wait)
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    HttpText(
                        code = resp.code,
                        finalUrl = resp.request.url.toString(),
                        body = resp.body?.string().orEmpty(),
                    )
                }
            } finally {
                lastAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * 表单提交。
     *
     * 章节测验的提交接口（addStudentWorkNewWeb）只吃 x-www-form-urlencoded，
     * 并且要带 X-Requested-With / Origin —— 少这两个会被当成跨站请求回 403。
     */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
    ): HttpText = withContext(Dispatchers.IO) {
        gate.withLock {
            val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastAt)
            if (wait > 0) Thread.sleep(wait)
            try {
                val body = FormBody.Builder(Charsets.UTF_8)
                fields.forEach { (k, v) -> body.add(k, v) }
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", "https://mooc1.chaoxing.com")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                    .post(body.build())
                    .build()
                client.newCall(req).execute().use { resp ->
                    HttpText(
                        code = resp.code,
                        finalUrl = resp.request.url.toString(),
                        body = resp.body?.string().orEmpty(),
                    )
                }
            } finally {
                lastAt = System.currentTimeMillis()
            }
        }
    }

    /** 走 AI 接口用；不设 300ms 间隔之外的额外限制，但仍在同一串口上，避免和刷课抢连接 */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpText = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                val body = json.toRequestBody(JSON_MEDIA_TYPE)
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .post(body)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    HttpText(
                        code = resp.code,
                        finalUrl = resp.request.url.toString(),
                        body = resp.body?.string().orEmpty(),
                    )
                }
            } finally {
                lastAt = System.currentTimeMillis()
            }
        }
    }

    companion object {
        /** PC 版 Chrome UA：数据接口必须用它，移动版页面拿不到 enc/stuenc */
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 移动版 UA：只给登录 WebView 用。
         * 用 PC UA 打开登录页会拿到桌面布局（实测：/login 无 viewport meta，35.7 KB），
         * WebView 按 980px 排版再缩到屏宽，输入框小到手指点不到。
         * 换成移动 UA 后学习通自动跳 /mlogin，带 width=device-width，控件正常大小。
         *
         * 注意：后面打开作答页做回填时要用回 [UA]，那是 PC 版页面。
         */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val MIN_GAP_MS = 300L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
