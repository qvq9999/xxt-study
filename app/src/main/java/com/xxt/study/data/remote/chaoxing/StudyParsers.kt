package com.xxt.study.data.remote.chaoxing

import com.xxt.study.domain.CardDefaults
import com.xxt.study.domain.ChapterJobs
import com.xxt.study.domain.ChapterNode
import com.xxt.study.domain.CourseTree
import com.xxt.study.domain.Job
import com.xxt.study.domain.JobKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/**
 * 刷课相关的页面解析，全部纯函数。
 *
 * 两个页面的结构照抄 SuperAutoStudy 的 Decode.java（那份代码是在真实账号上跑通的），
 * 但做了三处加固：
 * 1. 章节 id 不只认「第一个 div」——li 自身带 id 也能认，学习通两种排版都出现过
 * 2. mArg 用括号配平扫描而不是 `.*?};` 懒匹配，避免 JSON 里嵌套对象被截断
 * 3. 任务点按 jobId 去重——num=0/1/2 三遍抓取会返回重叠内容
 */
object StudyParsers {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val CHAPTER_ID = Regex("^cur(\\d{1,20})$")

    // ---------------- 章节目录 ----------------

    /**
     * 解析 mooc2-ans/mycourse/studentcourse 返回的章节目录。
     *
     * 结构：
     *   div.chapter_unit
     *     li
     *       div#cur12345                ← 知识点 id 在 id 属性上
     *         a.clicktitle              ← 章节名
     *         input.knowledgeJobCount   ← 任务点数量（缺失说明是"解锁"提示行）
     */
    fun parseCourseTree(html: String): CourseTree {
        if (html.isBlank()) return CourseTree(emptyList())
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return CourseTree(emptyList())

        val nodes = ArrayList<ChapterNode>()
        var hasLocked = false
        val seen = HashSet<String>()

        for (li in doc.select("div.chapter_unit li")) {
            val (id, holder) = chapterIdOf(li) ?: continue
            if (!seen.add(id)) continue

            val title = holder.selectFirst("a.clicktitle")?.text()
                ?.replace("\n", "")?.trim()
                .orEmpty()
                .ifBlank { holder.text().replace("\n", "").trim().take(40) }

            val countInput = holder.selectFirst("input.knowledgeJobCount")
            val jobCount = countInput?.attr("value")?.trim()?.toIntOrNull()
                ?: li.selectFirst("input.knowledgeJobCount")?.attr("value")?.trim()?.toIntOrNull()

            val locked = jobCount == null &&
                (holder.selectFirst("span.bntHoverTips")?.text()?.contains("解锁") == true)
            if (locked) hasLocked = true

            nodes += ChapterNode(
                id = id,
                title = title,
                jobCount = jobCount ?: 1,
                locked = locked,
            )
        }
        return CourseTree(nodes, hasLocked)
    }

    /** 知识点 id 可能在 li 自己身上，也可能在第一个内层 div 上；两种都试 */
    private fun chapterIdOf(li: org.jsoup.nodes.Element): Pair<String, org.jsoup.nodes.Element>? {
        CHAPTER_ID.matchEntire(li.id().trim())?.let { return it.groupValues[1] to li }
        for (div in li.select("div")) {
            CHAPTER_ID.matchEntire(div.id().trim())?.let { return it.groupValues[1] to div }
        }
        return null
    }

    // ---------------- 任务点列表 ----------------

    /**
     * 解析 mooc1.chaoxing.com/mooc-ans/knowledge/cards 返回的任务点。
     *
     * 关键：任务点不在 DOM 里，在页面脚本的 `mArg={...};` 变量里。
     * 结构：
     *   mArg.defaults      → ktoken / cpi / knowledgeid / reportTimeInterval ...
     *   mArg.attachments[] → 每个任务点一张卡，看 type 字段分流
     *
     * 过滤规则（与 SuperAutoStudy 一致）：
     * - isPassed=true 已完成，跳过
     * - type=video 但缺 mid → 转码失败，没有可播资源，跳过
     * - type=vote（问卷）不处理
     */
    fun parseJobs(html: String): ChapterJobs {
        if (html.isBlank()) return ChapterJobs(emptyList(), CardDefaults())
        if (html.contains("章节未开放")) {
            return ChapterJobs(emptyList(), CardDefaults(), notOpen = true)
        }

        val raw = extractMArg(html) ?: return ChapterJobs(emptyList(), CardDefaults())
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ChapterJobs(emptyList(), CardDefaults())

        val defaults = parseDefaults(root["defaults"] as? JsonObject)
        val attachments = root["attachments"] as? JsonArray ?: JsonArray(emptyList())

        val jobs = ArrayList<Job>()
        val seen = HashSet<String>()
        for (el in attachments) {
            val card = el as? JsonObject ?: continue
            val job = jobOf(card) ?: continue
            // num=0/1/2 三遍抓取会重复，jobId 相同只留第一个
            if (!seen.add(job.jobId)) continue
            jobs += job
        }
        return ChapterJobs(jobs, defaults, notOpen = false)
    }

    private fun parseDefaults(obj: JsonObject?): CardDefaults {
        if (obj == null) return CardDefaults()
        return CardDefaults(
            knowledgeId = obj.str("knowledgeid", "knowledgeId"),
            cpi = obj.str("cpi"),
            ktoken = obj.str("ktoken"),
            cardId = obj.str("cardid", "cardId"),
            defenc = obj.str("defenc"),
            mtEnc = obj.str("mtEnc"),
            qnenc = obj.str("qnenc"),
            reportInterval = obj.str("reportTimeInterval").toIntOrNull() ?: 60,
        )
    }

    private fun jobOf(card: JsonObject): Job? {
        // 已完成的任务点直接跳过，不必再刷一遍
        if (card.bool("isPassed")) return null

        val type = card.str("type").lowercase()
        val isJob = card.bool("job")
        val property = card["property"] as? JsonObject

        return when {
            // 阅读任务：不是任务点，而是「阅读型」附件，未读才算待办
            !isJob && type == "read" -> {
                if (property?.bool("read") == true) null else Job(
                    kind = JobKind.READ,
                    jobId = card.str("jobid"),
                    name = property?.str("title").orEmpty(),
                    jtoken = card.str("jtoken"),
                    otherInfo = card.str("otherInfo"),
                    enc = card.str("enc"),
                    aid = card.str("aid"),
                    // 阅读任务的 knowledgeid 从 otherInfo 里的 nodeId_xxx 抽
                    objectId = property?.str("id").orEmpty(),
                )
            }

            type == "video" -> {
                // 缺 mid 说明转码没完成，服务端不会给播放地址，刷不了
                if (card["mid"] == null) null else Job(
                    kind = JobKind.VIDEO,
                    jobId = card.str("jobid"),
                    name = property?.str("name").orEmpty(),
                    objectId = card.str("objectId", "objectid"),
                    otherInfo = card.str("otherInfo"),
                    mid = card.str("mid"),
                    aid = card.str("aid"),
                )
            }

            type == "document" -> Job(
                kind = JobKind.DOCUMENT,
                jobId = card.str("jobid"),
                name = property?.str("name", "title").orEmpty(),
                objectId = property?.str("objectid", "objectId").orEmpty(),
                otherInfo = card.str("otherInfo"),
                jtoken = card.str("jtoken"),
                mid = card.str("mid"),
                enc = card.str("enc"),
                aid = card.str("aid"),
            )

            type == "workid" -> Job(
                kind = JobKind.QUIZ,
                jobId = card.str("jobid"),
                name = property?.str("title", "name").orEmpty(),
                otherInfo = card.str("otherInfo"),
                mid = card.str("mid"),
                enc = card.str("enc"),
                aid = card.str("aid"),
            )

            // audio 是 video 的兄弟结构，学习通部分课程用它
            type == "audio" -> {
                if (card["objectId"] == null && card["objectid"] == null) null else Job(
                    kind = JobKind.AUDIO,
                    jobId = card.str("jobid"),
                    name = property?.str("name").orEmpty(),
                    objectId = card.str("objectId", "objectid"),
                    otherInfo = card.str("otherInfo"),
                    mid = card.str("mid"),
                    aid = card.str("aid"),
                )
            }

            else -> null
        }
    }

    /**
     * 从页面里抠出 `mArg={...};` 的 JSON 主体。
     *
     * 不用正则懒匹配：mArg 里嵌着 attachments 数组和 property 对象，
     * `.*?};` 遇到内层 "};" 就会截断。这里按花括号配平逐字符扫描，
     * 同时跳过字符串字面量里的花括号。
     */
    fun extractMArg(html: String): String? {
        // 只压掉换行（脚本里的多行会截断 JSON 字面量），保留空格——
        // 一旦把空格也删了，题干里的「第 1 章」会变成「第1章」，回填时对不上原题。
        val flat = html.replace("\r", "").replace("\n", "")
        var from = 0
        while (true) {
            val at = flat.indexOf("mArg", from)
            if (at < 0) return null
            from = at + 4
            val braceAt = flat.indexOf('{', from)
            if (braceAt < 0) return null
            // mArg 与 { 之间最多垫一个 = 和空白，隔太远说明不是这个变量
            if (braceAt - from > 8) continue

            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            var i = braceAt
            while (i < flat.length) {
                val c = flat[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break
                            }
                        }
                    }
                }
                i++
            }
            if (end > braceAt) {
                val body = flat.substring(braceAt, end + 1)
                // 至少要有 attachments 或 defaults 才算抓到真家伙
                if (body.contains("attachments") || body.contains("defaults")) return body
            }
        }
    }

    // ---------------- JSON 小工具 ----------------

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            val s = p.content.trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull
            ?: (this[key] as? JsonPrimitive)?.content?.trim()?.equals("true", true)
            ?: false
}
