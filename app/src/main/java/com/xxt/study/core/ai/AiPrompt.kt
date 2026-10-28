package com.xxt.study.core.ai

import com.xxt.study.domain.QType
import com.xxt.study.domain.Question
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 提示词构造。
 *
 * 单独抽出来的原因：**答案质量几乎全由提示词决定**，而它以前藏在 `AiClient` 的私有方法里，
 * 改错了没人能测。现在可以对着它写断言（见 `AiPromptTest`）。
 *
 * 三条硬约束，都是被真实问题逼出来的：
 * 1. **紧扣题干**：模型容易写"放之四海皆准"的套话，题干问的是 A 它答 B；
 * 2. **字数达标**：要求 150-250 字却只给 90 字，是最高频的投诉。所以把解析出的字数要求
 *    **直接写进题目字段**（`length`），并要求写到区间中上部——贴下限往往会因为标点差异掉出去；
 * 3. **覆盖全部题目**：漏题会导致整卷不提交（有兜底但白跑一趟）。
 */
internal object AiPrompt {

    /** 系统提示。只讲"怎么做"，题目细节在 user 里 */
    const val SYSTEM: String =
        "你是严谨的课程测验答题助手。你只输出 JSON，不要输出解释、不要输出 Markdown、不要复述题目。" +
            "每道题的答案都必须紧扣题干本身（选择题还要紧扣给出的选项）：" +
            "先判断题干到底在问什么，再针对性地回答，不要写与题干无关的通用套话，也不要编造题干没有的信息。" +
            "题干里出现的任何数量要求——字数区间、不少于几点、先判断后说明等格式约束——都必须严格遵守，" +
            "不要压缩，也不要自行放宽。"

    /**
     * 整卷作答的用户提示。
     *
     * `length` 字段只给简答题：模型看到"150-250 字"这种显式区间，比在规则里笼统说一句管用得多。
     */
    fun user(questions: List<Question>): String {
        val arr = buildJsonArray {
            for (q in questions) {
                add(buildJsonObject {
                    put("id", q.qid)
                    put("type", typeName(q.type))
                    // 带上题型标签（"单选题/论述题"），模型对中文题型标签的把握比英文 key 更准
                    if (q.typeLabel.isNotBlank()) put("kind", q.typeLabel)
                    put("stem", q.stem)
                    if (q.options.isNotEmpty()) {
                        put("options", buildJsonArray {
                            q.options.forEach { o ->
                                add("${o.letter.ifBlank { "-" }}. ${o.text}")
                            }
                        })
                    }
                    if (q.type == QType.FILL) put("blanks", q.blanks.coerceAtLeast(1))
                    if (q.type == QType.SHORT) {
                        LengthRules.parse(q.stem)?.let { put("length", it.describe) }
                    }
                })
            }
        }
        return """
            题目（JSON 数组）：
            $arr

            逐题作答，只输出下面这种 JSON，不要任何解释：
            {"answers":[{"id":"题目id","answer":["答案"]}]}

            规则：
            - 所有答案必须依据题干本身作答；选择题必须从给出的选项里选，不要自己造选项
            - single：answer 只放一个选项字母，如 ["A"]
            - multiple：answer 放全部正确字母，按字母升序，如 ["A","C"]
            - judge：answer 只放 "true" 或 "false"（true 表示“正确/对”）
            - fill：按空的顺序放填写内容，一空一个元素；只填答案本身，不要写"第一空"这类字样
            - short：**逐条回应题干的每一问**，写成能直接提交的完整段落，不要只列要点、不要写"综上所述"式空话
            - 题目里带 length 字段的，答案字数必须落在该区间内（按去掉空白后的字符数计算）。
              请写到区间中上部：贴下限容易因数标点差异掉出范围，超上限会被截断
            - 没有 length 的简答题，写 200-300 字
            必须覆盖全部题目，id 必须与题目 id 完全一致。
        """.trimIndent()
    }

    /**
     * 单题重写。
     *
     * 会把"上次写了多少字"直接摊在模型面前——只说"字数不够"它常常还是写不够，
     * 给出具体数字（"你只写了 98 字"）后命中率明显更高。
     */
    fun rewrite(q: Question, rule: LengthRule, previous: String): String = buildString {
        append("题目：").append(q.stem).append("\n\n")
        if (q.options.isNotEmpty()) {
            append("选项：\n")
            q.options.forEach { append(it.letter.ifBlank { "-" }).append(". ").append(it.text).append('\n') }
            append('\n')
        }
        append("请只输出答案正文：不要题号、不要引号、不要解释、不要 Markdown。\n")
        append("要求：紧扣题干作答，不要写通用套话。\n")
        append("字数要求：").append(rule.describe).append("（按去掉空白后的字符数计算）。\n")
        val n = LengthRules.count(previous)
        if (n > 0) {
            append("你上一次写的是 ").append(n).append(" 字，不符合要求。请重写并落在区间内，建议写到 ")
            append(targetWords(rule)).append(" 字左右。")
        } else {
            append("建议写到 ").append(targetWords(rule)).append(" 字左右。")
        }
    }

    /**
     * 建议目标字数：落在区间中上部。
     *
     * 贴下限最容易翻车（标点/空格口径差异就会掉出去），贴上限又容易被 max_tokens 截断，
     * 所以取 60% 位置。
     */
    internal fun targetWords(rule: LengthRule): Int {
        val lo = rule.min
        val hi = rule.max
        return when {
            lo != null && hi != null -> lo + ((hi - lo) * 0.6f).toInt()
            lo != null -> (lo * 1.15f).toInt()
            hi != null -> (hi * 0.85f).toInt()
            else -> 220
        }
    }

    fun typeName(t: QType): String = when (t) {
        QType.SINGLE -> "single"
        QType.MULTIPLE -> "multiple"
        QType.JUDGE -> "judge"
        QType.FILL -> "fill"
        QType.SHORT -> "short"
        QType.UNKNOWN -> "single"
    }
}
