package com.xxt.study.data.remote.chaoxing

import com.xxt.study.domain.Course
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkDetail
import com.xxt.study.domain.WorkStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 学习通页面解析器集合。
 *
 * 全部是纯函数：输入字符串，输出数据类，不碰 Android，不碰网络。
 * 这样平台改版时只改这里，并且可以用真实 HTML 片段做单测快速定位。
 *
 * 解析规则与 tools/probe-homework.mjs 保持一致——探针里验证过的 fixture
 * 直接搬成这里的单测用例，两边不许各写一套。
 */
object Parsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 页面里所有带 id 与 value 的隐藏 input，例如 enc / openc / workEnc */
    fun parseHiddenInputs(html: String): Map<String, String> {
        if (html.isBlank()) return emptyMap()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (el in doc.select("input[id][value]")) {
            val id = el.attr("id").trim()
            val v = el.attr("value").trim()
            if (id.isNotEmpty() && v.isNotEmpty()) out[id] = v
        }
        return out
    }

    /** 会话是否失效：被重定向到登录页，或页面里出现登录提示词 */
    fun isLoginRedirect(finalUrl: String, body: String = ""): Boolean {
        if (finalUrl.contains("passport2.chaoxing.com/login")) return true
        if (finalUrl.contains("/passport/")) return true
        return body.contains("请先登录") || body.contains("未登录")
    }

    /**
     * 服务端拒绝时返回命中的关键词，正常页返回 null。
     * 顺序上把更具体的放前面，便于给出准确原因。
     */
    fun detectFailure(html: String): String? {
        if (html.isBlank()) return null
        for (w in FAIL_WORDS) if (html.contains(w)) return w
        return null
    }

    /**
     * 是不是人机验证 / 风控页。
     *
     * 关键词都取"只会在验证页出现"的整句，不用单独的"验证码"——
     * 登录页有"验证码登录"，误判会把用户莫名其妙拦在验证窗口里。
     */
    fun looksLikeVerify(html: String): Boolean {
        if (html.isBlank()) return false
        return VERIFY_RE.containsMatchIn(html.take(30_000))
    }

    /**
     * 是风控页就"举手"并给出失败结果。
     *
     * 统一在这里做，是因为触发点太多（课程列表、作业详情、章节目录、测验页、视频上报）：
     * 每个调用点自己拼一遍，早晚会漏一个。
     */
    fun verifyOrNull(html: String, pageUrl: String): com.xxt.study.core.result.Cx.Fail? {
        if (!looksLikeVerify(html)) return null
        com.xxt.study.core.net.VerifyGate.request(pageUrl)
        return com.xxt.study.core.result.Cx.Fail(
            com.xxt.study.core.result.Cx.Kind.VERIFY,
            "学习通触发了风控，需要你手动完成验证后再继续",
        )
    }

    /**
     * 风控页关键词。
     *
     * 用正则而不是整句匹配，是被真实页面教过一次：学习通给的是
     * 「操作异常，请输入图片中的验证码」，而旧词表里只有「请输入验证码」——
     * 中间多了"图片中的"四个字就整条失效，验证窗口永远弹不出来。
     * 所以允许"请输入/请填写" 和 "验证码" 之间插几个字。
     *
     * 同时避开误伤：登录页有「验证码登录」，课程页可能有「验证码技术」，
     * 这些都不带"请输入/请填写"前缀，不会命中。
     *
     * ⚠️ 改这里要同步改 `ScanProbes.VERIFY_CHECK`（那个跑在 WebView 里）。
     */
    private val VERIFY_RE = Regex(
        "请(?:输入|填写).{0,8}验证码" +
            "|拖动.{0,6}滑块" +
            "|滑动验证" +
            "|人机验证" +
            "|安全验证" +
            "|完成验证" +
            "|验证码(?:错误|不正确|已失效|已过期)" +
            "|captcha",
        RegexOption.IGNORE_CASE,
    )

    private val FAIL_WORDS = listOf(
        "您没有权限", "没有权限", "无权限",
        "参数验证不通过", "无效的参数",
        "请先登录", "未登录",
    )

    // ---------------- 课程列表 ----------------

    /**
     * 主源：backclazzdata 的 JSON。
     *
     * 真实结构（样本 `.workbuddy/research/samples/02-courselist-json.html`）：
     *
     *   channelList[].content
     *     id         → classId（**不叫 classid**）
     *     cpi        → cpi
     *     name       → 班级名，如「中华民族共同体概论-8」
     *     course.data[0]
     *       id       → courseId（**不叫 courseid**）
     *       name     → 课程名，如「中华民族共同体概论」
     *
     * 旧实现只在同一层找 `courseid` + `classid`，这一层根本没有这两个字段名，
     * 所以要么解析不出课程、要么解析出来全是空名字（界面上就是「未命名课程」）。
     *
     * 先走精确路径；拿不到再退回旧的递归兜底（兼容老结构和机构定制页面）。
     */
    fun parseCourses(jsonText: String): List<Course> {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()

        val exact = parseChannels(root)
        if (exact.isNotEmpty()) return exact

        val out = LinkedHashMap<String, Course>()
        collectCourses(root, out, 0)
        return out.values.toList()
    }

    private fun parseChannels(root: JsonElement): List<Course> {
        val channels = (root as? JsonObject)?.get("channelList") as? JsonArray ?: return emptyList()
        val out = LinkedHashMap<String, Course>()
        for (el in channels) {
            val channel = el as? JsonObject ?: continue
            val content = channel["content"] as? JsonObject ?: continue

            val classId = content.str("id") ?: content.str("clazzid", "classId") ?: continue
            val cpi = content.str("cpi") ?: channel.str("cpi").orEmpty()

            val dataArray = (content["course"] as? JsonObject)?.get("data") as? JsonArray
            for (item in dataArray ?: JsonArray(emptyList())) {
                val courseObj = item as? JsonObject ?: continue
                val courseId = courseObj.str("id") ?: courseObj.str("courseid", "courseId") ?: continue
                // 课程名优先取 course.data[].name（干净），再次才是 content.name（班级名带后缀）
                val name = courseObj.str("name", "title", "courseName")
                    ?: content.str("name")
                    ?: ""
                val key = "$courseId:$classId"
                if (key !in out) {
                    out[key] = Course(courseId, classId, cpi, cleanName(name))
                }
            }
        }
        return out.values.toList()
    }

    /** 名字里偶尔带 HTML 实体或首尾空白，去掉，避免界面上看着像空 */
    private fun cleanName(raw: String): String = raw
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun collectCourses(el: JsonElement, out: MutableMap<String, Course>, depth: Int) {
        if (depth > 10) return
        when (el) {
            is JsonArray -> el.forEach { collectCourses(it, out, depth + 1) }
            is JsonObject -> {
                val courseId = el.str("courseid", "courseId")
                val classId = el.str("clazzid", "classId")
                if (courseId != null && classId != null) {
                    val name = el.str("name", "title", "courseName", "coursename").orEmpty()
                    val cpi = el.str("cpi").orEmpty()
                    val key = "$courseId:$classId"
                    if (key !in out) out[key] = Course(courseId, classId, cpi, name)
                    return
                }
                el.values.forEach { collectCourses(it, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val s = v.content.trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    /** 兜底源：courselistdata 的 HTML，从链接里抄四要素 */
    fun parseCoursesFromHtml(html: String): List<Course> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, Course>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!href.contains("courseId=", true) && !href.contains("courseid=", true)) continue
            val q = href.substringAfter('?', "")
            val params = q.split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).lowercase() to it.substring(i + 1)
            }.toMap()
            val courseId = params["courseid"] ?: continue
            val classId = params["clazzid"] ?: continue
            val key = "$courseId:$classId"
            val cpi = params["cpi"].orEmpty()

            // 课程页里同一个 courseId 会出现多个 a：封面图、进入按钮、标题链接。
            // 图片链接既没有 title 也没有文字，如果按"先到先得"记住它，
            // 这门课的名字就永远是空的（界面上的「未命名课程」就是这么来的）。
            // 所以规则改成：先到的占位，后来者只要有内容就补上。
            val name = cleanName(a.attr("title").ifBlank { a.text() })
            val old = out[key]
            out[key] = when {
                old == null -> Course(courseId, classId, cpi, name)
                else -> old.copy(
                    name = old.name.ifBlank { name },
                    cpi = old.cpi.ifBlank { cpi },
                )
            }
        }
        return out.values.toList()
    }

    // ---------------- 作业列表 ----------------

    /**
     * 作业行：<li onclick="goTask(this);" data="<详情直链>" aria-label="标题 ; 状态">
     * data 必须含 chaoxing.com，否则是干扰行（分页、空态提示等）。
     */
    fun parseWorkRows(html: String): List<Work> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val rows = ArrayList<Work>()
        for (li in doc.select("li[data]")) {
            val data = li.attr("data").trim()
            if (!data.contains("chaoxing.com")) continue
            val label = li.attr("aria-label").trim()
            rows += workFrom(label, data) ?: continue
        }
        return rows
    }

    /**
     * 由「aria-label + 详情直链」造一条作业。
     *
     * HTML 路径与 WebView 路径共用这一个函数，保证两条路的解析规则不会分叉。
     * WebView 那边直接返回 JSON 行，不传 HTML 回来，所以必须能单独调。
     */
    fun workFrom(label: String, url: String, now: Long = System.currentTimeMillis()): Work? {
        val data = url.trim()
        if (!data.contains("chaoxing.com")) return null
        val params = queryOf(data)
        val title = label.substringBefore(';').substringBefore('；').trim()
        return Work(
            workId = params["workid"].orEmpty(),
            answerId = params["answerid"].orEmpty(),
            enc = params["enc"].orEmpty(),
            title = title.ifBlank { data.substringAfterLast('/').take(24) },
            rawLabel = label.trim(),
            status = normalizeStatus(label),
            detailUrl = data,
            deadline = parseDeadline(label, now),
        )
    }

    /** aria-label 里的剩余时间：「未交 剩余394小时23分钟」「剩余 2 天 3 小时」都能吃 */
    private val REMAIN = Regex(
        "剩余\\s*(?:(\\d+)\\s*天)?\\s*(?:(\\d+)\\s*(?:小时|时))?\\s*(?:(\\d+)\\s*分)?",
    )

    /**
     * 把「剩余394小时23分钟」换算成绝对截止时间。
     *
     * 注意这是**服务端渲染那一刻**的剩余量，我们扫描时已经过去几秒到几十秒，
     * 误差在这个量级可以接受；换成"存剩余分钟数"反而会随缓存时间漂移得更厉害。
     */
    fun parseDeadline(label: String, now: Long): Long? {
        if (label.isBlank()) return null
        val m = REMAIN.find(label) ?: return null
        val days = m.groupValues[1].toLongOrNull() ?: 0L
        val hours = m.groupValues[2].toLongOrNull() ?: 0L
        val minutes = m.groupValues[3].toLongOrNull() ?: 0L
        val millis = ((days * 24 + hours) * 60 + minutes) * 60_000L
        return if (millis > 0) now + millis else null
    }

    private fun queryOf(url: String): Map<String, String> {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).lowercase() to it.substring(i + 1)
        }.toMap()
    }

    /**
     * 状态归一。顺序有意为之：先判未交，再判已交，最后判已批阅。
     * 学习通对过期未交的作业仍显示"未交"，所以 PENDING 优先命中，
     * 这样首页数字不会把"过期没交"漏掉。
     */
    fun normalizeStatus(label: String): WorkStatus {
        if (label.isBlank()) return WorkStatus.UNKNOWN
        return when {
            label.contains("未交") || label.contains("未提交") || label.contains("未开始") -> WorkStatus.PENDING
            label.contains("已过期") || label.contains("已截止") -> WorkStatus.OVERDUE
            label.contains("待批阅") || label.contains("已提交") || label.contains("已交") || label.contains("待评") -> WorkStatus.SUBMITTED
            label.contains("已完成") || label.contains("已批阅") || label.contains("已评分") -> WorkStatus.GRADED
            else -> WorkStatus.UNKNOWN
        }
    }

    /** 判断这份响应是"结构变了"还是"确实没作业" */
    fun looksLikeChangedStructure(html: String): Boolean =
        html.contains("<li", ignoreCase = true) && !html.contains("chaoxing.com")

    // ---------------- 作业详情 ----------------

    /**
     * 学习通不给年份，时间形如「作答时间: 10-08 08:00 至 10-15 23:59」。
     * 也兼容带年份的写法。
     */
    fun parseWorkDetail(html: String): WorkDetail {
        if (html.isBlank()) return WorkDetail()
        val text = runCatching { Jsoup.parse(html).text() }.getOrElse {
            html.replace(Regex("<[^>]+>"), " ")
        }
        val timeRange = Regex("作答时间[:：]?\\s*($DT\\s*至\\s*$DT)").find(text)?.groupValues?.get(1)
        return WorkDetail(
            questionCount = Regex("([0-9]+)\\s*道题").find(text)?.groupValues?.get(1),
            fullScore = Regex("满分[:：]?\\s*([0-9.]+)").find(text)?.groupValues?.get(1),
            score = Regex("智能分析\\s*([0-9.]+)\\s*分").find(text)?.groupValues?.get(1),
            timeRange = timeRange,
            endAt = timeRange?.substringAfter('至')?.trim(),
        )
    }

    private const val DT = "[0-9]{2,4}[-/][0-9]{1,2}(?:[-/][0-9]{1,2})?\\s*[0-9]{1,2}:[0-9]{2}"

    /** 从课程列表页里找 fid（base 页 JS 变量），兜底用 */
    fun parseFid(html: String): String? =
        Regex("[\"']?fid[\"']?\\s*[:=]\\s*[\"']?(\\d{3,})").find(html)?.groupValues?.get(1)

    /** 页面上的学校名，用于给用户确认登录的是哪个学校 */
    fun parseSiteName(html: String): String? =
        runCatching {
            Jsoup.parse(html).selectFirst("#siteName")?.attr("title")?.trim()?.ifBlank { null }
        }.getOrNull()

    /** 页面上的账号（脱敏前），用于确认登录态所属账号 */
    fun parseAccount(html: String): String? =
        Regex("aria-label=\"账号[:：]([^\"]*)\"").find(html)?.groupValues?.get(1)?.trim()

    /** 便于在调试面板里展示元素数量，判断解析是否命中 */
    fun countElements(html: String, selector: String): Int =
        runCatching { Jsoup.parse(html).select(selector).size }.getOrDefault(0)

    @Suppress("unused")
    private fun Element.debugLabel(): String = "${tagName()}#${id()}.${className()}"
}
