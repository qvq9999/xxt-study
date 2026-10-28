package com.xxt.study.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.CourseDigest

/** 首页的大数字：全屏唯一视觉焦点，也只用这一个高饱和色 */
@Composable
internal fun TodoBlock(todo: Int, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = todo.toString(),
            fontSize = 64.sp,
            fontWeight = FontWeight.Medium,
            color = if (todo > 0) Palette.alarm else Palette.ok,
        )
        Column(modifier = Modifier.padding(start = 10.dp, bottom = 14.dp)) {
            Text(
                text = if (todo > 0) "份未完成作业" else "全部交完",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(text = subtitle, fontSize = 11.sp, color = Palette.weak)
        }
    }
}

@Composable
internal fun ErrorBanner(
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.alarmSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons24.Alert,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Palette.alarm,
        )
        Text(
            text = message,
            fontSize = 12.sp,
            color = Palette.alarm,
            lineHeight = 17.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.alarm,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.card)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 登录态失效的入口条。
 *
 * 刻意做成"要用户点一下"而不是自动跳登录页：登录页自己也会检测会话，
 * 两边都自动跳会互相踢，形成来回跳的白屏。
 */
@Composable
internal fun ReloginBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.alarmSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons24.Alert,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Palette.alarm,
        )
        Text(
            text = "登录态已失效",
            fontSize = 12.sp,
            color = Palette.alarm,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        Text(
            text = "去登录",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.alarm,
        )
    }
}

@Composable
internal fun CourseRow(
    digest: CourseDigest,
    /** 现在还能提交的份数——红点只认这个，已截止的不算 */
    actionable: Int,
    /** 未交但已过截止的份数，用来区分「已清」和「已截止」 */
    expired: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = digest.course.name.ifBlank { "未命名课程" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(
                text = if (digest.ok) {
                    val base = if (digest.works.isEmpty()) "暂无作业" else "共 ${digest.works.size} 份作业"
                    // 结课是用户判断"这门课的红点为什么没了"的依据，必须写出来
                    if (digest.course.closed) "$base · 已结课" else base
                } else {
                    digest.error ?: "暂时取不到"
                },
                fontSize = 11.sp,
                color = if (digest.ok) Palette.weak else Palette.alarm,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        if (digest.ok) {
            CountChip(actionable, expired, digest.course.closed)
            Icon(
                imageVector = Icons24.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Palette.weak,
            )
        }
    }
}

/**
 * 课程行右侧的计数。
 *
 * 四种状态刻意分开，因为它们的含义完全不同：
 * - 有可交作业 → 红，提醒"这里还有活"
 * - 只剩已截止的 → 灰「已截止」，不制造红点（点了也交不上）
 * - 课程已结课 → 灰「已结课」，和"已截止"区分开：
 *   前者是整门课结束，后者是单份作业过期
 * - 一份都没有 → 绿「已清」
 */
@Composable
private fun CountChip(actionable: Int, expired: Int, closed: Boolean) {
    val bg = when {
        actionable > 0 -> Palette.alarmSoft
        expired > 0 || closed -> Palette.bg
        else -> Palette.okSoft
    }
    val fg = when {
        actionable > 0 -> Palette.alarm
        expired > 0 || closed -> Palette.weak
        else -> Palette.ok
    }
    val text = when {
        actionable > 0 -> "$actionable 份"
        expired > 0 -> "已截止"
        closed -> "已结课"
        else -> "已清"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
internal fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons24.Inbox,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Palette.line,
            )
            Text(text = text, fontSize = 13.sp, color = Palette.weak, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
