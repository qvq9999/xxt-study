package com.xxt.study.core.result

/**
 * 学习通请求的统一结果。
 * UI 层只看得到这个，见不到 HTTP 状态码、IOException 这类概念。
 */
sealed interface Cx<out T> {

    data class Ok<T>(val data: T) : Cx<T>

    /** Cookie 失效，需要重新登录 */
    data object NeedLogin : Cx<Nothing>

    data class Fail(val kind: Kind, val detail: String) : Cx<Nothing>

    enum class Kind {
        /** 网络不通 / 超时 */
        NETWORK,

        /** 服务端拒绝：无权限、参数验证不通过 */
        DENIED,

        /** 页面解析不出来，多半是改版 */
        PARSE,

        /** 被限流 */
        RATE_LIMITED,

        /**
         * 触发风控，需要人工完成验证（验证码/滑块）。
         *
         * 和"被限流"分开：限流等一会儿会自己好，这个必须人点一下，
         * 而且要把原版页面拿出来让用户操作，所以界面处理完全不同。
         */
        VERIFY,

        UNKNOWN,
    }
}

inline fun <T> Cx<T>.onOk(block: (T) -> Unit): Cx<T> {
    if (this is Cx.Ok) block(data)
    return this
}

fun <T> Cx<T>.dataOrNull(): T? = (this as? Cx.Ok)?.data
