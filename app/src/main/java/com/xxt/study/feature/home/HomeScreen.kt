package com.xxt.study.feature.home

import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.ui.agoText
import com.xxt.study.core.ui.rememberNow
import com.xxt.study.domain.isActionable
import com.xxt.study.domain.view
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.WorkView

@Composable
fun HomeScreen(
    onNeedLogin: () -> Unit,
    onOpenCourse: (CourseDigest) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 置顶集合来自 StudySettings 的 StateFlow：长按置顶后立刻重排，重进 App 也还在
    val pinnedKeys by vm.pinned.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // 红点要能"自己消失"：跨过截止时间后不需要重新扫描，下一拍统计就变
    val now = rememberNow()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        // 右上角只留刷新：设置和退出都归「我的」，两个入口并存只会让人犹豫点哪个
        HomeHeader(
            refreshing = state.loading,
            // 默认增量：12 分钟内取过的课直接复用，不再把 41 门课全重拉一遍。
            // 全量重扫改成**长按**——那是重活，也是风控的主要来源，不该是随手一点就发生的事。
            onRefresh = { vm.refresh() },
            onFullRefresh = {
                Toast.makeText(context, "全量重扫（忽略缓存）", Toast.LENGTH_SHORT).show()
                vm.refresh(force = true)
            },
        )

        val snapshot = state.snapshot
        // 判定口径统一走 Work.view()：结课课程的"未交"不算待办
        val actionableOf = { d: CourseDigest -> d.works.count { it.isActionable(now, d.course.closed) } }
        val expiredOf = { d: CourseDigest ->
            d.works.count { it.view(now, d.course.closed) == WorkView.EXPIRED }
        }
        // 卡外不再有裸文字行：覆盖课数、刷新时间、数据较旧、刷新进度全部进卡片，
        // 卡片高度固定，因此这些状态变化不会顶动下面的列表
        TodoBlock(
            todo = snapshot?.courses?.sumOf { actionableOf(it) } ?: 0,
            subtitle = when {
                snapshot == null -> "还没有数据，点右上角刷新"
                else -> "覆盖 ${snapshot.courseCount} 门课 · ${agoText(snapshot.at)}刷新" +
                    if (snapshot.failedCount > 0) " · ${snapshot.failedCount} 门未取到" else ""
            },
            note = snapshot?.note,
            busy = state.progress,
        )

        state.error?.let { err ->
            ErrorBanner(
                message = err,
                // 风控失败才好意思放"重新验证"：别的失败（网络/解析）点了也没用
                actionText = if (state.verifyBlocked) "重新验证" else null,
                onAction = if (state.verifyBlocked) {
                    { VerifyGate.forceReopen() }
                } else {
                    null
                },
            )
        }

        // 登录态失效不再自动跳回登录页（会与登录页的自动检测形成来回跳），
        // 改成明确的入口，由用户决定什么时候重新登录。
        if (state.needLogin) {
            ReloginBanner(onClick = onNeedLogin)
        }

        val courses = snapshot?.courses.orEmpty()
        // 搜索只在内存里过滤，不发请求：41 门课全在手上，没必要往返
        val shown = remember(courses, query, pinnedKeys) {
            val hit = if (query.isBlank()) courses else courses.filter { fuzzyMatch(it.course.name, query) }
            orderCourses(hit, pinnedKeys)
        }
        // 没有可交作业的课（全过期 / 全完成 / 本来就没作业）归档折叠，
        // 免得上学期的课把正在上的课挤下去
        var archiveOpen by remember { mutableStateOf(false) }

        if (courses.isNotEmpty()) {
            SearchBar(value = query, onChange = { query = it })
        }

        when {
            snapshot == null && state.loading -> CenterHint("正在读取课程与作业", Modifier.weight(1f))
            snapshot == null -> CenterHint("点右上角刷新试试", Modifier.weight(1f))
            courses.isEmpty() -> CenterHint("本学期还没有课程", Modifier.weight(1f))
            shown.isEmpty() -> CenterHint("没找到「${query.trim()}」", Modifier.weight(1f))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /*
                 * 置顶课程单独一组排最前，且**不参与"有代办/归档"的划分**。
                 *
                 * 否则一门已结课、没有待办的课被置顶后反而会掉进"暂无待办"折叠区里，
                 * 用户长按置顶却看不到它在上面，只会以为没生效。
                 */
                val pinnedCourses = shown.filter { it.key in pinnedKeys }
                val rest = shown.filterNot { it.key in pinnedKeys }
                val active = rest.filter { actionableOf(it) > 0 }
                val archived = rest.filter { actionableOf(it) <= 0 }

                /*
                 * 没有置顶课时**不插入这个 item**。
                 *
                 * 之前是 item 里再 if 判空：空 item 高度为 0，但 spacedBy(8dp) 仍会在它
                 * 前后各留一份间距 —— 视觉上就是"按课程"下面空了一大块。
                 * 条件写在 item 外面，列表第一项直接就是课程卡。
                 */
                if (pinnedCourses.isNotEmpty()) {
                    item(key = "pinned-header") {
                        Text(
                            text = "置顶 · ${pinnedCourses.size} 门",
                            fontSize = 11.sp,
                            color = Palette.weak,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                        )
                    }
                }

                items(pinnedCourses, key = { "${it.course.courseId}:${it.course.classId}" }) { digest ->
                    CourseItem(
                        digest = digest,
                        pinned = true,
                        actionable = actionableOf(digest),
                        expired = expiredOf(digest),
                        refreshing = "${'$'}{digest.key}" in state.refreshingCourses,
                        note = state.courseNotes[digest.key],
                        onOpen = {
                            if (digest.ok) {
                                onOpenCourse(it)
                            } else {
                                Toast.makeText(context, digest.error ?: "这门课暂时取不到", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTogglePin = { key ->
                            // 长按没有视觉即时反馈，给一句话确认；置顶标识会跟着列表一起变
                            val nowPinned = vm.togglePin(key)
                            Toast.makeText(
                                context,
                                if (nowPinned) "已置顶「${digest.course.name}」" else "已取消置顶",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onRefresh = { d -> vm.refreshCourse(d.course) },
                    )
                }

                if (active.isNotEmpty()) {
                    item(key = "active-header") {
                        if (pinnedCourses.isNotEmpty()) {
                            Text(
                                text = "其他课程",
                                fontSize = 11.sp,
                                color = Palette.weak,
                                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }

                items(active, key = { "${it.course.courseId}:${it.course.classId}" }) { digest ->
                    CourseItem(
                        digest = digest,
                        pinned = false,
                        actionable = actionableOf(digest),
                        expired = expiredOf(digest),
                        refreshing = "${'$'}{digest.key}" in state.refreshingCourses,
                        note = state.courseNotes[digest.key],
                        onOpen = {
                            if (digest.ok) {
                                onOpenCourse(it)
                            } else {
                                Toast.makeText(context, digest.error ?: "这门课暂时取不到", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTogglePin = { key ->
                            // 长按没有视觉即时反馈，给一句话确认；置顶标识会跟着列表一起变
                            val nowPinned = vm.togglePin(key)
                            Toast.makeText(
                                context,
                                if (nowPinned) "已置顶「${digest.course.name}」" else "已取消置顶",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onRefresh = { d -> vm.refreshCourse(d.course) },
                    )
                }

                if (archived.isNotEmpty()) {
                    item(key = "archive-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { archiveOpen = !archiveOpen }
                                .padding(horizontal = 2.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "暂无待办 · ${archived.size} 门",
                                fontSize = 12.sp,
                                color = Palette.weak,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (archiveOpen) "收起" else "展开",
                                fontSize = 11.sp,
                                color = Palette.brand,
                            )
                        }
                    }
                    if (archiveOpen) {
                        items(archived, key = { "${it.course.courseId}:${it.course.classId}" }) { digest ->
                            CourseItem(
                            digest = digest,
                            pinned = false,
                            actionable = actionableOf(digest),
                            expired = expiredOf(digest),
                            refreshing = "${'$'}{digest.key}" in state.refreshingCourses,
                            note = state.courseNotes[digest.key],
                            onOpen = {
                                if (digest.ok) {
                                    onOpenCourse(it)
                                } else {
                                    Toast.makeText(context, digest.error ?: "这门课暂时取不到", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTogglePin = { key ->
                            // 长按没有视觉即时反馈，给一句话确认；置顶标识会跟着列表一起变
                            val nowPinned = vm.togglePin(key)
                            Toast.makeText(
                                context,
                                if (nowPinned) "已置顶「${digest.course.name}」" else "已取消置顶",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                            onRefresh = { d -> vm.refreshCourse(d.course) },
                    )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一行课程的完整行为：点开、长按置顶、独立刷新。
 *
 * 抽这一层是为了让三个分组（置顶 / 待办 / 归档）用**完全相同**的一套回调——
 * 否则很容易出现"归档区里的课没有刷新键"这类不对称。
 */
/**
 * 列表排序：置顶的在前，其余保持原顺序。
 *
 * `sortedByDescending` 是**稳定排序**，所以同一组内不会被打乱——
 * repo 已经按"待办多的在前"排好，置顶只应该改变分组，不该改变组内优先级。
 *
 * 抽成纯函数是为了能单测（见 `HomeOrderTest`）：排序这种"看起来不会错"的代码，
 * 恰恰最容易在一次重构里被写成 `sortedBy { pinned }`（顺序反了）。
 */
internal fun orderCourses(shown: List<CourseDigest>, pinned: Set<String>): List<CourseDigest> =
    shown.sortedByDescending { it.key in pinned }

@Composable
private fun CourseItem(
    digest: CourseDigest,
    pinned: Boolean,
    actionable: Int,
    expired: Int,
    refreshing: Boolean,
    note: CourseNote?,
    onOpen: (CourseDigest) -> Unit,
    onTogglePin: (String) -> Unit,
    onRefresh: (CourseDigest) -> Unit,
) {
    CourseRow(
        digest = digest,
        actionable = actionable,
        expired = expired,
        pinned = pinned,
        refreshing = refreshing,
        note = note,
        onClick = { onOpen(digest) },
        onLongClick = { onTogglePin(digest.key) },
        onRefresh = { onRefresh(digest) },
    )
}

@Composable
private fun HomeHeader(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onFullRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 升到 20sp / Bold：B 方案的层级是"页标题压住主卡"，
        // 18sp Medium 和主卡里的 32sp 数字差太小，读起来分不清谁是标题
        Text(
            text = "学习通",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.ink,
            modifier = Modifier.weight(1f),
        )
        IconAction(
            icon = Icons24.Refresh,
            label = if (refreshing) "刷新中" else "刷新（长按全量重扫）",
            enabled = !refreshing,
            onClick = onRefresh,
            onLongClick = onFullRefresh,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(21.dp),
            tint = if (enabled) Palette.sub else Palette.line,
        )
    }
}

/**
 * 课程搜索框：内存过滤，不走网络。
 *
 * 做成胶囊 + 1dp 描边，而不是"白卡 + 阴影"：
 * 它紧贴主卡下沿，有阴影会和主卡糊在一起；描边则始终干净。
 */
@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(shape)
            .background(Palette.card)
            .border(1.dp, Palette.line, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons24.Search,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = Palette.weak,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            if (value.isEmpty()) {
                Text("搜索课程名，如「高数」「英语」", fontSize = 13.sp, color = Palette.weak)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Palette.ink),
                cursorBrush = SolidColor(Palette.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Icons24.Close,
                contentDescription = "清除",
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onChange("") },
                tint = Palette.weak,
            )
        }
    }
}

/**
 * 模糊匹配。
 *
 * 两级：先比连续子串（"高数"命中"高等数学"？不命中），再退化为
 * "字符按顺序出现"（"高数" → 高…数，命中）。
 * 多关键词用空格分隔，要求每个词都命中——和常见搜索框的习惯一致。
 *
 * 大小写与空白一律忽略：课程名里常有"25-26-2 中国共产党党史"这类混排。
 */
internal fun fuzzyMatch(name: String, query: String): Boolean {
    val hay = name.lowercase().filterNot { it.isWhitespace() }
    if (hay.isEmpty()) return false
    val parts = query.lowercase().split(' ', '\t', '\u3000').filter { it.isNotBlank() }
    if (parts.isEmpty()) return true
    return parts.all { raw ->
        val p = raw.filterNot { it.isWhitespace() }
        p.isEmpty() || hay.contains(p) || isSubsequence(p, hay)
    }
}

internal fun isSubsequence(needle: String, hay: String): Boolean {
    var i = 0
    for (c in hay) {
        if (i < needle.length && needle[i] == c) i++
        if (i == needle.length) return true
    }
    return i == needle.length
}
