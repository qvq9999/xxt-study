package com.xxt.study.core.update

/**
 * 一条版本记录。
 *
 * 日志刻意写得极短——它出现在「我的」页那一小块地方，
 * 不是 CHANGELOG 文档。完整说明在 README 里。
 */
data class ChangeEntry(
    val versionName: String,
    val date: String,
    val notes: List<String>,
)

/**
 * 内置的近期版本日志。
 *
 * 为什么放在代码里而不是从服务器拉：
 * 1. "看版本日志"是个**离线也该能用**的功能，不该为了它再发一次网络请求；
 * 2. 检查更新本身已经够慢了，能省的请求就省；
 * 3. 版本少、变化慢，随包发布完全够用。
 *
 * 发版时记得在这里补一条——`tools/release-update.mjs` 的 notes 直接抄过来即可。
 */
object Changelog {

    val entries: List<ChangeEntry> = listOf(
        ChangeEntry(
            versionName = "0.8.0",
            date = "10-28",
            notes = listOf(
                "深色模式（背景纯黑，OLED 省电）",
                "状态栏图标随主题反色",
                "作业列表支持单课刷新",
                "优先扫本学期课程",
                "更新检查改为并发，不再转半天",
                "「我的」页重排，内置版本日志",
            ),
        ),
        ChangeEntry(
            versionName = "0.7.1",
            date = "10-28",
            notes = listOf(
                "单次只扫 12 门，避免触发风控",
                "撤回升级清缓存",
                "修「验证完仍无法使用」",
            ),
        ),
        ChangeEntry(
            versionName = "0.7.0",
            date = "10-28",
            notes = listOf(
                "启动权限引导（通知 / 安装应用）",
                "更新可后台下载，关弹窗不中断",
                "下载完成发通知",
            ),
        ),
        ChangeEntry(
            versionName = "0.6.1",
            date = "10-28",
            notes = listOf(
                "修验证码页无限循环",
                "点继续前检查验证码是否已提交",
            ),
        ),
        ChangeEntry(
            versionName = "0.6.0",
            date = "10-28",
            notes = listOf(
                "作业状态按时间与结课重算",
                "内置更新（国内镜像优先）",
            ),
        ),
    )

    /** 默认展示几条：两三版足够说明"最近改了什么"，再多就变成长文档了 */
    const val COLLAPSED_COUNT = 2
}
