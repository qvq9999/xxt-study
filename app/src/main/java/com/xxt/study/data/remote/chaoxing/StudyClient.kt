package com.xxt.study.data.remote.chaoxing

import com.xxt.study.core.net.Http
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.CardDefaults
import com.xxt.study.domain.ChapterJobs
import com.xxt.study.domain.ChapterNode
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseTree
import com.xxt.study.domain.Job
import com.xxt.study.domain.JobKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** 播放信息：dtoken 是进度上报 URL 的一部分，duration 是视频总秒数 */
data class PlayInfo(val dtoken: String, val duration: Long)

/**
 * 刷课协议客户端。
 *
 * 全部走 [Http] 同一串口（300ms 间隔 + 串行），不并发——
 * 刷课本来就是长时间的串行任务，抢并发只会更快触发风控。
 *
 * 接口清单与 SuperAutoStudy 的 CourseUtil 一一对应，改地址时两边一起改。
 */
class StudyClient(private val http: Http) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 视频上报签名要用 userid，缺了整条链路跑不起来 */
    fun uid(): String? = http.cookies.value("_uid")

    /** 机构 id，查播放信息用 */
    fun fid(): String? = http.cookies.value("fid")

    // ---------------- 章节与任务点 ----------------

    suspend fun chapters(course: Course): Cx<CourseTree> = io {
        val url = "$STUDENT_COURSE?courseid=${course.courseId}&clazzid=${course.classId}" +
            "&cpi=${course.cpi}&ut=s"
        val t = http.get(url, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")
        Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@io it }
        Parsers.detectFailure(t.body)?.let {
            return@io Cx.Fail(Cx.Kind.DENIED, "章节目录被拒：$it")
        }
        val tree = StudyParsers.parseCourseTree(t.body)
        if (tree.nodes.isEmpty()) {
            return@io Cx.Fail(Cx.Kind.PARSE, "章节目录为空：课程可能无内容，或页面结构已改版")
        }
        Cx.Ok(tree)
    }

    /**
     * 抓一个知识点的任务点。
     *
     * 必须打三次（num=0/1/2）：一个知识点下可能挂多屏卡片，
     * 每屏的 attachments 只包含自己那部分，只打一次会漏任务点。
     */
    suspend fun jobs(course: Course, node: ChapterNode): Cx<ChapterJobs> = io {
        val merged = LinkedHashMap<String, Job>()
        var defaults = CardDefaults()
        var notOpen = false
        var any = false

        for (num in 0..2) {
            val url = "$CARDS?clazzid=${course.classId}&courseid=${course.courseId}" +
                "&knowledgeid=${node.id}&num=$num&ut=s&cpi=${course.cpi}&v=20160407-3&mooc2=1"
            val t = http.get(url, referer = BASE)
            if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
            if (t.code != 200) continue
            any = true

            val part = StudyParsers.parseJobs(t.body)
            if (part.notOpen) {
                notOpen = true
                break
            }
            if (defaults.knowledgeId.isBlank()) defaults = defaults.fillFrom(part.defaults)
            for (job in part.jobs) merged.putIfAbsent(job.jobId, job)
        }

        if (!any) return@io Cx.Fail(Cx.Kind.NETWORK, "任务点页面三次请求都失败")
        Cx.Ok(ChapterJobs(merged.values.toList(), defaults, notOpen))
    }

    // ---------------- 视频 / 音频 ----------------

    /**
     * 查播放信息。
     *
     * 返回 `status != success` 通常是资源没转码好，或者这节课不属于当前账号，
     * 这种情况直接跳过，不要重试——重试一百次也是同一个结果。
     */
    suspend fun playInfo(objectId: String, fid: String): Cx<PlayInfo> = io {
        val url = "$STATUS/$objectId?k=$fid&flag=normal"
        val t = http.get(url, referer = VIDEO_REFERER)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")

        val root = runCatching { json.parseToJsonElement(t.body).jsonObject }.getOrNull()
            ?: return@io Cx.Fail(Cx.Kind.PARSE, "播放信息不是 JSON")
        val status = root.s("status")
        if (status != "success") {
            return@io Cx.Fail(Cx.Kind.DENIED, "播放信息状态为 ${status.ifBlank { "空" }}，资源可能未转码")
        }
        val dtoken = root.s("dtoken")
        val duration = root.s("duration").toDoubleOrNull()?.toLong() ?: 0L
        if (dtoken.isBlank() || duration <= 0) {
            return@io Cx.Fail(Cx.Kind.PARSE, "播放信息缺 dtoken 或时长")
        }
        Cx.Ok(PlayInfo(dtoken, duration))
    }

    /**
     * 上报一次进度。
     *
     * 返回 true 表示这一条已经看满（playingTime >= duration），可以收工。
     * rt 参数先报 0.9 再报 1，是原项目实测出来的降级顺序：
     * 部分课程只认其中一个，403 就换下一个，两次都 403 才算真失败。
     */
    suspend fun reportTick(
        course: Course,
        cpi: String,
        job: Job,
        play: PlayInfo,
        uid: String,
        playingTime: Long,
        dtype: String,
    ): Cx<Boolean> = io {
        var lastCode = 0
        for (rt in RT_CANDIDATES) {
            val url = VideoSign.logUrl(
                cpi = cpi,
                dtoken = play.dtoken,
                clazzId = course.classId,
                courseId = course.courseId,
                uid = uid,
                jobId = job.jobId,
                objectId = job.objectId,
                otherInfo = job.otherInfo,
                playingTime = playingTime,
                duration = play.duration,
                dtype = dtype,
                rt = rt,
                timestamp = System.currentTimeMillis(),
            )
            val t = http.get(url, referer = VIDEO_REFERER)
            if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
            lastCode = t.code
            if (t.code == 200) {
                // 风控会拿 200 + 验证页替换掉正常响应，不看内容就会当成"上报成功"
                Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@io it }
                return@io Cx.Ok(playingTime >= play.duration)
            }
            if (t.code == 403) continue
            return@io Cx.Fail(Cx.Kind.DENIED, "进度上报被拒 HTTP ${t.code}")
        }
        Cx.Fail(Cx.Kind.DENIED, "进度上报连续 403（rt 两种取值都被拒），任务点可能已锁定")
    }

    // ---------------- 文档 / 阅读 ----------------

    suspend fun markDocument(course: Course, job: Job, knowledgeId: String): Cx<String> = io {
        val url = "$DOC_JOB?jobid=${job.jobId}&knowledgeid=$knowledgeId" +
            "&courseid=${course.courseId}&clazzid=${course.classId}" +
            "&jtoken=${job.jtoken}&_dc=${System.currentTimeMillis()}"
        val t = http.get(url, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.DENIED, "文档任务点被拒 HTTP ${t.code}")
        Cx.Ok(t.body.take(200))
    }

    suspend fun markRead(course: Course, job: Job, knowledgeId: String): Cx<String> = io {
        val url = "$READ_JOB?jobid=${job.jobId}&knowledgeid=$knowledgeId" +
            "&jtoken=${job.jtoken}&courseid=${course.courseId}&clazzid=${course.classId}"
        val t = http.get(url, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.DENIED, "阅读任务点被拒 HTTP ${t.code}")
        Cx.Ok(t.body.take(200))
    }

    // ---------------- 章节测验 ----------------

    /**
     * 章节测验的作答页。
     *
     * 请求参数照抄 StudyWork：workId 要去掉 `work-` 前缀，jtoken/ktoken/cpi 一个都不能少，
     * 否则服务端回「参数验证不通过」。
     */
    suspend fun quizPage(course: Course, job: Job, defaults: CardDefaults): Cx<String> = io {
        val workId = job.jobId.removePrefix("work-")
        val url = "$WORK_API?api=1&workId=$workId&jobid=${job.jobId}" +
            "&originJobId=${job.jobId}&needRedirect=true&skipHeader=true" +
            "&knowledgeid=${defaults.knowledgeId}&ktoken=${defaults.ktoken}" +
            "&cpi=${course.cpi.ifBlank { defaults.cpi }}&ut=s" +
            "&clazzId=${course.classId}&enc=${job.enc}&enc2=${job.enc}"
        val t = http.get(url, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@io it }
        Parsers.detectFailure(t.body)?.let {
            return@io Cx.Fail(Cx.Kind.DENIED, "测验页面被拒：$it")
        }
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")
        if (t.body.isBlank()) return@io Cx.Fail(Cx.Kind.PARSE, "测验页面为空")
        Cx.Ok(t.body)
    }

    /**
     * 提交章节测验。
     *
     * 服务端吃 form-urlencoded，字段 = 卷面隐藏域 + 每题 `answer<qid>` 与
     * `answertype<qid>` + 一个 `answerwqbid`（所有题号逗号连接、末尾多一个逗号）。
     * 末尾那个逗号是服务端要求的格式，别顺手删。
     */
    suspend fun submitQuiz(page: QuizPage, answers: Map<String, String>): Cx<String> = io {
        val fields = LinkedHashMap(page.form)
        for (q in page.questions) {
            fields["answer${q.qid}"] = answers[q.qid].orEmpty()
            fields["answertype${q.qid}"] = QuizParser.typeCodeOf(q.type)
        }
        fields["answerwqbid"] = page.questions.joinToString("") { "${it.qid}," }

        val t = http.postForm(SUBMIT_WORK, fields, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.DENIED, "提交被拒 HTTP ${t.code}")
        val ok = t.body.contains("\"status\":true") || t.body.contains("success", ignoreCase = true)
        if (!ok) return@io Cx.Fail(Cx.Kind.DENIED, "提交未成功：${t.body.take(160)}")
        Cx.Ok(t.body.take(200))
    }

    // ---------------- 内部 ----------------

    /** 只补空字段，已有的不覆盖——三次 num 抓回来的 defaults 以先到的为准 */
    private fun CardDefaults.fillFrom(other: CardDefaults): CardDefaults = CardDefaults(
        knowledgeId = knowledgeId.ifBlank { other.knowledgeId },
        cpi = cpi.ifBlank { other.cpi },
        ktoken = ktoken.ifBlank { other.ktoken },
        cardId = cardId.ifBlank { other.cardId },
        defenc = defenc.ifBlank { other.defenc },
        mtEnc = mtEnc.ifBlank { other.mtEnc },
        qnenc = qnenc.ifBlank { other.qnenc },
        reportInterval = if (reportInterval > 0) reportInterval else other.reportInterval,
    )

    private fun JsonObject.s(key: String): String =
        runCatching { this[key]!!.jsonPrimitive.content.trim() }.getOrDefault("")

    private suspend fun <T> io(block: suspend () -> Cx<T>): Cx<T> = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IOException) {
        Cx.Fail(Cx.Kind.NETWORK, e.message ?: "网络连接失败")
    } catch (e: Exception) {
        Cx.Fail(Cx.Kind.UNKNOWN, e.message ?: e::class.java.simpleName)
    }

    private companion object {
        const val BASE = "https://i.chaoxing.com/base"
        const val STUDENT_COURSE = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse"
        const val CARDS = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards"
        const val STATUS = "https://mooc1.chaoxing.com/ananas/status"
        const val DOC_JOB = "https://mooc1.chaoxing.com/ananas/job/document"
        const val READ_JOB = "https://mooc1.chaoxing.com/ananas/job/readv2"
        const val WORK_API = "https://mooc1.chaoxing.com/mooc-ans/api/work"
        const val SUBMIT_WORK = "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb"

        const val VIDEO_REFERER =
            "https://mooc1.chaoxing.com/ananas/modules/video/index.html?v=2023-1110-1610"

        val RT_CANDIDATES = listOf("0.9", "1")
    }
}

/** 任务点是否需要真正"做"，交给上层判断用 */
fun Job.isPlayable(): Boolean = when (kind) {
    JobKind.VIDEO, JobKind.AUDIO -> objectId.isNotBlank()
    JobKind.DOCUMENT, JobKind.READ -> jtoken.isNotBlank()
    JobKind.QUIZ -> jobId.isNotBlank()
    JobKind.UNKNOWN -> false
}
