package com.xxt.study.core.study

import android.content.Context
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.StudyClient
import com.xxt.study.data.repo.QuizHandler
import com.xxt.study.data.repo.StudyEvent
import com.xxt.study.data.repo.StudyRunner
import com.xxt.study.domain.Course
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
    /** 最近若干条事件，最新在最上面 */
    val log: List<String> = emptyList(),
    val needLogin: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
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

    fun start(course: Course) {
        if (running?.isActive == true) return

        _state.value = StudyUiState(running = true, courseName = course.name.ifBlank { "未命名课程" })
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
                is Cx.Fail -> _state.update {
                    it.copy(running = false, error = result.detail)
                }
                is Cx.Ok -> _state.update {
                    it.copy(running = false, finished = true, log = it.log)
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
                log = if (line == null) s.log else (listOf(line) + s.log).take(MAX_LOG),
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

    private companion object {
        const val MAX_LOG = 80
    }
}
