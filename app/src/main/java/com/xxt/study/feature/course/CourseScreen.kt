package com.xxt.study.feature.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.result.Cx
import com.xxt.study.core.study.StudyController
import com.xxt.study.core.ui.AppModal
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.ui.rememberNow
import com.xxt.study.data.remote.chaoxing.ActivityClient
import com.xxt.study.data.repo.PracticeHandler
import com.xxt.study.data.repo.StudyRepo
import com.xxt.study.domain.Activity
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.PracticeQuestion
import com.xxt.study.domain.PracticeSheet
import com.xxt.study.domain.Work
import com.xxt.study.domain.typeLabel
import com.xxt.study.domain.view
import com.xxt.study.feature.study.StudyScreen
import com.xxt.study.feature.work.WorkListScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 课程二级页的三个页签。顺序就是用户关心的顺序：有活动先看活动，再刷课，最后看作业 */
enum class CourseTab(val label: String) {
    Task("任务"),
    Chapter("章节"),
    Work("作业"),
}

/**
 * 课程二级页：任务 / 章节 / 作业 三个页签。
 *
 * 改造前这里直接就是作业列表，刷课还要再点一次按钮跳到另一个全屏页；
 * 现在三个入口平铺在同一层，课程名只出现一次（子页面用 embedded = true 不再画自己的标题栏）。
 *
 * 页签不做滑动切换：滑动会和列表纵向滚动、以及「章节」页里的 WebView 抢手势，
 * 点击切换是零冲突的做法。
 */
@Composable
fun CourseScreen(
    digest: CourseDigest,
    repo: StudyRepo,
    controller: StudyController,
    ai: AiClient,
    cookies: CookieStore,
    activities: ActivityClient,
    onBack: () -> Unit,
    onDigestUpdated: (CourseDigest) -> Unit,
    onOpenWork: (Work) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 页签记在课程 key 上：换一门课重新从「任务」开始，同一门课来回切不丢位置
    var tab by remember(digest.key) { mutableStateOf(CourseTab.Task) }
    val closed = digest.course.closed
    val now = rememberNow()
    val openCount = digest.works.count { it.view(now, closed).countsAsTodo }
    // 作答流程无状态，按客户端复用即可
    val practice = remember(activities, ai) { PracticeHandler(activities, ai) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
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
                    fontWeight = FontWeight.Bold,
                    color = Palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (closed) {
                        "已结课 · 共 ${digest.works.size} 份作业"
                    } else {
                        "$openCount 份待提交 · 共 ${digest.works.size} 份作业"
                    },
                    fontSize = 11.sp,
                    color = Palette.weak,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        TabStrip(selected = tab, onSelect = { tab = it })

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                CourseTab.Task -> PracticeTab(
                    course = digest.course,
                    key = digest.key,
                    client = activities,
                    handler = practice,
                )
                CourseTab.Chapter -> StudyScreen(
                    course = digest.course,
                    controller = controller,
                    ai = ai,
                    cookies = cookies,
                    onBack = onBack,
                    onOpenSettings = onOpenSettings,
                    // 标题与返回由本页负责：子页面只留自己的操作区
                    embedded = true,
                )
                CourseTab.Work -> WorkListScreen(
                    digest = digest,
                    repo = repo,
                    onBack = onBack,
                    onDigestUpdated = onDigestUpdated,
                    onOpenWork = onOpenWork,
                    embedded = true,
                )
            }
        }
    }
}

/** 三个页签：文字 + 2dp 短横线，静态绘制，无位移动画 */
@Composable
private fun TabStrip(selected: CourseTab, onSelect: (CourseTab) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            for (t in CourseTab.entries) {
                val active = t == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(t) }
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = t.label,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Palette.ink else Palette.weak,
                        textAlign = TextAlign.Center,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(width = 18.dp, height = 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) Palette.brand else Color.Transparent),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    }
}

/**
 * 「任务」页：随堂练习列表。
 *
 * 只显示 `activeType=42`。同一条活动可能同时出现在"进行中/已结束"两个分组里，客户端已按 id 去重。
 */
@Composable
private fun PracticeTab(
    course: Course,
    key: String,
    client: ActivityClient,
    handler: PracticeHandler,
) {
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var list by remember(key) { mutableStateOf<List<Activity>>(emptyList()) }
    var tick by remember(key) { mutableStateOf(0) }
    var opening by remember { mutableStateOf<Activity?>(null) }

    LaunchedEffect(key, tick) {
        loading = true
        error = null
        when (val r = client.activities(course)) {
            is Cx.Ok -> list = r.data.filter { it.isPractice }
            is Cx.Fail -> error = r.detail
            Cx.NeedLogin -> error = "登录态失效，请重新登录"
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "随堂练习",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.ink,
            )
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.6.dp,
                    color = Palette.brand,
                )
            } else {
                Text(
                    text = "刷新",
                    fontSize = 11.sp,
                    color = Palette.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { tick++ }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        when {
            loading && list.isEmpty() -> Hint("正在读取随堂练习…")
            error != null -> Hint(error!!)
            list.isEmpty() -> Hint("这门课暂时没有随堂练习")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { a ->
                    ActivityRow(activity = a, onClick = { opening = a })
                }
            }
        }
    }

    opening?.let { a ->
        PracticeModal(
            course = course,
            activity = a,
            client = client,
            handler = handler,
            onDismiss = { opening = null },
        )
    }
}

@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (activity.running) Palette.brandSoft else Palette.bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons24.Book,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (activity.running) Palette.brand else Palette.weak,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = activity.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(activity.statusLabel)
                    if (activity.startAt > 0) append(" · ").append(timeText(activity.startAt))
                    if (activity.attendCount > 0) append(" · ").append("${activity.attendCount} 人参与")
                },
                fontSize = 11.sp,
                color = if (activity.running) Palette.brand else Palette.weak,
                modifier = Modifier.padding(top = 2.dp),
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

/**
 * 随堂练习：取卷 → AI 作答 → 逐题可改 → 提交。
 *
 * 每题一个独立输入框，按题目 id 一一对应。**不做"答完一题自动跳下一题"**：
 * 随堂练习常把好几道简答排在同一页，自动跳转最容易把答案写进下一题的框。
 * 提交时按 id 逐题映射，只有多空填空题才按行拆。
 */
@Composable
private fun PracticeModal(
    course: Course,
    activity: Activity,
    client: ActivityClient,
    handler: PracticeHandler,
    onDismiss: () -> Unit,
) {
    var loading by remember(activity.id) { mutableStateOf(true) }
    var error by remember(activity.id) { mutableStateOf<String?>(null) }
    var sheet by remember(activity.id) { mutableStateOf<PracticeSheet?>(null) }
    var solving by remember(activity.id) { mutableStateOf(false) }
    var submitting by remember(activity.id) { mutableStateOf(false) }
    var confirm by remember(activity.id) { mutableStateOf(false) }
    var submitted by remember(activity.id) { mutableStateOf(false) }
    var banner by remember(activity.id) { mutableStateOf<String?>(null) }
    val answers = remember(activity.id) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activity.id) {
        when (val r = client.practiceSheet(activity.id)) {
            is Cx.Ok -> {
                sheet = r.data
                // 已提交过的卷子：把服务端答案填回各自的框，
                // 否则用户看到空框会以为答案丢了，再点提交就覆盖成空
                r.data.questions.forEach { q ->
                    if (q.answerText.isNotBlank()) answers[q.id] = q.answerText
                }
                // 服务端说交过了，但一条答案文本都没取到：说明字段形态跟预期不同。
                // 这时必须明说，不能静默显示空框让用户以为答案丢了
                if (r.data.serverAnswered && r.data.questions.none { it.answerText.isNotBlank() }) {
                    banner = "服务端记录这份已作答，但没解析到答案内容（字段形态可能变了）"
                }
            }
            is Cx.Fail -> error = r.detail
            Cx.NeedLogin -> error = "登录态失效，请重新登录"
        }
        loading = false
    }

    fun solve() {
        val s = sheet ?: return
        if (solving) return
        solving = true
        banner = null
        scope.launch {
            when (val r = handler.solve(s) { note -> banner = note }) {
                is Cx.Ok -> {
                    r.data.forEach { (qid, ans) -> answers[qid] = ans.joinToString("\n") }
                    val missing = s.questions.count { answers[it.id].isNullOrBlank() }
                    banner = if (missing > 0) {
                        "已生成 ${r.data.size} 题答案，还有 $missing 题空着，可手动补"
                    } else {
                        "已生成 ${r.data.size} 题答案，可逐题修改后提交"
                    }
                }
                is Cx.Fail -> banner = r.detail
                Cx.NeedLogin -> banner = "AI 接口需要重新配置"
            }
            solving = false
        }
    }

    fun submit() {
        val s = sheet ?: return
        if (submitting) return
        submitting = true
        banner = null
        scope.launch {
            val payload = s.questions.associate { q -> q.id to splitAnswer(q, answers[q.id].orEmpty()) }
            // 之前交过就必须走 reDo：do 接口在已有作答时会被服务端拒绝
            val redo = s.questions.any { it.answered } || submitted
            when (val r = handler.submit(course, s, payload, redo)) {
                is Cx.Ok -> {
                    submitted = true
                    banner = "提交成功，可修改后再次提交"
                    confirm = false
                    // 重新取一次卷面：服务端会把作答内容与「是否允许修改」一起带回来，
                    // 这样副标题的「已作答」和按钮语义就是真实状态，而不是本地标志猜的
                    when (val fresh = client.practiceSheet(activity.id)) {
                        is Cx.Ok -> {
                            sheet = fresh.data
                            fresh.data.questions.forEach { q ->
                                if (q.answerText.isNotBlank()) answers[q.id] = q.answerText
                            }
                        }
                        else -> Unit
                    }
                }
                is Cx.Fail -> banner = "提交失败：${r.detail}"
                Cx.NeedLogin -> banner = "登录态失效，请重新登录"
            }
            submitting = false
        }
    }

    val running = sheet?.status == 1 || activity.running
    val answeredCount = answers.count { it.value.isNotBlank() }
    // 服务端已有作答，或本次刚交过
    val answeredBefore = submitted || (sheet?.questions?.any { it.answered } == true)

    AppModal(
        title = sheet?.name ?: activity.name,
        subtitle = sheet?.let {
            "${it.questions.size} 题 · ${it.typeSummary}" + if (it.serverAnswered) " · 已作答" else ""
        },
        onDismiss = onDismiss,
        footer = {
            Column {
                banner?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = Palette.sub,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.bg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(
                        // 已有答案时这个按钮的语义是"改一版"：AI 重写，
                        // 写完仍然逐题可改，不是直接提交
                        text = when {
                            solving -> "修改中…"
                            answeredBefore -> "修改"
                            else -> "AI 作答"
                        },
                        enabled = running && sheet != null && !solving && !submitting,
                        primary = !answeredBefore,
                        onClick = ::solve,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton(
                        text = when {
                            submitting -> "提交中…"
                            confirm -> "确认提交 $answeredCount 题"
                            else -> "提交"
                        },
                        enabled = running && answeredCount > 0 && !submitting && !solving,
                        primary = true,
                        onClick = { if (confirm) submit() else confirm = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!running) {
                    Text(
                        text = "活动已结束，不能再提交",
                        fontSize = 10.sp,
                        color = Palette.weak,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (sheet?.editable == false && !confirm && !submitting && banner == null) {
                    // 服务端 isStuEditAnswer=0：网页端这时会直接把页面切回只读。
                    // 按钮不关掉（用户可能想试），但先说清重交大概率被拒，免得白点
                    Text(
                        text = "这份已提交，老师未开启修改，重交可能被服务端拒绝",
                        fontSize = 10.sp,
                        color = Palette.alarm,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (answeredBefore && !confirm && !submitting && banner == null) {
                    Text(
                        text = "这份已提交过，改完再点提交会覆盖上一次的答案",
                        fontSize = 10.sp,
                        color = Palette.weak,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (confirm && !submitting) {
                    Text(
                        text = "再点一次才会真正提交，提交后一般不能重做",
                        fontSize = 10.sp,
                        color = Palette.alarm,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
    ) {
        when {
            loading -> Text("正在取卷…", fontSize = 12.sp, color = Palette.weak)
            error != null -> Text(error!!, fontSize = 12.sp, color = Palette.alarm)
            sheet == null -> Text("卷面为空", fontSize = 12.sp, color = Palette.weak)
            else -> sheet!!.questions.forEach { q ->
                QuestionBlock(
                    q = q,
                    value = answers[q.id].orEmpty(),
                    onChange = { answers[q.id] = it },
                )
            }
        }
    }
}

@Composable
private fun QuestionBlock(q: PracticeQuestion, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = "${q.seq}. [${typeLabel(q.type)}] ${q.integral} 分",
            fontSize = 11.sp,
            color = Palette.weak,
        )
        Text(
            text = q.stem,
            fontSize = 13.sp,
            color = Palette.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
        q.options.forEach { o ->
            Text(
                text = "${o.letter}. ${o.text}",
                fontSize = 12.sp,
                color = Palette.sub,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp),
            )
        }
        // 每题一个独立框：多道简答各自对应，不会串行
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.bg)
                .border(1.dp, Palette.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = when {
                        q.isChoice -> "填写选项字母，如 A"
                        q.blanks > 1 -> "按空换行填写，共 ${q.blanks} 空"
                        else -> "填写答案"
                    },
                    fontSize = 12.sp,
                    color = Palette.weak,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(fontSize = 13.sp, color = Palette.ink, lineHeight = 19.sp),
                cursorBrush = SolidColor(Palette.brand),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (q.isChoice) 20.dp else 44.dp),
            )
        }
        if (q.blanks > 1) {
            Text(
                text = "多空题：每行对应一空，共 ${q.blanks} 空",
                fontSize = 10.sp,
                color = Palette.weak,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 把界面上的单框文本还原成提交用的答案数组。
 *
 * - 多空填空题：按行拆（界面提示就是"每行一空"），不足补空串
 * - 其它题：整体作为一条答案，**不拆行**——简答题里的换行是排版，不是分空
 */
private fun splitAnswer(q: PracticeQuestion, text: String): List<String> {
    val t = text.trim()
    if (t.isEmpty()) return emptyList()
    if (q.blanks > 1) {
        val parts = t.split("\n").map { it.trim() }
        return List(maxOf(q.blanks, parts.size)) { parts.getOrElse(it) { "" } }
    }
    return listOf(t)
}

@Composable
private fun SmallButton(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        !enabled -> Palette.bg
        primary -> Palette.brand
        else -> Palette.brandSoft
    }
    val fg = when {
        !enabled -> Palette.weak
        primary -> Palette.onBrand
        else -> Palette.brand
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun Hint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 12.sp, color = Palette.weak)
    }
}

private fun timeText(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
