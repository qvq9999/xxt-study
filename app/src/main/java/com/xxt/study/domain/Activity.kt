package com.xxt.study.domain

import kotlinx.serialization.json.JsonArray

/**
 * 学习通「活动」——课程任务栏里的一条（随堂练习 / 签到 / 投票 / 抢答…）。
 *
 * 与 [Job] 的区别：Job 是知识树上的任务点（视频/文档/测验），
 * Activity 是课堂活动流里的条目，接口完全另一套（mobilelearn 的 /v2/apis/active）。
 * 两者不能混在一个模型里：发现方式、状态机、提交接口都不一样。
 */
data class Activity(
    val id: String,
    val name: String,
    /** activeType：42 = 随堂练习（答题页 /page/quiz/stu/answerQuestion2） */
    val type: Int,
    /** pptActive.status：0 未开始 / 1 进行中 / 2 已结束 */
    val status: Int,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val attendCount: Int = 0,
) {
    val isPractice: Boolean get() = type == TYPE_PRACTICE
    val running: Boolean get() = status == 1

    val statusLabel: String
        get() = when {
            status == 1 -> "进行中"
            status == 0 -> "未开始"
            else -> "已结束"
        }

    companion object {
        const val TYPE_PRACTICE = 42
    }
}

/**
 * 活动类型 → 中文名。
 *
 * 活动列表接口里 `name` 字段经常是空的（实测「随堂练习」那条就没有），
 * 直接回退成"活动 42"对用户毫无意义，所以按类型给一个可读的兜底名。
 * 只写确认过的几种，其它保持"活动 x"——猜错名字比显示代号更糟。
 */
fun activityTypeName(type: Int): String = when (type) {
    Activity.TYPE_PRACTICE -> "随堂练习"
    2 -> "签到"
    11 -> "抢答"
    14 -> "问卷"
    35 -> "个人任务"
    43 -> "投票"
    else -> "活动 $type"
}

/** 选择题/判断题的一个选项：letter 是页面上的字母，text 是正文（判断题里是 对/错） */
data class PracticeOption(val letter: String, val text: String)

/**
 * 随堂练习里的一道题。
 *
 * - [type] 学习通题型编码：0 单选 / 1 多选 / 3·16 判断 / 2·9·10 填空·分录·资料 /
 *   4·5·6·7·18 简答·名词解释·论述·计算·口语
 * - [blanks] 填空题的空数（来自服务端 `answer` 数组长度），决定要拆几段填
 * - [integral] 分值，仅用于展示
 */
data class PracticeQuestion(
    val id: String,
    val seq: Int,
    val type: Int,
    val stem: String,
    val options: List<PracticeOption> = emptyList(),
    val blanks: Int = 0,
    val integral: Int = 0,
    /**
     * 服务端已有的作答内容（`personAnswer` 摊平成一段文本）。
     *
     * 重新打开已提交的随堂练习时必须把它填回输入框，否则用户看到空框，
     * 会以为答案丢了、再点一次提交就会覆盖成空。多空填空用换行连接，
     * 与提交时的"每行一空"约定对齐。
     */
    val answerText: String = "",
    /** 服务端标记这道题"已作答"（haveAnswer），即使答案文本没解析出来 */
    val haveAnswer: Boolean = false,
) {
    val answered: Boolean get() = answerText.isNotBlank() || haveAnswer

    val isChoice: Boolean get() = type == 0 || type == 1 || type == 3 || type == 16
    val isBlankFill: Boolean get() = type == 2 || type == 9 || type == 10
}

/**
 * 一份随堂练习的卷面。
 *
 * [raw] 是服务端下发的 `questionlist` 原样 JSON。
 * **提交时必须回传它**（只改每题 `personAnswer`）——页面的做法就是
 * `JSON.stringify(questionList)` 整卷发回，缺字段会被服务端判为格式错误。
 * 所以这里不重建对象，只保留原始数组，避免漏字段。
 */
data class PracticeSheet(
    val activeId: String,
    val name: String,
    val status: Int,
    val questions: List<PracticeQuestion>,
    val raw: JsonArray,
    /**
     * 服务端是否允许改答案（`isStuEditAnswer == 1`）。
     *
     * true 才能走重交；false 时页面只能看不能改。取不到时按 true 处理，
     * 免得因为一个辅助接口失败就把提交按钮关掉。
     */
    val editable: Boolean = true,
) {
    /** 服务端认为这份已经交过（有答案文本，或有 haveAnswer 标记） */
    val serverAnswered: Boolean get() = questions.any { it.answered }

    val typeSummary: String
        get() = questions.groupingBy { q -> q.type }.eachCount()
            .entries.sortedBy { it.key }
            .joinToString("、") { "${typeLabel(it.key)}${it.value}" }
}

/** 题型编码 → 中文。与答题页模板一致，改编码时两边一起改 */
fun typeLabel(type: Int): String = when (type) {
    0 -> "单选"
    1 -> "多选"
    2 -> "填空"
    3, 16 -> "判断"
    4 -> "简答"
    5 -> "名词解释"
    6 -> "论述"
    7 -> "计算"
    9 -> "分录"
    10 -> "资料"
    18 -> "口语"
    else -> "题型$type"
}
