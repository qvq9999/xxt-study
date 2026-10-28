package com.xxt.study.feature.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.ui.countdownText
import com.xxt.study.core.ui.isExpired
import com.xxt.study.core.ui.isUrgent
import com.xxt.study.core.ui.rememberNow
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkStatus
import com.xxt.study.domain.isActionable

/**
 * 作业列表。
 * 数据直接来自首页那次刷新拿到的 works，不再额外请求——
 * 这也是为什么刷新时把每门课的作业一起存下来：进了课程不该再等一次网络。
 */
@Composable
fun WorkListScreen(
    digest: CourseDigest,
    onBack: () -> Unit,
    onOpenWork: (Work) -> Unit,
    onStartStudy: () -> Unit,
) {
    // 每 30 秒走一次：倒计时按分钟/小时显示，秒级刷新只是白耗电
    val now = rememberNow()
    val sorted = sortWorks(digest.works)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons24.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(22.dp),
                    tint = Palette.sub,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = digest.course.name.ifBlank { "未命名课程" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Text(
                    text = "${digest.todoCount} 份未交 · 共 ${digest.works.size} 份",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 刷课入口放在课程页而不是首页：刷课是按课程跑的，先选中课程才谈得上刷
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.brandSoft)
                    .clickable(onClick = onStartStudy)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "刷课",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.brand,
                )
            }
        }

        // 折叠状态：待提交默认展开，另外两组默认收起
        val expanded = remember { mutableStateMapOf<WorkGroup, Boolean>() }
        fun isExpanded(g: WorkGroup) = expanded[g] ?: (g == WorkGroup.TODO)
        val sections = WorkGroup.entries
            .map { g -> g to sorted.filter { groupOfWork(it, now) == g } }
            .filter { it.second.isNotEmpty() }

        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons24.Check,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Palette.line,
                    )
                    Text(
                        text = "这门课没有作业",
                        fontSize = 13.sp,
                        color = Palette.weak,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { (group, list) ->
                    item(key = "h-" + group.name) {
                        GroupHeader(
                            group = group,
                            count = list.size,
                            expanded = isExpanded(group),
                            onToggle = { expanded[group] = !isExpanded(group) },
                        )
                    }
                    if (isExpanded(group)) {
                        items(list, key = { it.detailUrl }) { work ->
                            WorkRow(work, now) { onOpenWork(work) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkRow(work: Work, now: Long, onClick: () -> Unit) {
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
                text = work.title.ifBlank { "未命名作业" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            val sub = subtitleOf(work)
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    fontSize = 11.sp,
                    color = if (isOverdue(work, now)) Palette.alarm else Palette.weak,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        StatusChip(work, now)
        Icon(
            imageVector = Icons24.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Palette.weak,
        )
    }
}

@Composable
private fun StatusChip(work: Work, now: Long) {
    val deadline = work.deadline
    val pending = work.status == WorkStatus.PENDING
    val expired = pending && deadline != null && isExpired(deadline, now)
    val urgent = pending && deadline != null && isUrgent(deadline, now)

    // 未交且知道截止时间时，chip 直接显示倒计时——这比"未交"两个字有用得多
    val label = when {
        expired -> "已过期"
        pending && deadline != null -> countdownText(deadline, now)
        else -> when (work.status) {
            WorkStatus.PENDING -> "未交"
            WorkStatus.SUBMITTED -> "待批阅"
            WorkStatus.GRADED -> "已完成"
            WorkStatus.OVERDUE -> "已过期"
            WorkStatus.UNKNOWN -> work.rawLabel.ifBlank { "未知" }
        }
    }

    val bg = when {
        expired || urgent -> Palette.alarmSoft
        pending -> Palette.brandSoft
        work.status == WorkStatus.SUBMITTED -> Palette.brandSoft
        work.status == WorkStatus.GRADED -> Palette.okSoft
        else -> Palette.bg
    }
    val fg = when {
        expired || urgent -> Palette.alarm
        pending -> Palette.brand
        work.status == WorkStatus.SUBMITTED -> Palette.brand
        work.status == WorkStatus.GRADED -> Palette.ok
        else -> Palette.weak
    }

    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 未交且过了截止时间 */
private fun isOverdue(work: Work, now: Long): Boolean =
    work.status == WorkStatus.PENDING && work.deadline?.let { isExpired(it, now) } == true

/**
 * 副标题：状态 + 绝对截止时间。
 *
 * 用绝对时间（10-15 23:59）而不是再写一遍"剩余多少"——
 * 剩余量已经在右边 chip 上实时走了，这里给出确切时刻便于对照。
 */
private fun subtitleOf(work: Work): String {
    val state = STRIP_REMAIN.replace(work.rawLabel, "").trim().ifBlank { work.status.name }
    val deadline = work.deadline ?: return state
    val at = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(deadline))
    return "$state · 截止 $at"
}

/** 去掉 aria-label 里的「剩余394小时23分钟」，避免和 chip 的倒计时重复 */
private val STRIP_REMAIN = Regex(
    "剩余\\s*(?:(\\d+)\\s*天)?\\s*(?:(\\d+)\\s*(?:小时|时))?\\s*(?:(\\d+)\\s*分)?",
)


/**
 * 作业分组。
 *
 * 为什么按这个维度分：用户真正想一眼看到的是"我现在还能交什么"，
 * 而不是"我历史上欠了多少"。上学期没交的作业堆在同一个列表里，
 * 会把真正紧急的东西埋掉——所以已截止和已完成默认折叠起来。
 */
private enum class WorkGroup(val title: String) {
    TODO("待提交"),
    EXPIRED("已截止"),
    DONE("已完成"),
}

private fun groupOfWork(w: Work, now: Long): WorkGroup = when {
    w.isActionable(now) -> WorkGroup.TODO
    w.status == WorkStatus.PENDING -> WorkGroup.EXPIRED
    else -> WorkGroup.DONE
}

/**
 * 未交排最前，同状态内**快截止的在前**——用户最关心的是"马上要过期的那份"。
 * 没有截止时间的排在同类末尾（Long.MAX_VALUE），不占前面的位置。
 */
internal fun sortWorks(works: List<Work>): List<Work> {
    fun rank(s: WorkStatus) = when (s) {
        WorkStatus.PENDING -> 0
        WorkStatus.OVERDUE -> 1
        WorkStatus.SUBMITTED -> 2
        WorkStatus.GRADED -> 3
        WorkStatus.UNKNOWN -> 4
    }
    return works.sortedWith(
        compareBy<Work> { rank(it.status) }.thenBy { it.deadline ?: Long.MAX_VALUE },
    )
}

/** 分组头：点一下收起 / 展开 */
@Composable
private fun GroupHeader(group: WorkGroup, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val color = when (group) {
        WorkGroup.TODO -> Palette.alarm
        WorkGroup.EXPIRED -> Palette.weak
        WorkGroup.DONE -> Palette.ok
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Text(
            text = " · $count 份",
            fontSize = 11.sp,
            color = Palette.weak,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (expanded) "收起" else "展开",
            fontSize = 11.sp,
            color = Palette.brand,
        )
    }
}
