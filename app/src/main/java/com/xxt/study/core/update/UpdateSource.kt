package com.xxt.study.core.update

/**
 * 更新源。
 *
 * 用户要求「默认更新源改成国内镜像」——所以顺序是：先镜像、最后才直连 GitHub。
 * 每个镜像都是"前缀 + 原始 GitHub 地址"的拼接方式，这也是这类加速站通用的用法。
 *
 * 发版时只需要改 [REPO] 一处；清单和 APK 的地址都由它推出来，
 * 不会出现"清单改了 APK 忘了改"的情况。
 */
object UpdateSource {

    /** GitHub 仓库（owner/repo）。第一次发版前把这个改成真实仓库即可 */
    const val REPO = "qvq9999/xxt-study"

    /** 清单在仓库里的路径（main 分支） */
    private const val MANIFEST_PATH = "main/update/latest.json"

    /** GitHub 原始地址。国内直连 raw 经常超时，所以只作最後兜底 */
    const val ORIGINAL_MANIFEST = "https://raw.githubusercontent.com/$REPO/$MANIFEST_PATH"

    /**
     * 国内镜像前缀，按**实测可用性**排（2026-10-28 逐个 curl 过）：
     *
     * ```
     * https://ghproxy.net/   → 200
     * https://gh-proxy.com/  → 200
     * https://ghfast.top/    → 000（连不上）
     * ```
     *
     * 所以 ghfast.top 从第一位挪到末尾——它排第一的话，每次检查都要先等 8 秒超时，
     * 用户会感觉"检查更新很慢"。留着它是因为这类站点经常复活，不必删。
     *
     * 都是纯 HTTP 反代，不需要 key、不改变文件内容；一个挂了就换下一个。
     */
    val MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
    )

    data class Route(val name: String, val url: String)

    /**
     * 清单的候选地址。
     *
     * 第一个是用户要的"默认国内镜像"，最后一个是直连兜底。
     * [MIRRORS] 为空时退化成只用直连，不会崩。
     */
    fun manifestRoutes(): List<Route> {
        val mirrorRoutes = MIRRORS.map { prefix ->
            Route(name = hostOf(prefix), url = prefix + ORIGINAL_MANIFEST)
        }
        return mirrorRoutes + Route(name = "GitHub", url = ORIGINAL_MANIFEST)
    }

    /**
     * 安装包的候选地址。
     *
     * @param assetUrl 清单里的 url（GitHub Release 直链）
     */
    fun downloadRoutes(assetUrl: String): List<Route> {
        val clean = assetUrl.trim()
        if (clean.isEmpty()) return emptyList()
        val mirrorRoutes = MIRRORS.map { prefix ->
            Route(name = hostOf(prefix), url = prefix + clean)
        }
        return mirrorRoutes + Route(name = "GitHub", url = clean)
    }

    /** "https://ghfast.top/" → "ghfast.top"，只用来在界面上说明走了哪个源 */
    fun hostOf(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifBlank { url }

    /**
     * 把版本号说成 `1.2.3`。
     *
     * versionName 由 build.gradle 手写，正常就是 "0.6.0"；
     * 这里只是去掉可能存在的 `-debug` 之类的后缀。
     */
    fun cleanVersionName(raw: String): String = raw.substringBefore('-').trim()
}
