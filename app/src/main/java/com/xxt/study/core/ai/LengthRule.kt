package com.xxt.study.core.ai

/**
 * 题干里隐含的字数要求。
 *
 * 模型经常"写够要点就收笔"，150 字的要求只给 90 字。只靠提示词约束不可靠，
 * 所以这里把要求显式解析出来，答完再验一遍，不达标就单独重写这一题。
 */
data class LengthRule(val min: Int?, val max: Int?) {

    val describe: String
        get() = when {
            min != null && max != null -> "$min-$max 字"
            min != null -> "不少于 $min 字"
            max != null -> "不超过 $max 字"
            else -> "不限"
        }

    fun fits(text: String): Boolean {
        val n = LengthRules.count(text)
        if (min != null && n < min) return false
        if (max != null && n > max) return false
        return true
    }
}

object LengthRules {

    /** "字数"的口径：去掉空白后的字符数（中文习惯） */
    fun count(text: String): Int = text.count { !it.isWhitespace() }

    private val RANGE = Regex("(\\d{2,5})\\s*[-~—－至到]\\s*(\\d{2,5})\\s*字")
    private val AT_LEAST = Regex("(?:不少于|不低于|至少|最少|不能少于)\\s*(\\d{1,5})\\s*字")
    private val AT_MOST = Regex("(?:不超过|不多于|最多|不能多于|不得超过)\\s*(\\d{1,5})\\s*字")
    private val ABOVE = Regex("(\\d{1,5})\\s*字(?:以上|及以上)")
    private val ABOUT = Regex("(\\d{1,5})\\s*字左右")

    /**
     * 解析题干里的字数要求；没有返回 null。
     *
     * 优先读区间（"150-250 字"），其次下限、上限。
     * "左右"按 ±20% 放宽——原文本来就是模糊要求，卡死反而容易误判。
     */
    fun parse(stem: String): LengthRule? {
        if (stem.isBlank()) return null
        RANGE.find(stem)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return@let
            val b = it.groupValues[2].toIntOrNull() ?: return@let
            if (a in 1..99999 && b in 1..99999 && a < b) return LengthRule(a, b)
        }
        val lo = AT_LEAST.find(stem)?.groupValues?.get(1)?.toIntOrNull()
            ?: ABOVE.find(stem)?.groupValues?.get(1)?.toIntOrNull()
        val hi = AT_MOST.find(stem)?.groupValues?.get(1)?.toIntOrNull()
        if (lo != null || hi != null) return LengthRule(lo, hi)

        ABOUT.find(stem)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return LengthRule((n * 0.8).toInt(), (n * 1.2).toInt())
        }
        return null
    }
}
