package com.xxt.study.data.remote.chaoxing

import com.xxt.study.domain.QOption
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 作业作答页的题目解析。
 *
 * 真实结构（2026-10-27 在「蒙版」单选题作业上实测，fixture 见 test/resources/work-question.html）：
 *
 *   1 个 div.TiMu（整卷容器，不是单题！）里装着 12 个 div.questionLi
 *   每个 div.questionLi[typename][data=<qid>] 里：
 *     h3.mark_name      题干（含 span.colorShallow 的 "(单选题)" 标记）
 *     input#answertype<qid>   题型码 0单选/1多选/2填空/3判断（交叉校验用）
 *     input#tiankongsize<qid> 填空格数
 *     div.answerBg[qid][qtype][role][aria-label]  一个选项
 *        span.num_option[data]  选择题 data=字母；判断题 data=true/false
 *        div.answer_p           选项正文
 *
 * 注意：千万别把 div.TiMu 当单题——那样只会解析出第 1 题，答案必然串题。
 */
object QuestionParser {

    fun parse(html: String): List<Question> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()

        val containers = doc.select("div.questionLi")
        if (containers.isEmpty()) return emptyList()

        val out = ArrayList<Question>(containers.size)
        containers.forEachIndexed { index, el ->
            val qid = qidOf(el) ?: return@forEachIndexed
            val typeCode = doc.selectFirst("input[name=answertype$qid]")?.attr("value")
            val typeLabel = el.attr("typename").ifBlank { labelFromStem(el) }
            val blanks = doc.selectFirst("input[name=tiankongsize$qid]")?.attr("value")
                ?.toIntOrNull() ?: 0

            out += Question(
                qid = qid,
                number = index + 1,
                type = typeOf(typeLabel, typeCode),
                typeLabel = typeLabel,
                stem = stemOf(el),
                options = optionsOf(el),
                blanks = blanks,
            )
        }
        return out
    }

    private fun qidOf(el: Element): String? {
        val fromData = el.attr("data").trim()
        if (fromData.isNotEmpty()) return fromData
        val fromId = el.id().removePrefix("question").trim()
        return fromId.ifEmpty { null }
    }

    /** 兜底：从题干里的 (单选题) 取题型名 */
    private fun labelFromStem(el: Element): String {
        val t = el.selectFirst("span.colorShallow")?.text().orEmpty().trim()
        return t.trim('(', ')', '（', '）')
    }

    private fun stemOf(el: Element): String {
        val h3 = el.selectFirst("h3.mark_name") ?: return ""
        val copy = h3.clone()
        copy.select("span.colorShallow").remove()
        val text = copy.text().trim()
        // 去掉开头的题号 "1. " / "1、"
        return text.replaceFirst(Regex("^\\s*\\d+\\s*[.、]\\s*"), "").trim()
    }

    private fun optionsOf(el: Element): List<QOption> =
        el.select("div.answerBg").mapNotNull { opt ->
            val letter = opt.selectFirst("span.num_option")?.text()?.trim().orEmpty()
            if (letter.isEmpty()) return@mapNotNull null
            val value = opt.selectFirst("span.num_option")?.attr("data").orEmpty().trim()
            val body = opt.selectFirst("div.answer_p")?.text()?.trim().orEmpty()
            val text = body.ifEmpty { fromAriaLabel(opt.attr("aria-label"), letter) }
            QOption(letter = letter, value = value, text = text)
        }

    /** aria-label 形如 "A 显示选择" / "A对选择"，去掉字母与结尾的"选择" */
    private fun fromAriaLabel(aria: String, letter: String): String =
        aria.trim().removePrefix(letter).removeSuffix("选择").trim()

    /**
     * 题型判定：优先页面给的 typename，其次隐藏域题型码。
     * 实测码值：0 单选 / 1 多选 / 2 填空 / 3 判断。
     */
    private fun typeOf(label: String, code: String?): QType = when {
        label.contains("单选") -> QType.SINGLE
        label.contains("多选") -> QType.MULTIPLE
        label.contains("判断") -> QType.JUDGE
        label.contains("填空") -> QType.FILL
        label.contains("论述") || label.contains("简答") || label.contains("名词解释") ||
            label.contains("计算") || label.contains("其它") || label.contains("其他") -> QType.SHORT
        code == "0" -> QType.SINGLE
        code == "1" -> QType.MULTIPLE
        code == "2" -> QType.FILL
        code == "3" -> QType.JUDGE
        else -> QType.UNKNOWN
    }

    /**
     * 判断题归一：页面选项正文是「对/错」，但要兼容其它写法。
     * 注意不能看 num_option 的字母（A/B），要看正文或 data 的 true/false。
     */
    fun normalizeJudge(option: QOption): Boolean? {
        val t = option.text.trim()
        val v = option.value.lowercase()
        return when {
            t.contains("对") || t.contains("正确") || t.contains("√") -> true
            t.contains("错") || t.contains("错误") || t.contains("×") -> false
            v == "true" -> true
            v == "false" -> false
            else -> null
        }
    }
}
