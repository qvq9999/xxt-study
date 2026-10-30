package com.xxt.study.data.repo

import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.QuizPage
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question

/** 一题的作答结果 */
data class SolvedAnswer(
    val qid: String,
    /** 提交给学习通的值；null 表示这题没拿到可用答案 */
    val value: String?,
    /** 给人看的说明，如 "A"、"未作答：模型未给出" */
    val note: String,
    /**
     * 结构化分量。
     *
     * - 多选：每个字母一个元素
     * - **填空：一空一个元素，顺序就是页面上的"第1空/第2空"**
     * - 其他：空
     *
     * [value] 是给人看的拼合串（也用于不需要分量的场景）；分量才是回填的依据——
     * 多空填空题在页面上是 N 个独立编辑框，拿拼合串往里填必然全落进第 1 空。
     */
    val parts: List<String> = emptyList(),
)

data class QuizSolution(
    val answers: List<SolvedAnswer>,
) {
    val answerMap: Map<String, String>
        get() = answers.mapNotNull { a -> a.value?.let { a.qid to it } }.toMap()

    val missing: List<SolvedAnswer> get() = answers.filter { it.value == null }
}

/**
 * 把 AI 给的自然语言式答案翻译成学习通要的提交值。
 *
 * 中间这层不能省：AI 说"选 A"，学习通要的是 `answer<qid>=A`；
 * 判断题更绕——AI 说 "true"，学习通要 `true`，但选项字母是 A/B，
 * 而"对"可能挂在 A 也可能挂在 B，得按选项正文反查。
 */
class QuizSolver(private val ai: AiClient) {

    suspend fun solve(page: QuizPage): Cx<QuizSolution> {
        val resp = ai.answer(page.questions)
        val raw = when (resp) {
            is Cx.Ok -> resp.data.answers
            is Cx.Fail -> return resp
            Cx.NeedLogin -> return Cx.Fail(Cx.Kind.UNKNOWN, "答题流程不需要登录态")
        }

        val answers = page.questions.map { q ->
            AnswerMapper.toAnswer(q, raw[q.qid].orEmpty())
        }
        return Cx.Ok(QuizSolution(answers))
    }

}

/**
 * AI 答案 → 学习通提交值的纯映射。
 *
 * 独立成 object 是为了能脱离 Android（AiClient 依赖 SecretStore）直接单测——
 * 这部分恰恰是最容易出错、最需要单测的：字母对错位、判断题正反了，都看不出来。
 */
object AnswerMapper {

    /** 单题转换；任何对不上的情况都返回 null 而不是瞎猜 */
    fun toAnswer(q: Question, given: List<String>): SolvedAnswer {
        val letters = q.options.map { it.letter.uppercase() }.filter { it.isNotBlank() }
        val first = given.firstOrNull()?.trim().orEmpty()

        return when (q.type) {
            QType.SINGLE -> {
                val pick = first.uppercase().take(1).ifBlank { "" }
                val ok = pick.isNotBlank() && (letters.isEmpty() || pick in letters)
                if (ok) SolvedAnswer(q.qid, pick, pick)
                else SolvedAnswer(q.qid, null, "单选：模型给的是「$first」，不在选项里")
            }

            QType.MULTIPLE -> {
                val picks = given.flatMap { it.uppercase().filter { c -> c in 'A'..'Z' }.toList() }
                    .distinct().sorted()
                val valid = picks.filter { letters.isEmpty() || it.toString() in letters }
                if (valid.isEmpty()) SolvedAnswer(q.qid, null, "多选：模型给的是「$first」，不在选项里")
                else SolvedAnswer(
                    qid = q.qid,
                    value = valid.joinToString(""),
                    note = valid.joinToString(""),
                    parts = valid.map { it.toString() },
                )
            }

            QType.JUDGE -> judgeAnswer(q, given)

            QType.FILL -> {
                /*
                 * 一空一个元素，**保序、保留空位**。
                 *
                 * 不能 filter 掉空串：第 2 空为空时若被删掉，第 3 空的答案就会被写到第 2 空里。
                 * 也不能把多空拼成一句——页面上它们是各自独立的编辑框（见 FillScript 注释）。
                 */
                val parts = given.map { it.trim() }
                val nonEmpty = parts.count { it.isNotBlank() }
                if (nonEmpty == 0) {
                    SolvedAnswer(q.qid, null, "填空：模型未给出内容")
                } else {
                    val text = parts.filter { it.isNotBlank() }.joinToString(" ")
                    SolvedAnswer(q.qid, text, text.take(30), parts)
                }
            }

            QType.SHORT -> {
                val text = given.joinToString(" ") { it.trim() }.trim()
                if (text.isEmpty()) SolvedAnswer(q.qid, null, "填空/简答：模型未给出内容")
                else SolvedAnswer(q.qid, text, text.take(30))
            }

            QType.UNKNOWN -> {
                // 题型不明时按单选处理，但明确标注，方便用户复核
                val pick = first.uppercase().take(1)
                if (pick.isNotBlank() && pick in letters) SolvedAnswer(q.qid, pick, "$pick（题型未识别）")
                else SolvedAnswer(q.qid, null, "题型未识别，模型答案「$first」无法映射")
            }
        }
    }

    /**
     * 判断题。
     *
     * 三种可能的输入都要吃得下：
     * - "true"/"false"（我们的 prompt 要求）
     * - "A"/"B"（模型自发挥）
     * - "对"/"错"（模型自作主张）
     * 最终统一成学习通要的 true / false。
     */
    private fun judgeAnswer(q: Question, given: List<String>): SolvedAnswer {
        val first = given.firstOrNull()?.trim().orEmpty()
        val lower = first.lowercase()

        if (lower == "true" || lower == "false") {
            return SolvedAnswer(q.qid, lower, if (lower == "true") "对" else "错")
        }
        if (first.contains("对") || first.contains("正确") || first == "√" || first == "T") {
            return SolvedAnswer(q.qid, "true", "对")
        }
        if (first.contains("错") || first.contains("错误") || first == "×" || first == "F") {
            return SolvedAnswer(q.qid, "false", "错")
        }

        // 给的是选项字母：按该选项正文判断是「对」还是「错」
        val letter = first.uppercase().take(1)
        val opt = q.options.firstOrNull { it.letter.uppercase() == letter }
        if (opt != null) {
            val isTrue = opt.text.contains("对") || opt.text.contains("正确") || opt.text.contains("√")
            return SolvedAnswer(q.qid, if (isTrue) "true" else "false", "${opt.letter} ${opt.text}")
        }
        return SolvedAnswer(q.qid, null, "判断：模型答案「$first」无法映射")
    }
}
