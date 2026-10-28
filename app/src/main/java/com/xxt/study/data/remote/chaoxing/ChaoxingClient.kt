package com.xxt.study.data.remote.chaoxing

import com.xxt.study.core.net.Http
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Course
import com.xxt.study.domain.Session
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 学习通接口客户端。
 *
 * 关键事实（来自真实环境实测，见 docs/作业获取方案-制作步骤.md）：
 * - 作业列表的 enc= 必须用中间页的 workEnc，不是页面通用 enc
 * - stuenc= 才用页面通用 enc
 * - 还要带 openc / t / isdisplaytable，否则可能"参数验证不通过"
 * - 这些参数是会话绑定的，所以 enc 包不缓存太久，遇拒绝就重取
 */
class ChaoxingClient(private val http: Http) {

    /**
     * 上一次 [courses] 是否走了 HTML 兜底。
     *
     * 兜底源的课程名和 cpi 都可能不完整（直接导致后续「课程页没有 workEnc」），
     * 所以在 UI 上要明说，而不是让用户猜为什么有的课点不开。
     * 串行调用，用一个字段记着就够，不值得为它改一整条返回值类型。
     */
    @Volatile
    var lastCoursesDegraded: Boolean = false
        private set

    suspend fun checkSession(): Cx<Session> = io {
        val t = http.get(BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        if (t.code != 200) return@io Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")

        val account = Parsers.parseAccount(t.body)
        val school = Parsers.parseSiteName(t.body)
        val fid = Parsers.parseFid(t.body)
        // 三个字段一个都解析不出来，不再当作"登录失败"。
        // 原因：不同登录入口落到的首个页面结构差异很大（手机版 / 网页版 / 机构定制页），
        // 在这里判死会让"其实已经登录成功"的账号卡在登录页出不来。
        // 真正的真伪由后续课程接口暴露——那一步拿不到课，UI 会明确报错。
        Cx.Ok(Session(account = account.orEmpty(), school = school.orEmpty(), fid = fid.orEmpty()))
    }

    suspend fun courses(fid: String): Cx<List<Course>> = io {
        // 主源：JSON。失败重试一次——实测它偶发返回空壳（短时间内第二次请求尤其容易），
        // 一次就降级到 HTML 会让整次扫描的课程名和 cpi 都不可靠。
        lastCoursesDegraded = false
        var fromJson = emptyList<Course>()
        var lastCode = 0
        for (attempt in 0..1) {
            if (attempt > 0) kotlinx.coroutines.delay(600)
            val json = http.get(COURSES_JSON, referer = BASE)
            if (Parsers.isLoginRedirect(json.finalUrl, json.body)) return@io Cx.NeedLogin
            lastCode = json.code
            // 风控会把 JSON 接口也替换成验证页，返回的是 HTML 而不是空数组，
            // 直接当"解析不到"处理会白跑一次重试，还看不出原因
            Parsers.verifyOrNull(json.body, json.finalUrl)?.let { return@io it }
            fromJson = if (json.code == 200) Parsers.parseCourses(json.body) else emptyList()
            if (fromJson.isNotEmpty()) return@io Cx.Ok(fromJson)
        }

        // 兜底：HTML 源
        if (fid.isBlank()) {
            return@io Cx.Fail(
                Cx.Kind.PARSE,
                "JSON 源两次都没解析出课程（HTTP $lastCode），且缺少 fid 无法走 HTML 兜底",
            )
        }
        val html = http.get(COURSES_HTML.format(fid), referer = BASE)
        if (Parsers.isLoginRedirect(html.finalUrl, html.body)) return@io Cx.NeedLogin
        Parsers.verifyOrNull(html.body, html.finalUrl)?.let { return@io it }
        Parsers.detectFailure(html.body)?.let {
            return@io Cx.Fail(Cx.Kind.DENIED, "课程列表 HTML 源被拒：$it")
        }
        val fromHtml = Parsers.parseCoursesFromHtml(html.body)
        if (fromHtml.isEmpty()) {
            return@io Cx.Fail(Cx.Kind.PARSE, "两个课程源都没解析出课程（JSON HTTP $lastCode）")
        }
        lastCoursesDegraded = true
        // 降级路径要知道自己降级了：课程名和 cpi 都可能不完整，
        // 直接导致后续「课程页没有 workEnc」。把这句话带上去给用户看。
        Cx.Ok(fromHtml.map { it.copy(name = it.name.ifBlank { "未命名课程（$it.courseId）" }) })
    }

    suspend fun detail(work: Work): Cx<WorkDetail> = io {
        val t = http.get(work.detailUrl, referer = BASE)
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@io Cx.NeedLogin
        Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@io it }
        Parsers.detectFailure(t.body)?.let {
            return@io Cx.Fail(Cx.Kind.DENIED, "作业详情被拒：$it")
        }
        Cx.Ok(Parsers.parseWorkDetail(t.body))
    }

    // ---------------- 内部 ----------------

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
        const val COURSES_JSON = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1"
        const val COURSES_HTML = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata?courseType=1&courseFid=%s"
    }
}
