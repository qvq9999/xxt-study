package com.xxt.study.data.repo

import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.ai.LengthRule
import com.xxt.study.core.ai.LengthRules
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.ActivityClient
import com.xxt.study.domain.Course
import com.xxt.study.domain.PracticeQuestion
import com.xxt.study.domain.PracticeSheet
import com.xxt.study.domain.QOption
import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import com.xxt.study.domain.typeLabel

/**
 * 随堂练习的作答流程：取卷 → 交给 AI → 按题号收回答案 → 提交。
 *
 * 为什么不复用 `QuizSolver`：那个类绑定的是章节测验的 `QuizPage`（含隐藏域、
 * 字体反爬、`answerwqbid` 那套表单）。随堂练习是 JSON 整卷提交，模型完全不同，
 * 硬套只会把两边的特殊处理搅在一起。真正共用的是更下一层的 [AiClient.answer]
 * 与 [Question] 模型——提示词、JSON 格式、答案清洗都在那一层。
 */
class PracticeHandler(
    private val client: ActivityClient,
    private val ai: AiClient,
) {

    /**
     * 整卷一次问完；整卷解析失败时自动退回逐题。
     *
     * 为什么要有逐题兜底：随堂练习几乎全是简答题，模型输出的 JSON 里
     * 一旦带了裸换行、Markdown 或多余逗号，整卷就解不出来 ——
     * 一道题的格式问题会让整套题都拿不到答案，这在只有 1 分钟的活动里等于直接放弃。
     * 逐题请求输出体积小、格式简单，失败也只影响那一题。
     */
    suspend fun solve(sheet: PracticeSheet, onTick: (String) -> Unit = {}): Cx<Map<String, List<String>>> {
        val questions = sheet.questions.map { it.toQuestion() }
        if (questions.isEmpty()) return Cx.Fail(Cx.Kind.PARSE, "卷面没有题目")

        onTick("正在让 AI 作答（${questions.size} 题）…")
        return when (val r = ai.answer(questions, PRACTICE_RULE)) {
            is Cx.Ok -> {
                // AI 偶尔漏题。漏掉的题留空，提交前界面会标出来，
                // 绝不拿上一题或其他题的答案去填——那比空着更糟
                val missing = questions.filter { r.data.answers[it.qid].isNullOrEmpty() }
                if (missing.isNotEmpty()) {
                    onTick("有 ${missing.size} 题没拿到答案，正在逐题补…")
                    val patched = LinkedHashMap(r.data.answers)
                    for (q in missing) {
                        val one = askOne(q, onTick) ?: continue
                        patched[q.qid] = listOf(one)
                    }
                    return Cx.Ok(patched)
                }
                Cx.Ok(r.data.answers)
            }
            is Cx.Fail -> {
                if (r.kind != Cx.Kind.PARSE) return r
                // 整卷 JSON 解不出来：退化成逐题，能救一道是一道
                onTick("整卷返回的格式不对，改为逐题作答…")
                val out = LinkedHashMap<String, List<String>>()
                for (q in questions) {
                    val one = askOne(q, onTick) ?: continue
                    out[q.qid] = listOf(one)
                }
                if (out.isEmpty()) {
                    Cx.Fail(Cx.Kind.PARSE, "整卷与逐题都没拿到答案，请重试或换模型")
                } else {
                    Cx.Ok(out)
                }
            }
            Cx.NeedLogin -> Cx.Fail(Cx.Kind.DENIED, "AI 接口需要重新配置")
        }
    }

    /** 单题问一次；失败返回 null（调用方决定是跳过还是继续） */
    private suspend fun askOne(q: Question, onTick: (String) -> Unit): String? {
        onTick("逐题作答：第 ${q.number} 题…")
        val rule = LengthRules.parse(q.stem) ?: LengthRule(null, null)
        return when (val r = ai.answerOne(q, rule, "")) {
            is Cx.Ok -> r.data
            is Cx.Fail -> null
            Cx.NeedLogin -> null
        }
    }

    suspend fun submit(
        course: Course,
        sheet: PracticeSheet,
        answers: Map<String, List<String>>,
        /** 之前交过就走重交接口 */
        redo: Boolean = false,
    ): Cx<String> = client.submit(course, sheet, answers, redo)

    /**
     * 把随堂练习的题映射成 AI 认得的 [Question]。
     *
     * 题型编码与 QType 的对应关系（学习通 → 页面控件）：
     * 0 单选 / 1 多选 / 3·16 判断 / 2·9·10 填空类 / 其余按简答处理。
     * 拿不准的一律当简答：宁可让 AI 写一段文字，也不要按选择题去对字母。
     */
    private fun PracticeQuestion.toQuestion(): Question {
        val qType = when (type) {
            0 -> QType.SINGLE
            1 -> QType.MULTIPLE
            3, 16 -> QType.JUDGE
            2, 9, 10 -> QType.FILL
            else -> QType.SHORT
        }
        return Question(
            qid = id,
            number = seq,
            type = qType,
            typeLabel = typeLabel(type),
            stem = stem,
            options = options.map { QOption(letter = it.letter, value = it.letter, text = it.text) },
            blanks = if (qType == QType.FILL) maxOf(blanks, 1) else 0,
        )
    }

    private companion object {
        /**
         * 随堂练习的额外硬约束。
         *
         * 通用提示词是按"章节测验"写的，答案里允许 Markdown 换行；
         * 随堂练习的答案要直接进网页输入框，且整卷是一个 JSON，
         * 任何 Markdown 记号或双引号都会同时破坏"可读性"和"可解析性"。
         */
        val PRACTICE_RULE = """
            本次是随堂练习，在上面规则之外再加三条硬要求：
            - 每题 answer 必须是纯文本：不要 Markdown（不要 **加粗**、不要 - 列表、不要 # 标题、
              不要表格、不要代码块、不要 LaTeX 公式符号）。
            - 分点用「1. 2. 3.」或分号；需要分段时用一个换行，不要空行。
            - 答案里不要出现英文双引号与反斜杠，需要引用时用「」。
            选择题与判断题仍然只输出选项字母或 true/false，这一条不变。
        """.trimIndent()
    }
}
