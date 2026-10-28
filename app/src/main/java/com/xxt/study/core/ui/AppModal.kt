package com.xxt.study.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全 App 统一的弹窗容器（替代原来的 `ModalBottomSheet` 抽屉）。
 *
 * ## 为什么不用抽屉了
 *
 * `ModalBottomSheet` 的下滑关闭手势会**和里面的滚动抢事件**：手指往下滑想翻内容，
 * 内容没动、窗口先跟着跳到一半，再滑一次直接关掉——刷课页里就踩过这个坑，
 * 当时临时换成了整页。抽屉还有个更根本的问题：它是"从底部推上来"的，
 * 屏幕矮的机型上展开高度不稳定，一半内容被压在屏幕外。
 *
 * 所以统一成居中弹窗，并且把"弹出 / 关闭 / 遮罩 / 滚动 / 移动端适配"这五件事
 * 一次性写在**同一个地方**——各页面只描述内容，不再各自决定这些行为，
 * 也就不会出现"这个弹窗能点遮罩关、那个不能"的不一致。
 *
 * ## 几条固定的行为
 *
 * - **遮罩**：由 Dialog 自带的 scrim 提供，点击遮罩关闭（[onDismiss]）
 * - **返回键**：关闭弹窗，不退出页面
 * - **宽度**：占满可用宽度减两侧 18dp（`usePlatformDefaultWidth = false`，
 *   否则默认宽度只有屏幕的 ~56%，长文本会挤成一条）
 * - **高度**：最多 [maxHeightFraction] 屏，超出时**内容区自己滚**，标题与底部按钮固定
 * - **键盘**：容器带 `imePadding()`，编辑长文本时弹窗整体上移，输入框不会被键盘盖住
 *
 * @param footer 固定在底部、不参与滚动的按钮区（可为空）
 * @param content 会滚动的正文
 */
@Composable
fun AppModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxHeightFraction: Float = 0.82f,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeightDp * maxHeightFraction)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.card),
            ) {
                // ---- 标题栏（固定，不跟着滚）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.ink,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                fontSize = 11.sp,
                                color = Palette.weak,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons24.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = Palette.weak,
                        )
                    }
                }

                /*
                 * 内容区：weight(1f, fill = false) + verticalScroll。
                 *
                 * fill = false 让"内容本来就短"的弹窗保持紧凑（不会撑成整屏），
                 * 内容长的时候则由 weight 拿到剩余高度并内部滚动。
                 * 两者只能这样配：给死高度会让短弹窗也变一块大方块，
                 * 不给 weight 则超高时整体溢出屏幕、底部按钮被顶出去。
                 */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    content = content,
                )

                if (footer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                    ) {
                        footer()
                    }
                } else {
                    Box(Modifier.padding(bottom = 14.dp))
                }
            }
        }
    }
}

/** 弹窗里的小按钮：主按钮用实心，次按钮用浅色底 */
@Composable
fun ModalButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Palette.bg
                    primary -> Palette.brand
                    else -> Palette.brandSoft
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = when {
                !enabled -> Palette.weak
                primary -> Palette.card
                else -> Palette.brand
            },
        )
    }
}
