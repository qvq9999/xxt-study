package com.xxt.study.data.remote.chaoxing

import com.xxt.study.core.net.Http
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Activity
import com.xxt.study.domain.Course
import com.xxt.study.domain.PracticeOption
import com.xxt.study.domain.PracticeQuestion
import com.xxt.study.domain.PracticeSheet
import com.xxt.study.domain.activityTypeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 课堂活动客户端：随堂练习（activeType=42）的发现、取卷与提交。
 *
 * 接口来自实机抓包（HAR + 答题页内联脚本），无 enc 签名、只靠 Cookie：
 * - 列表 `GET /v2/apis/active/student/activelist?fid&courseId&classId&showNotStartedActive=0`
 * - 取卷 `GET /v2/apis/quiz/quizDetail2?activeId&moreClassAttendEnc=&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId`
 * - 提交 `POST /v2/apis/studentQuestion/doQuestionAnswering?activeId&courseId&classId&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId`
 *   body 是**整卷 questionlist**，只把每题 `personAnswer` 换掉；`result==1` 视为成功。
 *
 * 活动解析为什么是"递归找带 activeType 的对象"而不是定死路径：
 * 抓包只拿到了请求、没拿到 activelist 的响应体，`data` 下面是数组还是
 * `{activeList:[…]}` 无法确证。写死路径猜错就整页空白；递归扫描对两种结构都成立。
 */
class ActivityClient(private val http: Http) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun fid(): String? = http.cookies.value("fid")

    /** 这门课的活动列表。失败时带上原因，界面直接显示，不做静默降级 */
    suspend fun activities(course: Course): Cx<List<Activity>> = withContext(Dispatchers.IO) {
        val fid = fid() ?: return@withContext Cx.Fail(Cx.Kind.DENIED, "缺少机构 id（fid），请重新登录")
        val url = "$BASE/v2/apis/active/student/activelist" +
            "?fid=$fid&courseId=${course.courseId}&classId=${course.classId}" +
            "&showNotStartedActive=0&_=${System.currentTimeMillis()}"
        val t = http.get(url, referer = "$BASE/page/active/stuActiveList")
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@withContext Cx.NeedLogin
        Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@withContext it }
        if (t.code != 200) return@withContext Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")

        val root = runCatching { json.parseToJsonElement(t.body) }.getOrNull()
            ?: return@withContext Cx.Fail(Cx.Kind.PARSE, "活动列表不是 JSON：${t.body.take(80)}")
        val list = ArrayList<Activity>()
        collect(root, list)
        // 同一活动可能出现在多个分组里，按 id 去重
        Cx.Ok(list.distinctBy { it.id }.sortedByDescending { it.startAt })
    }

    /** 取一份随堂练习的卷面（含原始 JSON，提交时要原样回传） */
    suspend fun practiceSheet(activeId: String): Cx<PracticeSheet> = withContext(Dispatchers.IO) {
        val url = "$BASE/v2/apis/quiz/quizDetail2?activeId=$activeId&moreClassAttendEnc=" +
            "&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId"
        val t = http.get(url, referer = "$BASE/")
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@withContext Cx.NeedLogin
        Parsers.verifyOrNull(t.body, t.finalUrl)?.let { return@withContext it }
        if (t.code != 200) return@withContext Cx.Fail(Cx.Kind.NETWORK, "HTTP ${t.code}")

        val root = runCatching { json.parseToJsonElement(t.body).asObject() }.getOrNull()
            ?: return@withContext Cx.Fail(Cx.Kind.PARSE, "卷面不是 JSON：${t.body.take(80)}")
        if (root.int("result") != 1) {
            return@withContext Cx.Fail(Cx.Kind.DENIED, root.str("errorMsg") ?: "取卷被拒")
        }
        val data = root["data"].asObject() ?: return@withContext Cx.Fail(Cx.Kind.PARSE, "卷面缺 data")
        val active = data["pptActive"].asObject()
        val raw = (data["questionlist"] as? JsonArray) ?: JsonArray(emptyList())
        val questions = raw.mapNotNull { parseQuestion(it.asObject()) }
        if (questions.isEmpty()) return@withContext Cx.Fail(Cx.Kind.PARSE, "卷面没有题目")

        /*
         * quizDetail2 **不返回已作答内容**：实测交完后重开，personAnswer / studentAnswer
         * 全是 null、haveAnswer 是 false，只有 pptUserAttend.score 有分。
         *
         * 真正的作答记录在另一个接口 getAnswerResult（答题页在 isStuEdit=1 时调它，
         * 见内联脚本 getActiveDetailEdit）。不调这个接口，界面上永远是空框。
         * 它顺带返回 isStuEditAnswer，即"老师有没有开放修改"。
         */
        val prior = priorAnswers(activeId)
        val merged = if (prior.byId.isEmpty()) {
            questions
        } else {
            questions.map { q ->
                prior.byId[q.id]?.takeIf { it.isNotBlank() }
                    ?.let { q.copy(answerText = it, haveAnswer = true) }
                    ?: q
            }
        }

        Cx.Ok(
            PracticeSheet(
                activeId = active?.str("id").orEmpty().ifBlank { activeId },
                name = active?.str("name").orEmpty().ifBlank { "随堂练习" },
                status = active?.int("status") ?: 0,
                questions = merged,
                raw = raw,
                editable = prior.editable ?: true,
            ),
        )
    }

    /** 学生已有作答：题目 id → 答案文本，外加"是否允许修改"（null 表示没问到） */
    private data class Prior(val byId: Map<String, String>, val editable: Boolean?)

    /**
     * 取学生自己的作答记录。
     *
     * 失败一律降级成"没有作答"：这个接口只是为了让重开的卷面能回填，
     * 它挂掉不该让整个卷面打不开。
     */
    private suspend fun priorAnswers(activeId: String): Prior {
        val empty = Prior(emptyMap(), null)
        val url = "$BASE/v2/apis/studentQuestion/getAnswerResult?activeId=$activeId"
        val t = runCatching { http.get(url, referer = "$BASE/") }.getOrNull() ?: return empty
        if (Parsers.isLoginRedirect(t.finalUrl, t.body) || t.code != 200) return empty
        val root = runCatching { json.parseToJsonElement(t.body).asObject() }.getOrNull() ?: return empty
        if (root.int("result") != 1) return empty
        val data = root["data"].asObject() ?: return empty
        // isStuEditAnswer：1 可修改 / 0 只读（页面在 0 时会跳回只读视图）
        val editable = data.int("isStuEditAnswer")?.let { it == 1 }
        val arr = (data["questionList"] as? JsonArray)
            ?: (data["questionlist"] as? JsonArray)
            ?: findQuestionArray(data)
            ?: return Prior(emptyMap(), editable)

        val map = LinkedHashMap<String, String>()
        for (el in arr) {
            val o = el.asObject() ?: continue
            val id = o["id"].asLong()?.toString() ?: continue
            val text = existingAnswer(o)
            if (text.isNotBlank()) map[id] = text
        }
        return Prior(map, editable)
    }

    /** 兜底：结构变了就在整棵 data 里找"元素带 id 且有 personAnswer/studentAnswer"的数组 */
    private fun findQuestionArray(data: JsonObject): JsonArray? {
        fun scan(e: JsonElement, depth: Int): JsonArray? {
            if (depth > 3) return null
            when (e) {
                is JsonArray -> {
                    val looksLikeQuestions = e.any { el ->
                        val o = el.asObject() ?: return@any false
                        o["id"] != null && (o.containsKey("personAnswer") || o.containsKey("studentAnswer") || o.containsKey("type"))
                    }
                    if (looksLikeQuestions) return e
                    e.forEach { child -> scan(child, depth + 1)?.let { return it } }
                }
                is JsonObject -> e.values.forEach { child -> scan(child, depth + 1)?.let { return it } }
                else -> Unit
            }
            return null
        }
        return scan(data, 0)
    }

    /**
     * 提交整卷。
     *
     * @param answers key 是题目 id，value 按题型语义给：
     *  - 选择/判断：一元素，如 ["A"]（多选给 ["A","C"]）
     *  - 填空：按空数给多元素
     *  - 简答：一元素（长文本）
     *
     * 返回值只代表"服务端收下了"，不代表得分——简答题通常由老师批改。
     */
    suspend fun submit(
        course: Course,
        sheet: PracticeSheet,
        answers: Map<String, List<String>>,
        /** 已提交过就走 reDo 接口：do 接口在已有作答时会被服务端拒绝 */
        redo: Boolean = false,
    ): Cx<String> = withContext(Dispatchers.IO) {
        val body = buildJsonArray {
            for (el in sheet.raw) {
                val obj = el.asObject() ?: continue
                val qid = obj["id"].asLong()?.toString()
                val q = sheet.questions.firstOrNull { it.id == qid }
                if (qid == null || q == null) { add(obj); continue }
                val ans = answers[qid].orEmpty()
                add(
                    buildJsonObject {
                        obj.forEach { (k, v) -> if (k != "personAnswer") put(k, v) }
                        put("personAnswer", personAnswer(q, ans))
                    },
                )
            }
        }.toString()

        val api = if (redo) "reDoQuestionAnswering" else "doQuestionAnswering"
        val url = "$BASE/v2/apis/studentQuestion/$api" +
            "?activeId=${sheet.activeId}&courseId=${course.courseId}&classId=${course.classId}" +
            "&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId"
        val t = http.postJson(url, body, headers = mapOf("Referer" to "$BASE/"))
        if (Parsers.isLoginRedirect(t.finalUrl, t.body)) return@withContext Cx.NeedLogin
        if (t.code != 200) return@withContext Cx.Fail(Cx.Kind.DENIED, "提交被拒 HTTP ${t.code}")
        val res = runCatching { json.parseToJsonElement(t.body).asObject() }.getOrNull()
        if (res?.int("result") != 1) {
            return@withContext Cx.Fail(Cx.Kind.DENIED, res?.str("errorMsg") ?: t.body.take(120))
        }
        Cx.Ok("已提交")
    }

    /**
     * 按题型拼 personAnswer。字段名与答题页模板一一对应：
     * - 单选/多选/判断：`{"myoption":"A"}`（多选是字母连写 "AB"，不是数组）
     * - 填空类：`{"blankAnswer":[{"content":"…"},…]}`
     * - 简答类：`{"content":"…"}`
     */
    private fun personAnswer(q: PracticeQuestion, ans: List<String>): JsonObject = when {
        q.isChoice -> buildJsonObject {
            put("myoption", JsonPrimitive(resolveOption(q, ans)))
        }
        q.isBlankFill -> {
            val n = maxOf(q.blanks, ans.size, 1)
            buildJsonObject {
                put(
                    "blankAnswer",
                    buildJsonArray {
                        for (i in 0 until n) {
                            add(buildJsonObject { put("content", JsonPrimitive(ans.getOrElse(i) { "" })) })
                        }
                    },
                )
            }
        }
        else -> buildJsonObject {
            // 简答类只认一个 content 框：多段答案用换行保留层次，
            // 不能直接拼成一整段，否则页面上就是"糊成一团"
            put("content", JsonPrimitive(ans.filter { it.isNotBlank() }.joinToString("\n")))
        }
    }

    /**
     * 把 AI 的答案归一成选项字母。
     *
     * AI 可能回 "A"、"A."、"答案是A"、"对"、"正确"，而判断题的选项正文就是 对/错，
     * 所以先按字母匹配，再按选项正文匹配，都对不上才原样返回
     * （交给服务端判错，至少不会因为本地猜错而写到别的选项上）。
     */
    private fun resolveOption(q: PracticeQuestion, ans: List<String>): String {
        if (q.options.isEmpty()) return ans.joinToString("").trim()
        if (q.type == 1) {
            // 多选：把所有回答里命中的选项按选项顺序拼起来
            val picked = q.options.filter { o -> ans.any { a -> matchesOption(o, a) } }
            if (picked.isNotEmpty()) return picked.joinToString("") { it.letter }
        } else {
            q.options.firstOrNull { o -> ans.any { a -> matchesOption(o, a) } }?.let { return it.letter }
        }
        val first = ans.firstOrNull().orEmpty().trim()
        val letters = first.filter { c ->
            c.isLetter() && q.options.any { it.letter.equals(c.toString(), ignoreCase = true) }
        }
        return letters.ifBlank { first.take(2) }
    }

    private fun matchesOption(o: PracticeOption, raw: String): Boolean {
        val a = raw.trim()
        if (a.isEmpty()) return false
        if (a.uppercase().startsWith(o.letter.uppercase())) return true
        // 判断题：选项正文是"对 / 错"，AI 常直接答"对"/"正确"
        val t = o.text.trim()
        if (t.isNotEmpty() && (a.contains(t) || t.contains(a))) return true
        if (t.startsWith("对") && (a.contains("正确") || a.equals("T", true) || a.equals("true", true))) return true
        if (t.startsWith("错") && (a.contains("错误") || a.equals("F", true) || a.equals("false", true))) return true
        return false
    }

    private fun parseQuestion(q: JsonObject?): PracticeQuestion? {
        if (q == null) return null
        val id = q["id"].asLong()?.toString() ?: return null
        val rawAnswer = (q["answer"] as? JsonArray).orEmpty()
        val options = rawAnswer.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val letter = o.str("name").orEmpty()
            val text = stripHtml(o.str("content").orEmpty())
            if (letter.isBlank() && text.isBlank()) null else PracticeOption(letter, text)
        }
        return PracticeQuestion(
            id = id,
            seq = q.int("sequence") ?: 0,
            type = q.int("type") ?: -1,
            stem = stripHtml(q.str("content").orEmpty()),
            options = options,
            blanks = rawAnswer.size,
            integral = q.int("integral") ?: 0,
            answerText = existingAnswer(q),
            haveAnswer = q.bool("haveAnswer"),
        )
    }

    /**
     * 把服务端下发的作答内容摊平成一段文本。
     *
     * 为什么要试多个字段、多种形态：实测提交成功后重开，`personAnswer` 可能仍是 null，
     * 真正的作答记录落在 `studentAnswer`；而这两者有时是对象、有时是被序列化过的
     * JSON 字符串、有时是只含一个元素的数据组。只认一种形态的结果就是
     * "明明交过，打开还是空框"。这里按 字段 × 形态 全试一遍，取第一个非空。
     */
    private fun existingAnswer(q: JsonObject): String {
        for (key in ANSWER_KEYS) {
            val t = flattenAnswer(q[key])
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun flattenAnswer(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonNull -> ""
        is JsonArray -> el.asSequence().map { flattenAnswer(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        is JsonObject -> when {
            el.str("content").orEmpty().isNotBlank() -> el.str("content").orEmpty()
            el.str("myoption").orEmpty().isNotBlank() -> el.str("myoption").orEmpty()
            el["blankAnswer"] is JsonArray -> (el["blankAnswer"] as JsonArray)
                .mapNotNull { it.asObject()?.str("content") }
                .joinToString("\n")
                .trim()
            else -> ""
        }
        is JsonPrimitive -> {
            val raw = el.contentOrNull.orEmpty().trim()
            when {
                raw.isEmpty() -> ""
                // 被序列化过的 JSON：再解一次
                raw.startsWith("{") || raw.startsWith("[") ->
                    runCatching { flattenAnswer(json.parseToJsonElement(raw)) }.getOrDefault("")
                // 纯文本答案（有些题型 server 直接给字符串）
                else -> raw
            }
        }
        else -> ""
    }

    /**
     * 递归收集：凡是同时带 id / activeType 的对象就当成一条活动。
     * 题目对象没有 activeType，不会误判。
     */
    private fun collect(e: JsonElement, out: MutableList<Activity>) {
        when (e) {
            is JsonArray -> e.forEach { collect(it, out) }
            is JsonObject -> {
                val type = e["activeType"].asInt()
                if (type != null) {
                    val id = e["id"].asLong()
                    if (id != null) {
                        out += Activity(
                            id = id.toString(),
                            // 列表里 name 常常为空，回退成可读的类型名，别显示"活动 42"
                            name = e.str("name").orEmpty()
                                .ifBlank { e.str("activeName").orEmpty() }
                                .ifBlank { activityTypeName(type) },
                            type = type,
                            status = e.int("status") ?: 0,
                            startAt = e["starttime"].asLong() ?: 0,
                            endAt = e["endtime"].asLong() ?: 0,
                            attendCount = e.int("attendNum") ?: 0,
                        )
                        return
                    }
                }
                e.values.forEach { collect(it, out) }
            }
            else -> Unit
        }
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asLong(): Long? = runCatching { (this as? JsonPrimitive)?.longOrNull }.getOrNull()

    private fun JsonElement?.asInt(): Int? = asLong()?.toInt()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? = this[key].asInt()

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false

    companion object {
        const val BASE = "https://mobilelearn.chaoxing.com"

        /** 作答内容可能落在这几个字段上，按优先级试 */
        private val ANSWER_KEYS = listOf("personAnswer", "studentAnswer")

        /**
         * 题干是 HTML（`<p>`、`<span style>` 都有）。只做"去标签 + 还原常见实体"，
         * 给 UI 显示与提示词用，不参与提交，宁可少还原几个不常见的实体。
         */
        fun stripHtml(html: String): String = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }
}
