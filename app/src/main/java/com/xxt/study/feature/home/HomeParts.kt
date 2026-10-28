package com.xxt.study.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.CourseDigest

/**
 * 首页的待办概览。
 *
 * 0.9.0 从"64sp 大数字 + 两行说明"压成一行紧凑版：
 * 那个大数字竖向吃掉近 90dp，加搜索框和分组标题，列表还没开始就没了三成屏幕。
 * 现在数字降到 26sp、说明并成一行、上下留白各 6dp，总高约 40dp——
 * 数字仍然是全屏最大的字，但不再拿"展示面积"换"视觉冲击"。
 *
 * 信息量没减：份数、覆盖课数、刷新时间、失败课数全在同一行里。
 */
@Composable
internal fun TodoBlock(todo: Int, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = todo.toString(),
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            color = if (todo > 0) Palette.alarm else Palette.ok,
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = if (todo > 0) "份未完成作业" else "全部交完",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = Palette.weak,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CourseRow(
    digest: CourseDigest,
    /** 现在还能提交的份数——红点只认这个，已截止的不算 */
    actionable: Int,
    /** 未交但已过截止的份数，用来区分「已清」和「已截止」 */
    expired: Int,
    /** 是否已置顶（长按切换） */
    pinned: Boolean,
    /** 这一门正在单独刷新 */
    refreshing: Boolean,
    /** 刷新结果文案，3 秒后由 VM 自动清掉 */
    note: CourseNote?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.card)
            // 长按置顶：不用多一个按钮占地方，也不容易误触（列表行本来就只点不拖）
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    // 置顶标识：一个"钉"字小标签，比图标更好认，也不占宽度
                    Text(
                        text = "置顶",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.brand,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.brandSoft)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = digest.course.name.ifBlank { "未命名课程" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            // 刷新反馈就地显示在课程名下：不用 Toast 也不打断操作
            if (note != null) {
                Text(
                    text = note.text,
                    fontSize = 10.sp,
                    color = if (note.ok) Palette.ok else Palette.alarm,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (digest.ok) {
            CountChip(actionable, expired, digest.course.closed)
        }

        // 独立刷新键：只刷这一门（2 个请求），转圈时不可点
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(enabled = !refreshing, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = Palette.brand,
                )
            } else {
                Icon(
                    imageVector = Icons24.Refresh,
                    contentDescription = "刷新这门课的作业",
                    modifier = Modifier.size(17.dp),
                    tint = Palette.weak,
                )
            }
        }

        Icon(
            imageVector = Icons24.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Palette.line,
        )
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
