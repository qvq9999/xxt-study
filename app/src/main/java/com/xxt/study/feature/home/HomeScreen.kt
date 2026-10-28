package com.xxt.study.feature.home

import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
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
        TodoBlock(
            todo = snapshot?.courses?.sumOf { actionableOf(it) } ?: 0,
            subtitle = when {
                snapshot == null -> "还没有数据，点右上角刷新"
                else -> "覆盖 ${snapshot.courseCount} 门课 · ${agoText(snapshot.at)}刷新" +
                    if (snapshot.failedCount > 0) " · ${snapshot.failedCount} 门未取到" else ""
            },
        )

        state.progress?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = Palette.sub,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        snapshot?.note?.let { note ->
            Text(
                text = note,
                fontSize = 11.sp,
                color = Palette.alarm,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        state.error?.let { ErrorBanner(it) }

        // 登录态失效不再自动跳回登录页（会与登录页的自动检测形成来回跳），
        // 改成明确的入口，由用户决定什么时候重新登录。
        if (state.needLogin) {
            ReloginBanner(onClick = onNeedLogin)
        }

        Spacer(Modifier.height(4.dp))

        val courses = snapshot?.courses.orEmpty()
        // 搜索只在内存里过滤，不发请求：41 门课全在手上，没必要往返
        val shown = remember(courses, query) {
            if (query.isBlank()) courses else courses.filter { fuzzyMatch(it.course.name, query) }
        }
        // 没有可交作业的课（全过期 / 全完成 / 本来就没作业）归档折叠，
        // 免得上学期的课把正在上的课挤下去
        var archiveOpen by remember { mutableStateOf(false) }

        if (courses.isNotEmpty()) {
            SearchBar(value = query, onChange = { query = it })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (query.isBlank()) "按课程" else "搜索「${query.trim()}」",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.sub,
            )
            if (courses.isNotEmpty()) {
                Text(
                    text = if (query.isBlank()) "${courses.size} 门" else "${shown.size}/${courses.size} 门",
                    fontSize = 12.sp,
                    color = Palette.weak,
                )
            }
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
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val active = shown.filter { actionableOf(it) > 0 }
                val archived = shown.filter { actionableOf(it) <= 0 }

                items(active, key = { "${it.course.courseId}:${it.course.classId}" }) { digest ->
                    CourseRow(digest, actionableOf(digest), expiredOf(digest)) {
                        if (digest.ok) {
                            onOpenCourse(digest)
                        } else {
                            Toast.makeText(context, digest.error ?: "这门课暂时取不到", Toast.LENGTH_SHORT).show()
                        }
                    }
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
                            CourseRow(digest, 0, expiredOf(digest)) { onOpenCourse(digest) }
                        }
                    }
                }
            }
        }
    }
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
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("学习通", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
            Text("作业助手", fontSize = 12.sp, color = Palette.weak)
        }
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
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
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
            modifier = Modifier.size(22.dp),
            tint = if (enabled) Palette.sub else Palette.line,
        )
    }
}

/** 课程搜索框：内存过滤，不走网络 */
@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons24.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
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
