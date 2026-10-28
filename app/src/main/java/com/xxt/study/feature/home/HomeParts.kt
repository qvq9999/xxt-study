package com.xxt.study.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.CourseDigest

/** 卡片统一圆角：与弹窗、设置页保持一致，改这里等于改全项目 */
private val CardShape = RoundedCornerShape(18.dp)

/**
 * 首页待办主卡。
 *
 * **高度固定 80dp，不随文字多少变化**：原先「覆盖 N 门课 · X 前刷新」和
 * 「还有 N 门课数据较旧…」这两行挂在卡片外面，出现/消失时会把下面的列表整体顶动；
 * 现在三行全部收进卡内并垂直居中，缺的内容用空串占位（而不是 if 掉），
 * 行数恒定 → 高度恒定 → 列表不会因为一句提示就跳一下。
 *
 * @param subtitle 覆盖课数与刷新时间，常驻第二行
 * @param note     数据较旧 / 备用源之类的补充提示，没有就传 null（占位不显示）
 * @param busy     刷新中的进度文案，非空时顶替第二行，不新增行
 */
@Composable
internal fun TodoBlock(todo: Int, subtitle: String, note: String? = null, busy: String? = null) {
    /*
     * 高度为什么交给内容决定，而不是写死 dp：
     *
     * 实机（系统字号被调大）上原实现写死 80dp，三行文字放不下，
     * 第三行被卡片底部裁掉，上下留白也从 14dp / 7dp 变成肉眼可见的不对称。
     *
     * 但"不写死"不等于"会随数据跳"：这里第三行**永远渲染**（无提示时放一个空格），
     * 三行行数恒定、每行 maxLines = 1，所以待办从 29 变 30、
     * 提示从有变没有、全部交完切勾，卡片高度都不会动一分。
     * 唯一会让它变高的是用户自己调大系统字号——那是应该变大，不是缺陷。
     */
    // 数字是图形元素，不跟着系统字号膨胀，否则字号一大它就吃掉整张卡
    val numberSize = with(LocalDensity.current) { 32.dp.toSp() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(Palette.heroFrom, Palette.heroTo)))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = todo.toString(),
            fontSize = numberSize,
            fontWeight = FontWeight.Bold,
            color = Palette.onHero,
        )
        Column(modifier = Modifier.padding(start = 11.dp).weight(1f)) {
            Text(
                text = if (todo > 0) "份未完成作业" else "全部交完",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.onHero,
                maxLines = 1,
            )
            Text(
                text = busy ?: subtitle,
                fontSize = 10.sp,
                color = Palette.onHero.copy(alpha = 0.74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            /*
             * 第三行恒存在：有提示就画「!」+ 文案，没有就画一个透明空格。
             * 用空格而不是 if 掉整行，是为了让行数恒定 → 高度恒定。
             */
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hasNote = !note.isNullOrBlank()
                if (hasNote) {
                    Box(
                        // 14dp 而不是 12dp：12dp 时「!」在放大字号下会被圆形裁掉下半截
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Palette.onHero.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "!",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.onHero,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = if (hasNote) note!! else " ",
                    fontSize = 10.sp,
                    color = if (hasNote) Palette.onHero.copy(alpha = 0.92f) else Color.Transparent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (hasNote) 4.dp else 0.dp),
                )
            }
        }
        // 全部交完时给一个勾，代替原来"0 变绿"的语义；
        // 主卡底色是品牌色，绿色压在上面会脏，用同色系的勾更干净
        if (todo == 0) {
            Icon(
                imageVector = Icons24.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Palette.onHero.copy(alpha = 0.65f),
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

/**
 * 课程首字。
 *
 * 课程名里有 "25-26-2 中国共产党党史" 这种学期前缀，直接取 `first()` 会显示成 "2"。
 * 所以跳过一个字也不是的字（数字、连字符、空格），取第一个真正的字母/汉字。
 * 全角括号之类的符号同样跳过；实在没有就退化成第一个字符。
 */
private fun initialOf(name: String): String {
    val c = name.firstOrNull { it.isLetter() } ?: name.firstOrNull() ?: '·'
    return c.toString()
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
            .clip(CardShape)
            .background(Palette.card)
            // 1dp 描边代替阴影：41 行 × 每行一层 elevation 是滚动掉帧的主要来源，
            // 描边同样能把白卡从冷灰底上"分"出来，成本只有一次 drawLine
            .border(1.dp, Palette.line, CardShape)
            // 长按置顶：不用多一个按钮占地方，也不容易误触（列表行本来就只点不拖）
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 首字块：给 41 门课一个可扫读的"落点"，比一整列纯文字快
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (digest.ok) Palette.brandSoft else Palette.alarmSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialOf(digest.course.name.ifBlank { "未命名课程" }),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (digest.ok) Palette.brand else Palette.alarm,
            )
        }

        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
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
                            .clip(RoundedCornerShape(6.dp))
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
                modifier = Modifier.padding(top = 2.dp),
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
 *
 * 全部用「浅底 + 同色文字」，不填充实色：实色块在长列表里会连成一片红，
 * 反而盖掉了真正要看的课程名。
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
            .clip(RoundedCornerShape(10.dp))
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
