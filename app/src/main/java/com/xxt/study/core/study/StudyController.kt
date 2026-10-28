package com.xxt.study.core.study

import android.content.Context
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.StudyClient
import com.xxt.study.data.repo.QuizHandler
import com.xxt.study.data.repo.StudyEvent
import com.xxt.study.data.repo.StudyRunner
import com.xxt.study.domain.Course
import com.xxt.study.domain.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 刷课界面需要的一切状态，UI 只读这个 */
data class StudyUiState(
    val running: Boolean = false,
    val courseName: String = "",
    val chapterIndex: Int = 0,
    val chapterTotal: Int = 0,
    val chapterTitle: String = "",
    val jobTitle: String = "",
    val fraction: Float = 0f,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    /** 当前这次任务属于哪门课（"courseId:classId"）；没跑过就是空串 */
    val courseKey: String = "",
    /**
     * 运行日志，**按课程分开**，最新在最上面。
     *
     * 全局一份日志会让"点开另一门课"时看到上一门课的记录和计数，
     * 用户会以为这门课已经在跑。键是同 [Course.key] 的写法。
     */
    val logs: Map<String, List<String>> = emptyMap(),
    val needLogin: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    /** 取某门课的日志；没跑过就是空列表，不会串到别的课 */
    fun logFor(key: String): List<String> = logs[key].orEmpty()

    /** 当前这次任务是不是属于这门课 */
    fun belongsTo(key: String): Boolean = courseKey == key

    /** 通知栏/标题用的单行进度 */
    val statusLine: String
        get() = when {
            finished -> "已完成：成功 $done / 失败 $failed / 跳过 $skipped"
            !running -> "未开始"
            jobTitle.isNotBlank() -> "第 $chapterIndex/$chapterTotal 章 · $jobTitle"
            chapterTitle.isNotBlank() -> "第 $chapterIndex/$chapterTotal 章 · $chapterTitle"
            else -> "准备中"
        }
}

/**
 * 刷课总控。
 *
 * 为什么不让前台服务直接跑业务：服务在低内存时会被系统销毁重建，
 * 那样任务会从第 0 个任务点重跑一遍——刷课是有副作用的操作，重跑等于重复上报。
 * 所以业务留在协程里（跟着 Application 活），服务只负责保活和通知。
 */
class StudyController(
    private val context: Context,
    private val client: StudyClient,
    private val settings: StudySettings,
    private val quizHandlerProvider: () -> QuizHandler?,
) {

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    private var scope = CoroutineScope(SupervisorJob())
    private var running: Job? = null

    /**
     * 验证通过后要续跑哪门课。
     *
     * 刷课跑到一半撞上风控，任务会被中止；用户完成验证后如果还要手动点一次「开始」，
     * 就等于把"验证一下就好"变成了"重新开始"。重跑本身是安全的——已经标记过的任务点
     * 在解析阶段就被过滤掉了（见 StudyRunner），所以这里直接续。
     */
    private var pendingResume: Course? = null
    private val resumeScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    init {
        resumeScope.launch {
            VerifyGate.passed.collect {
                val c = pendingResume ?: return@collect
                pendingResume = null
                if (running?.isActive != true) start(c)
            }
        }
    }

    fun start(course: Course) {
        if (running?.isActive == true) return

        val key = course.key
        // 重跑就是新一轮：清掉这门课上轮的日志，别让旧记录和新进度混在一起
        _state.value = StudyUiState(
            running = true,
            courseKey = key,
            courseName = course.name.ifBlank { "未命名课程" },
            logs = StudyLogs.reset(_state.value.logs, key),
        )
        StudyService.start(context, "正在准备：${course.name.ifBlank { "课程" }}")
        StudyService.acquireWakeLock(context)

        val handler = if (settings.autoQuiz) quizHandlerProvider() else null
        val runner = StudyRunner(
            client = client,
            quizHandler = handler,
            listener = ::onEvent,
        )

        scope = CoroutineScope(SupervisorJob())
        running = scope.launch {
            val result = runner.runCourse(course)
            // 用户中途点了停止：不要再把「已取消」当成错误盖到界面上
            if (!isActive) return@launch
            when (result) {
                is Cx.NeedLogin -> _state.update {
                    it.copy(running = false, needLogin = true, error = "登录态已失效，请重新登录")
                }
                is Cx.Fail -> {
                    _state.update { it.copy(running = false, error = result.detail) }
                    if (result.kind == Cx.Kind.VERIFY) pendingResume = course
                }
                is Cx.Ok -> _state.update {
                    it.copy(running = false, finished = true)
                }
            }
            StudyService.stop(context)
            StudyService.releaseWakeLock()
        }
    }

    fun stop() {
        running?.cancel()
        running = null
        studyScopeCleanup()
        StudyService.stop(context)
        StudyService.releaseWakeLock()
        _state.update { it.copy(running = false, jobTitle = "已手动停止") }
    }

    /** 进程退出时调用，避免协程和 WakeLock 泄漏 */
    fun shutdown() {
        running?.cancel()
        running = null
        pendingResume = null
        resumeScope.cancel()
        studyScopeCleanup()
        StudyService.releaseWakeLock()
    }

    private fun studyScopeCleanup() {
        runCatching { scope.cancel() }
    }

    private fun onEvent(event: StudyEvent) {
        val line = describe(event)
        _state.update { s ->
            s.copy(
                chapterIndex = when (event) {
                    is StudyEvent.ChapterStart -> event.index
                    else -> s.chapterIndex
                },
                chapterTotal = when (event) {
                    is StudyEvent.ChapterStart -> event.total
                    else -> s.chapterTotal
                },
                chapterTitle = when (event) {
                    is StudyEvent.ChapterStart -> event.title
                    is StudyEvent.Tree -> "共 ${event.tree.nodes.size} 章"
                    else -> s.chapterTitle
                },
                jobTitle = when (event) {
                    is StudyEvent.JobStart -> event.job.displayName
                    is StudyEvent.JobTick -> event.job.displayName
                    is StudyEvent.JobEnd -> event.job.displayName
                    else -> s.jobTitle
                },
                fraction = when (event) {
                    is StudyEvent.JobTick -> event.fraction
                    is StudyEvent.JobStart -> 0f
                    else -> s.fraction
                },
                done = when (event) {
                    is StudyEvent.JobEnd -> if (event.ok) s.done + 1 else s.done
                    is StudyEvent.Done -> event.done
                    else -> s.done
                },
                failed = when (event) {
                    is StudyEvent.JobEnd -> if (event.ok) s.failed else s.failed + 1
                    is StudyEvent.Done -> event.failed
                    else -> s.failed
                },
                skipped = when (event) {
                    is StudyEvent.Done -> event.skipped
                    else -> s.skipped
                },
                logs = if (line == null) s.logs else StudyLogs.push(s.logs, s.courseKey, line),
            )
        }
        if (line != null) StudyService.update(context, line)
    }

    private fun describe(event: StudyEvent): String? = when (event) {
        is StudyEvent.Tree -> "发现 ${event.tree.nodes.size} 个章节，共 ${event.tree.totalJobs} 个任务点"
        is StudyEvent.ChapterStart -> "▶ ${event.title}"
        is StudyEvent.JobStart -> "开始 ${event.job.displayName}"
        is StudyEvent.JobTick -> null // 每轮都刷日志会把界面刷爆，只走通知
        is StudyEvent.JobEnd -> if (event.ok) "✓ ${event.job.displayName}" else "✗ ${event.job.displayName}：${event.note}"
        is StudyEvent.QuizReady -> "取到测验卷面：${event.job.displayName}"
        is StudyEvent.Done -> "课程结束：成功 ${event.done} / 失败 ${event.failed} / 跳过 ${event.skipped}"
        is StudyEvent.Failed -> "出错：${event.message}"
    }

}
