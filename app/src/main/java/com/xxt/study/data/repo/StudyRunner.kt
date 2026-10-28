package com.xxt.study.data.repo

import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.PlayInfo
import com.xxt.study.data.remote.chaoxing.StudyClient
import com.xxt.study.data.remote.chaoxing.VideoSign
import com.xxt.study.data.remote.chaoxing.isPlayable
import com.xxt.study.domain.CardDefaults
import com.xxt.study.domain.ChapterJobs
import com.xxt.study.domain.ChapterNode
import com.xxt.study.core.study.StudyLimits
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseTree
import com.xxt.study.domain.Job
import com.xxt.study.domain.JobKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/** 刷课过程中的事件，UI 和前台通知都吃这一份 */
sealed interface StudyEvent {
    data class Tree(val course: Course, val tree: CourseTree) : StudyEvent
    data class ChapterStart(val index: Int, val total: Int, val title: String) : StudyEvent
    data class JobStart(val job: Job) : StudyEvent
    /** fraction 0..1，仅视频/音频有值 */
    data class JobTick(val job: Job, val fraction: Float, val note: String) : StudyEvent
    data class JobEnd(val job: Job, val ok: Boolean, val note: String) : StudyEvent
    data class QuizReady(val job: Job, val html: String) : StudyEvent
    data class Done(val done: Int, val failed: Int, val skipped: Int) : StudyEvent
    data class Failed(val message: String) : StudyEvent
}

data class RunSummary(val done: Int, val failed: Int, val skipped: Int)

/**
 * 刷课执行器。
 *
 * 只负责「按协议把任务点一个个走完」，不碰 UI、不碰持久化。
 * 这样它既能在前台服务里跑（长期任务），也能在单测里被假 client 驱动。
 *
 * 三个刻意的取舍：
 * 1. **一次只走一个任务点**，不并发。刷课本来就是长任务，并发只会更快触发风控。
 * 2. **视频用 delay(60s) 而不是 sleep**，这样用户点停止能立刻中断。
 * 3. **单个任务点失败不中断整门课**，记一笔继续；只有会话失效才整体停。
 */
class StudyRunner(
    private val client: StudyClient,
    /** 上报倍速，固定为平台允许的最高值，用户不可调 */
    private val rate: Double = StudyLimits.PLATFORM_MAX_RATE,
    /** 一轮上报之间真实等待多久；服务端 reportTimeInterval 一般 58~60 秒 */
    private val tickMillis: Long = 60_000L,
    /** 章节测验的处理方式；为 null 时只把卷面递出去，不自动作答 */
    private val quizHandler: QuizHandler? = null,
    private val listener: (StudyEvent) -> Unit = {},
) {

    suspend fun runCourse(course: Course): Cx<RunSummary> {
        val uid = client.uid()
            ?: return Cx.Fail(Cx.Kind.DENIED, "缺少 _uid，登录态不完整，请重新登录")
        val fid = client.fid()
            ?: return Cx.Fail(Cx.Kind.DENIED, "缺少 fid（机构 id），请重新登录后再试")

        val tree = when (val r = client.chapters(course)) {
            is Cx.Ok -> r.data
            is Cx.Fail -> return r
            Cx.NeedLogin -> return Cx.NeedLogin
        }
        listener(StudyEvent.Tree(course, tree))

        var done = 0
        var failed = 0
        var skipped = 0

        tree.nodes.forEachIndexed { index, node ->
            if (!currentCoroutineContext().isActive) return Cx.Fail(Cx.Kind.UNKNOWN, "已取消")
            // 已经撞上风控就别再往下打了：剩下的任务点只会一起失败，还加深风控
            if (VerifyGate.pending.value != null) {
                return Cx.Fail(Cx.Kind.VERIFY, "学习通触发了风控，需要你手动完成验证")
            }
            listener(StudyEvent.ChapterStart(index + 1, tree.nodes.size, node.title))

            if (node.locked) {
                skipped++
                return@forEachIndexed
            }

            val jobs = when (val r = client.jobs(course, node)) {
                is Cx.Ok -> r.data
                is Cx.Fail -> {
                    failed++
                    listener(StudyEvent.JobEnd(dummy(node), false, r.detail))
                    return@forEachIndexed
                }
                Cx.NeedLogin -> return Cx.NeedLogin
            }
            if (jobs.notOpen || jobs.jobs.isEmpty()) {
                if (jobs.notOpen) skipped++ else done++
                return@forEachIndexed
            }

            for (job in jobs.jobs) {
                if (!currentCoroutineContext().isActive) return Cx.Fail(Cx.Kind.UNKNOWN, "已取消")
                val ok = runJob(course, jobs, job, uid, fid)
                if (ok) done++ else failed++
                if (VerifyGate.pending.value != null) {
                    return Cx.Fail(Cx.Kind.VERIFY, "学习通触发了风控，需要你手动完成验证")
                }
            }
        }

        listener(StudyEvent.Done(done, failed, skipped))
        return Cx.Ok(RunSummary(done, failed, skipped))
    }

    /** 单个任务点；返回 true 表示这次算完成 */
    private suspend fun runJob(
        course: Course,
        jobs: ChapterJobs,
        job: Job,
        uid: String,
        fid: String,
    ): Boolean {
        listener(StudyEvent.JobStart(job))

        if (!job.isPlayable()) {
            listener(StudyEvent.JobEnd(job, false, "缺少必要参数（资源未转码或页面改版），已跳过"))
            return false
        }

        // cpi 优先用课程自带的，缺了用卡片 defaults 里的兜底
        val cpi = course.cpi.ifBlank { jobs.defaults.cpi }

        return when (job.kind) {
            JobKind.VIDEO -> runMedia(course, jobs.defaults, job, uid, fid, cpi, "Video")
            JobKind.AUDIO -> runMedia(course, jobs.defaults, job, uid, fid, cpi, "Audio")
            JobKind.DOCUMENT -> when (val r = client.markDocument(course, job, jobs.defaults.knowledgeId)) {
                is Cx.Ok -> {
                    listener(StudyEvent.JobEnd(job, true, "文档任务点已标记"))
                    true
                }
                is Cx.Fail -> {
                    listener(StudyEvent.JobEnd(job, false, r.detail))
                    false
                }
                Cx.NeedLogin -> false
            }
            JobKind.READ -> when (val r = client.markRead(course, job, jobs.defaults.knowledgeId)) {
                is Cx.Ok -> {
                    listener(StudyEvent.JobEnd(job, true, "阅读任务点已标记"))
                    true
                }
                is Cx.Fail -> {
                    listener(StudyEvent.JobEnd(job, false, r.detail))
                    false
                }
                Cx.NeedLogin -> false
            }
            JobKind.QUIZ -> when (val r = client.quizPage(course, job, jobs.defaults)) {
                is Cx.Ok -> {
                    // 卷面先递出去（UI 可以展示），有 handler 才继续作答
                    listener(StudyEvent.QuizReady(job, r.data))
                    val handler = quizHandler
                    if (handler == null) {
                        listener(StudyEvent.JobEnd(job, false, "已取到卷面，但未启用自动答题"))
                        false
                    } else {
                        when (
                            val s = handler.handle(course, job, r.data, jobs.defaults) { note ->
                                // 测验是全自动跑的，把每一步透出来，否则用户只看到"卡住了"
                                listener(StudyEvent.JobTick(job, 0f, note))
                            }
                        ) {
                            is Cx.Ok -> {
                                listener(StudyEvent.JobEnd(job, true, "测验已作答并提交"))
                                true
                            }
                            is Cx.Fail -> {
                                listener(StudyEvent.JobEnd(job, false, s.detail))
                                false
                            }
                            Cx.NeedLogin -> false
                        }
                    }
                }
                is Cx.Fail -> {
                    listener(StudyEvent.JobEnd(job, false, r.detail))
                    false
                }
                Cx.NeedLogin -> false
            }
            JobKind.UNKNOWN -> false
        }
    }

    /**
     * 视频/音频主循环。
     *
     * 节奏照抄原项目：先报一次 → 等 60 秒 → playingTime 涨 tick → 再报。
     * 服务端只认上报里自称的 playingTime，不校验真实观看，这是整个加速的前提。
     * 短视频（时长小于一个 tick）两轮就结束，不会白等。
     */
    private suspend fun runMedia(
        course: Course,
        defaults: CardDefaults,
        job: Job,
        uid: String,
        fid: String,
        cpi: String,
        dtype: String,
    ): Boolean {
        val play = when (val r = client.playInfo(job.objectId, fid)) {
            is Cx.Ok -> r.data
            is Cx.Fail -> {
                listener(StudyEvent.JobEnd(job, false, r.detail))
                return false
            }
            Cx.NeedLogin -> return false
        }
        listener(StudyEvent.JobTick(job, 0f, "时长 ${formatDuration(play.duration)}"))

        var playingTime = 0L
        var rounds = 0
        while (true) {
            if (!currentCoroutineContext().isActive) return false
            rounds++

            val t = client.reportTick(
                course = course,
                cpi = cpi,
                job = job,
                play = play,
                uid = uid,
                playingTime = playingTime,
                dtype = dtype,
            )
            when (t) {
                is Cx.Ok -> if (t.data) {
                    listener(StudyEvent.JobTick(job, 1f, "已完成"))
                    listener(StudyEvent.JobEnd(job, true, "看完 $rounds 轮"))
                    return true
                }
                is Cx.Fail -> {
                    listener(StudyEvent.JobEnd(job, false, t.detail))
                    return false
                }
                Cx.NeedLogin -> return false
            }

            val tick = VideoSign.tickSeconds(rate, StudyLimits.TICK_SECONDS)
            val next = playingTime + tick
            listener(
                StudyEvent.JobTick(
                    job = job,
                    fraction = (next.toFloat() / play.duration).coerceAtMost(1f),
                    note = "${formatDuration(next)} / ${formatDuration(play.duration)}",
                ),
            )

            // 已经到了终点：下一轮上报会拿到 true，这里补最小等待，别把服务端当傻子
            if (next >= play.duration) {
                playingTime = play.duration
                delay(minOf(tickMillis, 3_000L))
            } else {
                playingTime = next
                delay(tickMillis)
            }
            // 单任务点上限 6 小时，防止某些资源的 duration 异常导致整夜挂机
            if (rounds > 360) {
                listener(StudyEvent.JobEnd(job, false, "超过 360 轮仍未完成，疑似时长异常"))
                return false
            }
        }
    }

    /** 章节抓不到任务点时，给 UI 一个占位事件 */
    private fun dummy(node: ChapterNode) = Job(
        kind = JobKind.UNKNOWN,
        jobId = "",
        name = node.title,
    )

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
