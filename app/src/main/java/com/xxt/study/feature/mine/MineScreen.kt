package com.xxt.study.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.Session

/**
 * 「我的」页。
 *
 * 这里只放两类东西：账号是谁，以及去哪改配置。
 * 不在这里放业务开关——刷课/答题的参数都归设置页，
 * 免得同一个开关在两个地方有入口、状态还对不上。
 */
@Composable
fun MineScreen(
    session: Session?,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
    onNeedLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val version = rememberVersion(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "我的",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        )

        // ---- 账号卡 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.card)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Palette.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (session?.account?.firstOrNull() ?: '·').toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.brand,
                )
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    // 还在读就明说在读，不要先写"已登录"再跳成真名——看起来像状态在乱变
                    text = session?.account?.ifBlank { "已登录" } ?: "正在读取账号",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Text(
                    text = session?.school?.ifBlank { "学习通账号" } ?: "请稍候",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 设置 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.card),
        ) {
            MineRow(
                icon = Icons24.Tune,
                title = "设置",
                subtitle = "AI 服务商与密钥、自动答题",
                onClick = onOpenSettings,
            )
            MineRow(
                icon = Icons24.Inbox,
                title = "使用说明",
                subtitle = "功能教程、AI Key 获取地址（可一键复制）",
                onClick = onOpenGuide,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 账号操作 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.card),
        ) {
            MineRow(
                icon = Icons24.Refresh,
                title = "重新登录",
                subtitle = "登录态失效时用这个",
                onClick = onNeedLogin,
            )
            MineRow(
                icon = Icons24.Alert,
                title = "退出登录",
                subtitle = "清除本机 Cookie 与缓存",
                danger = true,
                onClick = onLogout,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "学习通助手 v$version\n只处理课程任务点与作业答案回填，不碰考试。",
            fontSize = 11.sp,
            color = Palette.weak,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun MineRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (danger) Palette.alarm else Palette.sub,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (danger) Palette.alarm else Palette.ink,
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Icon(
            imageVector = Icons24.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Palette.line,
        )
    }
}

/** 读一次版本号；拿不到就显示 0.0.0，不影响使用 */
@Composable
private fun rememberVersion(context: android.content.Context): String =
    remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
