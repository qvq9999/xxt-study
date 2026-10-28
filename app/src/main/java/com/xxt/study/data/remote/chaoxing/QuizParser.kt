package com.xxt.study.data.remote.chaoxing

import com.xxt.study.domain.QOption
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import org.jsoup.Jsoup

/** 一份章节测验卷面 */
data class QuizPage(
    /** 表单隐藏域，提交时原样带上（workId / enc / ktoken 这类都在里面） */
    val form: Map<String, String>,
    val questions: List<Question>,
) {
    val isEmpty: Boolean get() = questions.isEmpty()
}

/**
 * 章节测验卷面解析。
 *
 * 结构与作业作答页**不同**，别混用 QuestionParser：
 *   作业页：一个 div.TiMu 装着多个 div.questionLi
 *   测验页：多个 div.singleQuesId，每个里面一个 div.TiMu[data=题型码]
 *
 * 题型码在这里挂在 div.TiMu 上（作业页挂在 input[name=answertype<qid>] 上），
 * 这是两套页面最容易串的地方。
 *
 * 题干与选项都要过一遍字体解码（参数 [decode]），因为测验页带字体反爬，
 * 作业页没有——这是两个页面第二个关键差异。
 */
object QuizParser {

    /** 选项行前缀：A. / A、 / A． / A) / A ） */
    private val OPTION_PREFIX = Regex("^\\s*([A-Za-z])\\s*[.、．:：)）]?\\s*")

    /** 题型标记，形如「（单选题）」「(多选题)」 */
    private val TYPE_LABEL = Regex("[（(]\\s*(单选题|多选题|判断题|填空题|简答题|论述题|名词解释|计算题)\\s*[）)]")

    fun parse(html: String, decode: (String) -> String = { it }): QuizPage {
        if (html.isBlank()) return QuizPage(emptyMap(), emptyList())
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return QuizPage(emptyMap(), emptyList())

        // 隐藏域：凡是 name 里带 answer 的都是答题位，不预填
        val form = LinkedHashMap<String, String>()
        for (input in doc.select("form input[name]")) {
            val name = input.attr("name").trim()
            if (name.isEmpty() || name.contains("answer", ignoreCase = true)) continue
            form[name] = input.attr("value")
        }

        val questions = ArrayList<Question>()
        doc.select("div.singleQuesId").forEachIndexed { index, div ->
            val qid = div.attr("data").trim().ifBlank {
                div.id().removePrefix("question").trim()
            }
            if (qid.isEmpty()) return@forEachIndexed

            val tiMu = div.selectFirst("div.TiMu")
            val typeCode = tiMu?.attr("data")?.trim().orEmpty()
            val stemRaw = div.selectFirst("div.Zy_TItle")?.text().orEmpty()
            val stemDecoded = decode(stemRaw)

            val label = TYPE_LABEL.find(stemDecoded)?.groupValues?.get(1).orEmpty()
            val options = div.select("ul li").mapNotNull { li ->
                val text = decode(li.text()).trim()
                if (text.isEmpty()) null else optionOf(text)
            }

            questions += Question(
                qid = qid,
                number = index + 1,
                type = typeOf(label, typeCode, options.size),
                typeLabel = label,
                stem = cleanStem(stemDecoded),
                options = options,
                blanks = blankCountOf(div.select("input[name^=answer$qid]").size),
            )
        }
        return QuizPage(form, questions)
    }

    private fun optionOf(text: String): QOption {
        val m = OPTION_PREFIX.find(text)
        val letter: String
        val body: String
        if (m != null && m.value.isNotBlank() && m.value.trim().first().isLetter()) {
            letter = m.groupValues[1].uppercase()
            body = text.substring(m.value.length).trim()
        } else {
            // 没有字母前缀：用序号兜底，正文就是整行
            letter = ""
            body = text
        }
        return QOption(letter = letter, value = letter, text = body.ifBlank { text })
    }

    /** 去掉题号前缀与题型标记，只留干净题干给 AI */
    private fun cleanStem(raw: String): String {
        var s = raw.replace("\r", " ").replace("\n", " ").trim()
        s = s.replaceFirst(Regex("^\\s*\\d+\\s*[.、．]\\s*"), "")
        s = TYPE_LABEL.replace(s, "")
        return s.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun blankCountOf(answerInputs: Int): Int = answerInputs.coerceAtLeast(0)

    /**
     * 题型判定：题目标记优先，其次 TiMu 的 data 码值，最后靠"没有选项"猜填空。
     * 码值含义与作业页一致：0 单选 / 1 多选 / 2 填空 / 3 判断。
     */
    private fun typeOf(label: String, code: String, optionCount: Int): QType = when {
        label.contains("单选") -> QType.SINGLE
        label.contains("多选") -> QType.MULTIPLE
        label.contains("判断") -> QType.JUDGE
        label.contains("填空") -> QType.FILL
        label.contains("简答") || label.contains("论述") ||
            label.contains("名词解释") || label.contains("计算") -> QType.SHORT
        code == "0" -> QType.SINGLE
        code == "1" -> QType.MULTIPLE
        code == "2" -> QType.FILL
        code == "3" -> QType.JUDGE
        optionCount == 0 -> QType.FILL
        else -> QType.UNKNOWN
    }

    /** 提交时的题型码，与页面 input[name=answertype<qid>] 的取值一致 */
    fun typeCodeOf(type: QType): String = when (type) {
        QType.SINGLE -> "0"
        QType.MULTIPLE -> "1"
        QType.FILL -> "2"
        QType.JUDGE -> "3"
        QType.SHORT -> "4"
        QType.UNKNOWN -> "0"
    }
}
