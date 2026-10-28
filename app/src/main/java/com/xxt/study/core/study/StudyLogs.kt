package com.xxt.study.core.study

/**
 * 按课程隔离的运行日志。
 *
 * 之前只有一份全局日志：刷完 A 课再点开 B 课的刷课页，看到的还是 A 的日志和 A 的计数，
 * 用户会以为 B 已经在跑了。日志属于"某一次具体任务"，必须以课程为键。
 *
 * 抽成纯函数是为了能单测——这里最容易出的错是"切换课程时旧日志没被清掉"，
 * 而它在界面上表现为"数据串了"，很难一眼看出来。
 */
internal object StudyLogs {

    /** 单门课最多留多少条 */
    const val MAX_PER_COURSE = 80

    /** 同时保留几门课的日志，超了淘汰最久没更新的 */
    const val MAX_COURSES = 4

    /**
     * 往 [key] 这门课的日志头部压一条。
     *
     * 更新过的课会被排到最后，"淘汰最久没动的"才是对的——否则切回来一次就把别人挤掉了。
     */
    fun push(
        map: Map<String, List<String>>,
        key: String,
        line: String,
    ): Map<String, List<String>> {
        if (key.isBlank() || line.isBlank()) return map
        val next = (listOf(line) + map[key].orEmpty()).take(MAX_PER_COURSE)
        val out = LinkedHashMap<String, List<String>>()
        map.forEach { (k, v) -> if (k != key) out[k] = v }
        out[key] = next
        while (out.size > MAX_COURSES) {
            val oldest = out.keys.firstOrNull() ?: break
            out.remove(oldest)
        }
        return out
    }

    /** 开始刷某门课前清掉它上一轮的日志：重跑就是新一轮，旧记录只会误导 */
    fun reset(map: Map<String, List<String>>, key: String): Map<String, List<String>> {
        if (key.isBlank() || key !in map) return map
        return map.filterKeys { it != key }
    }
}
