package com.xxt.study.feature.perm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xxt.study.core.ui.Palette

/**
 * 启动时的权限引导。
 *
 * ## 为什么这两项要单独引导
 *
 * - **通知**（Android 13+）是运行时权限，可以直接弹系统对话框；
 * - **安装未知应用**（Android 8+）**不是**运行时权限，`requestPermissions` 对它无效，
 *   只能跳到 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 让用户自己打开。
 *   部分国产 ROM 会在这个页面里再弹一次确认框，部分不会——所以我们既给按钮，
 *   也把"去哪个页面开"写清楚，用户找不到时不至于卡死。
 *
 * 这个弹窗**不阻塞**使用：可以"稍后再说"，之后从「我的 → 权限」随时回来。
 */
@Composable
fun PermissionSetupDialog(
    notifGranted: Boolean,
    installGranted: Boolean,
    onRequestNotif: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.card)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "还差两项权限",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(
                text = "不是必须，但给了体验才完整。之后可以在「我的 → 权限」再改。",
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(14.dp))

            PermissionItem(
                title = "通知",
                desc = "刷课进度、更新下载完成的提醒",
                granted = notifGranted,
                actionText = "允许",
                onAction = onRequestNotif,
            )

            Spacer(Modifier.height(10.dp))

            PermissionItem(
                title = "安装应用",
                desc = "内置更新下载完后交给系统安装，Android 要求手动打开这个开关",
                granted = installGranted,
                actionText = "去开启",
                onAction = onOpenInstallSettings,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.bg)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("稍后再说", fontSize = 13.sp, color = Palette.sub)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.brand)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (notifGranted && installGranted) "完成" else "知道了",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.card,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    desc: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        if (granted) {
            Text("已允许", fontSize = 12.sp, color = Palette.ok)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Palette.brandSoft)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = actionText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.brand,
                )
            }
        }
    }
}
