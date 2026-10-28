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
            versionName = "1.0.0",
            date = "10-28",
            notes = listOf(
                "课程页改任务/章节/作业",
                "随堂练习可 AI 作答",
                "已作答自动回填",
                "界面改柔光风格",
                "使用说明重写",
            ),
        ),
        ChangeEntry(
            versionName = "0.9.2",
            date = "10-28",
            notes = listOf(
                "课程可长按置顶",
                "每门课独立刷新",
                "里外作业数自动同步",
            ),
        ),
        ChangeEntry(
            versionName = "0.9.1",
            date = "10-28",
            notes = listOf(
                "答题弹窗可对话优化",
                "优化可撤销回上一版",
                "使用说明同步新版",
            ),
        ),
        ChangeEntry(
            versionName = "0.9.0",
            date = "10-28",
            notes = listOf(
                "抽屉窗口统一改弹窗",
                "答题可编辑提示词再生成",
                "作答过程分阶段显示",
                "课程页顶部更紧凑",
            ),
        ),
        ChangeEntry(
            versionName = "0.8.2",
            date = "10-28",
            notes = listOf(
                "修简答题填成提示词",
                "提交结果被裁可一键展开",
            ),
        ),
        ChangeEntry(
            versionName = "0.8.1",
            date = "10-28",
            notes = listOf(
                "版本日志改成弹窗查看",
                "「我的」页底部只留版本号",
                "修提交后看不到结果",
            ),
        ),
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

    /**
     * 一条记录最多留几条要点。
     *
     * 弹窗虽然比原来那坨内嵌列表宽裕，但"一次列八条"还是会让人不想看。
     * 发版脚本已经卡了字数，这里再卡条数，两头都短，日志才有被读完的可能。
     */
    const val MAX_NOTES = 6
}
