package com.xxt.study.feature.update

import com.xxt.study.core.update.UpdatePhase
import com.xxt.study.core.update.UpdateUiState

/**
 * "我的"页那一行更新入口要显示的文案。
 *
 * 抽成纯函数是为了能被单测覆盖：这一行是用户判断"更新到底走到哪一步了"的唯一常驻入口，
 * 文案写错（比如下载中显示"检查更新"，或者失败什么都不说）就等于没有反馈。
 */
data class UpdateRowText(
    val title: String,
    val subtitle: String,
    /** 点了应该"查看进行中的任务"，还是"发起一次新的检查" */
    val viewable: Boolean = false,
)

fun updateRowText(state: UpdateUiState, version: String): UpdateRowText {
    val v = state.available?.manifest?.versionName.orEmpty()
    return when (state.phase) {
        UpdatePhase.IDLE -> UpdateRowText(
            title = "检查更新",
            subtitle = "当前 v$version · 点击检查",
        )

        UpdatePhase.CHECKING -> UpdateRowText(
            title = "正在检查更新…",
            subtitle = "正在连接更新服务器",
        )

        UpdatePhase.UP_TO_DATE -> UpdateRowText(
            title = "已是最新版本",
            subtitle = "当前 v$version · 刚刚检查过",
        )

        UpdatePhase.FAILED -> UpdateRowText(
            title = "检查更新失败",
            subtitle = "${state.message.orEmpty().ifBlank { "网络或更新源不可用" }} · 点击重试",
        )

        UpdatePhase.PROMPT -> UpdateRowText(
            title = "发现新版本 v$v",
            subtitle = "点击查看更新内容",
            viewable = true,
        )

        UpdatePhase.DOWNLOADING -> UpdateRowText(
            title = "正在下载 v$v",
            subtitle = if (state.progress in 1..99) {
                "已完成 ${state.progress}%，可后台继续"
            } else {
                "正在准备下载，可后台继续"
            },
            viewable = true,
        )

        UpdatePhase.READY -> UpdateRowText(
            title = "更新已下载完成",
            subtitle = "v$v 待安装 · 点击立即安装",
            viewable = true,
        )

        UpdatePhase.NEED_PERMISSION -> UpdateRowText(
            title = "需要允许安装应用",
            subtitle = "点击查看开启路径",
            viewable = true,
        )

        UpdatePhase.ERROR -> UpdateRowText(
            title = "更新失败",
            subtitle = "${state.message.orEmpty().ifBlank { "下载或安装出错" }} · 点击重试",
            viewable = true,
        )
    }
}
