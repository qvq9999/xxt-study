package com.xxt.study.core.ai

/**
 * 答案清洗：把"模型复述了要求 / 抄了一遍题干"这类污染挡在回填之前。
 *
 * ## 为什么需要这一层
 *
 * 真实反馈：**AI 作答简答题时，有时会把提示词写进作答框，而不是写答案。**
 * 这不是解析出错——模型确实返回了内容，`answer` 字段里装的是一段像这样的东西：
 *
 * ```
 * 逐条回应题干的每一问，写成能直接提交的完整段落，不要只列要点
 * 字数要求：150-250 字（按去掉空白后的字符数计算）
 * ```
 *
 * 两个高发场景：
 * 1. **单题重写**（字数不达标时触发）。重写的 user 消息里几乎全是要求，
 *    模型一"顺着说"就把要求当成了答案，而这段文字恰好字数达标，
 *    于是被当成合格答案写进作答框——比原来那个偏短的答案还糟。
 * 2. **整卷返回被截断**（max_tokens 用完），JSON 断在半路，模型补的内容退化成规则文本。
 *
 * 所以在"接受答案"这一步统一过一道：
 * 剥掉外壳（代码围栏、引号、"答案："、"（共 xxx 字）"）→
 * 逐行剔掉复述要求的行 → 剩下的还要能通过"是否在复述要求/照抄题干"的判定，否则整条丢弃。
 *
 * **丢弃比写错强**：把提示词填进作答框，学生可能直接提交；而"这题没答案"
 * 至少会被人看见并手动补。
 */
object AnswerSanitizer {

    /**
     * 只可能来自我们提示词的措辞。
     *
     * 刻意只收**特征明显**的句子：像"综上所述""不要解释"这类日常用词不收，
     * 它们在正常的课程答案里也会出现，收了就会把好答案误杀。
     */
    private val OWN_MARKERS = listOf(
        "只输出答案正文",
        "不要题号",
        "不要 Markdown",
        "去掉空白后的字符数",
        "逐条回应题干",
        "区间中上部",
        "超过上限会被截断",
        "你上一次写的是",
        "建议写到",
        "JSON 数组",
        "\"answers\"",
        "answer 只放",
        "id 必须与题目",
    )

    /** 整行就是在说要求（而不是在做答）的开头 */
    private val OWN_LINE_PREFIX = listOf(
        "题目（JSON",
        "选项：",
        "要求：",
        "字数要求",
        "规则：",
        "请只输出",
        "逐题作答",
        "必须覆盖全部题目",
    )

    /**
     * 清洗一条答案。
     *
     * @param raw 模型原文
     * @param stem 题干（用来识别"照抄题目"）；拿不到就传空串，只做要求复述检测
     * @return 可回填的正文；null 表示这条不能用（复述了要求、抄了题干、或剥完什么都不剩）
     */
    fun sanitize(raw: String, stem: String = ""): String? {
        val stripped = strip(raw)
        if (stripped.isBlank()) return null

        // 多行才逐行剔除：单行内容剔掉就什么都不剩了，交给下面的规则判断
        val text = if (stripped.contains('\n')) {
            stripped.lines()
                .filterNot { isOwnLine(it) }
                .joinToString("\n")
                .trim()
        } else {
            stripped
        }
        if (text.isBlank()) return null

        val hits = OWN_MARKERS.count { text.contains(it) }
        // 两条以上要求措辞：这段就是规则本身
        if (hits >= 2) return null
        // 只命一条但整段很短：多半是"请只输出答案正文"这类残句
        if (hits >= 1 && text.length < 80) return null
        if (echoesStem(text, stem)) return null

        return text
    }

    /**
     * 是否照抄了题干。
     *
     * 只在题干足够长（10 字以上）时判断，避免"简述递归"这种短题干把
     * 正常答案（以"递归"开头很自然）误判成照抄。
     */
    fun echoesStem(text: String, stem: String): Boolean {
        val s = stem.replace(WHITESPACE, "")
        if (s.length < 10) return false
        val t = text.replace(WHITESPACE, "")
        if (t.isEmpty()) return false
        if (t == s) return true
        // 答案以题干开头、且题干占了六成以上——这就是把题目抄了一遍
        return t.startsWith(s) && s.length * 10 >= t.length * 6
    }

    /**
     * 剥外壳。
     *
     * 模型常见的包装：代码围栏、"答案："前缀、整体引号、"（共 213 字）"后缀，
     * 而且**顺序会变**（"答案：`…`"、"```json … ```"、"「答案：…」" 都出现过），
     * 所以这里反复剥到稳定为止，而不是按固定顺序走一遍。
     */
    private fun strip(raw: String): String {
        var s = raw.trim()
        repeat(6) {
            val before = s
            s = stripOnce(s)
            if (s == before) return s.trim()
        }
        return s.trim()
    }

    private fun stripOnce(input: String): String {
        var s = input.trim()

        // 1) 代码围栏
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            if (s.trimEnd().endsWith("```")) s = s.trimEnd().dropLast(3)
            s = s.trim()
        }

        // 2) 末尾的字数标注：模型会"贴心地"报一句（共 213 字），可能出现在收尾标点之前
        TRAILING_COUNT.findAll(s).lastOrNull()?.let { m ->
            if (s.length - m.range.last <= 8) s = s.removeRange(m.range).trim()
        }

        // 3) 整体被一对引号包住（允许引号外还跟着句号）
        val core = s.removeSuffix("。").removeSuffix(".").removeSuffix("，").trim()
        for ((l, r) in QUOTES) {
            if (core.length > 2 && core.startsWith(l) && core.endsWith(r)) {
                s = core.substring(1, core.length - 1).trim()
                break
            }
        }

        // 4) 前缀标签，可能叠好几层（"答案：" + "答："）
        for (p in LABEL_PREFIX) {
            if (s.length > p.length && s.startsWith(p)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        return s
    }

    private fun isOwnLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        if (OWN_LINE_PREFIX.any { l.startsWith(it) }) return true
        return OWN_MARKERS.any { l.contains(it) }
    }

    private val QUOTES = listOf(
        "\"" to "\"",
        "“" to "”",
        "「" to "」",
        "『" to "』",
    )

    private val LABEL_PREFIX = listOf(
        "参考答案：",
        "参考答案:",
        "答案：",
        "答案:",
        "【答案】",
        "[答案]",
        "（答案）",
        "答：",
        "答:",
    )

    private val TRAILING_COUNT = Regex("[（(]\\s*(?:共\\s*)?\\d+\\s*字\\s*[)）]")
    private val WHITESPACE = Regex("\\s+")
}
