package com.xxt.study.core.net

import com.xxt.study.core.crypto.SecretStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 持久化 Cookie 罐。
 *
 * 与 WebView 的关系：登录在 WebView 里完成，登录成功后把 CookieManager 里的
 * 整串 Cookie 用 [importHeader] 灌进来；之后 Http 请求带上它。
 * 反过来 WebView 打开作答页时，再由 WebView 自己带自己的 Cookie——两边同源即可。
 *
 * 落盘前走 [SecretStore] 加密，不写明文。
 *
 * @param namespace 多账号隔离用。空串 = 主账号（沿用历史键 `cx_cookies`，老用户升级不掉登录态）；
 *                  需要并行第二个号时传 `acct_<key>`，落盘键变成 `cx_cookies_acct_<key>`，两份互不可见。
 *                  目前只有主账号在用，能力留着是为了将来加号时不用改存储格式。
 */
class CookieStore(private val secrets: SecretStore, namespace: String = "") : CookieJar {

    private val prefKey = if (namespace.isBlank()) KEY else "${KEY}_$namespace"

    private val jar = LinkedHashMap<String, Cookie>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = secrets.get(prefKey) ?: return
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            decode(line)?.let { jar[keyOf(it)] = it }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        if (cookies.isEmpty()) return
        for (c in cookies) {
            // 会话 Cookie 也保留：学习通的登录态本来就是会话级的
            jar[keyOf(c)] = c
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        return jar.values.filter { it.expiresAt > now && it.matches(url) }
    }

    /** 把 WebView 里 `CookieManager.getCookie()` 拿到的整串 "k=v; k2=v2" 灌进来 */
    fun importHeader(raw: String, url: String = DEFAULT_URL): Int {
        ensureLoaded()
        if (raw.isBlank()) return 0
        // 学习通的接口全在 *.chaoxing.com 下，统一挂到父域，一次登录覆盖所有子域。
        // 注意 domain() 不能带前导点：OkHttp 的 Cookie.Builder 会直接抛
        // IllegalArgumentException（实测，见 CookieDomainTest），
        // 以前写 ".$host" 导致每条 Cookie 都构建失败、导入 0 条 → 登录后永远进不去。
        val parent = parentDomainOf(url)
        var n = 0
        for (part in raw.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val i = item.indexOf('=')
            if (i <= 0) continue
            val name = item.substring(0, i).trim()
            val value = item.substring(i + 1).trim()
            if (name.isEmpty()) continue
            val cookie = runCatching {
                Cookie.Builder()
                    .domain(parent)
                    .path("/")
                    .name(sanitizeName(name))
                    .value(sanitizeValue(value))
                    .build()
            }.getOrNull() ?: continue
            jar[keyOf(cookie)] = cookie
            n++
        }
        if (n > 0) persist()
        return n
    }

    /**
     * 从 URL 取可注册父域：`https://i.chaoxing.com/base` → `chaoxing.com`。
     *
     * 只保留最后两段（chaoxing.com），这样 `mooc1`、`passport2`、`mooc2-ans`
     * 全都命中。多段后缀（如 .com.cn）这里不处理——学习通不用。
     */
    private fun parentDomainOf(url: String): String {
        val host = runCatching { url.toHttpUrl().host }.getOrDefault("chaoxing.com")
        val parts = host.split('.')
        return if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
    }

    /**
     * 学习通的 Cookie 值里有很长的签名串。这里只挡会破坏 Cookie 头的字符：
     * 分号（会截断）、控制字符和非 ASCII。
     *
     * 特别注意别过滤逗号——它是合法值字符，删掉等于改了签名，
     * 服务端会认不出这个会话（表现是最难查的那种"导进去了但登录无效"）。
     */
    private fun sanitizeValue(v: String): String =
        v.filter { (it.code in 0x20..0x7E) && it != ';' }

    private fun sanitizeName(v: String): String =
        v.filter { it.code in 0x21..0x7E }.filter { it !in "()<>@,;:\\\"/[]?={} \t" }

    /**
     * 当前是否具备登录态。
     *
     * 与登录页的判定保持一致，认三种凭据形态：
     * `_uid`、`UID`、或 `fid`+`uf` 组合——手机版登录流程下发哪个不固定，
     * 只看 `_uid` 会让一部分账号冷启动时被踢回登录页。
     *
     * 这是乐观判断，只用来决定首屏进退；真伪由 [ChaoxingClient.checkSession] 定，
     * 那份接口一旦返回 NeedLogin，UI 会把用户送回登录页。
     */
    fun loggedIn(): Boolean {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val live = jar.values.filter { it.expiresAt > now && it.value.isNotBlank() }
        if (live.isEmpty()) return false
        fun has(name: String) = live.any { it.name == name }
        return has("_uid") || has("UID") || (has("fid") && has("uf"))
    }

    fun exportHeader(url: String = DEFAULT_URL): String {
        ensureLoaded()
        val u = runCatching { url.toHttpUrl() }.getOrNull() ?: return ""
        return loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * 按名字取一个 Cookie 值。
     *
     * 刷课要用到的两个关键值都在 Cookie 里：
     * - `_uid`：视频进度上报的 userid 参数，也参与 enc 签名，少一位签名就废
     * - `fid`：ananas/status 查播放信息的 k 参数（机构 id）
     * 这两个都拿不到时协议跑不起来，所以宁可返回 null 让上层明确报错，不要猜。
     */
    fun value(name: String): String? {
        ensureLoaded()
        val now = System.currentTimeMillis()
        return jar.values.firstOrNull { it.name == name && it.expiresAt > now && it.value.isNotBlank() }?.value
    }

    fun clear() {
        ensureLoaded()
        jar.clear()
        secrets.remove(prefKey)
    }

    /** 只清学习通域的缓存参数，不清登录态 */
    fun cookieCount(): Int {
        ensureLoaded()
        return jar.size
    }

    @Synchronized
    private fun persist() {
        val text = jar.values.joinToString("\n") { encode(it) }
        secrets.put(prefKey, text)
    }

    private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

    private fun encode(c: Cookie): String = listOf(
        c.domain, c.path, c.name, c.value, c.expiresAt.toString(),
        if (c.hostOnly) "1" else "0",
        if (c.secure) "1" else "0",
        if (c.httpOnly) "1" else "0",
    ).joinToString("\t")

    private fun decode(line: String): Cookie? = runCatching {
        val p = line.split('\t')
        if (p.size < 5) return null
        val b = Cookie.Builder()
        // 旧版本可能把 ".domain" 带点存进来，这里的 domain() 会抛异常把整条丢掉
        if (p[5] == "1") b.hostOnlyDomain(p[0].trimStart('.')) else b.domain(p[0].trimStart('.'))
        b.path(p[1]).name(p[2]).value(p[3])
        p[4].toLongOrNull()?.let { if (it > 0) b.expiresAt(it) }
        if (p.getOrNull(6) == "1") b.secure()
        if (p.getOrNull(7) == "1") b.httpOnly()
        b.build()
    }.getOrNull()

    private companion object {
        const val KEY = "cx_cookies"
        const val DEFAULT_URL = "https://i.chaoxing.com/base"
    }
}
