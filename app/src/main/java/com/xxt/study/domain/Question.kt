package com.xxt.study.domain

/**
 * 题型。
 * 学习通页面上的判定依据是 `div.questionLi[typename]`（"单选题/多选题/判断题/填空题/论述题"），
 * 隐藏域 `answertype<qid>` 的值可作交叉校验：0 单选 / 1 多选 / 2 填空 / 3 判断。
 */
enum class QType {
    SINGLE,
    MULTIPLE,
    JUDGE,
    FILL,
    SHORT,
    UNKNOWN,
    ;

    /** 有选项可点的题型 */
    val hasOptions: Boolean get() = this == SINGLE || this == MULTIPLE || this == JUDGE

    /** 需要写文字的题型 */
    val isTextInput: Boolean get() = this == FILL || this == SHORT
}

/**
 * 一个选项。
 *
 * [letter] 是用户看到的字母（A/B/C/D）；
 * [value] 是 `span.num_option[data]` 的值——选择题里与 letter 相同，
 * **判断题里是 "true"/"false"**（页面显示 A/B，正文写"对/错"）。
 * 所以判断题要按 [text] 归一成对/错，不能拿 letter 当答案。
 */
data class QOption(
    val letter: String,
    val value: String,
    val text: String,
)

/** 一道题。qid 来自 `div.questionLi[data]`，是回填与提交的唯一锚点 */
data class Question(
    val qid: String,
    val number: Int,
    val type: QType,
    val typeLabel: String,
    val stem: String,
    val options: List<QOption>,
    val blanks: Int,
) {
    /** 给 AI 的选项摘要，格式稳定便于校验答案 */
    fun optionsForPrompt(): String =
        options.joinToString("\n") { "${it.letter}. ${it.text}" }
}
