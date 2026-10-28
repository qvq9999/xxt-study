package com.xxt.study.domain

/** 课程四要素：学习通所有接口都用这四个定位一门课 */
data class Course(
    val courseId: String,
    val classId: String,
    val cpi: String,
    val name: String,
)

/**
 * 课程唯一键，与 [CourseDigest.key] 同一写法。
 *
 * 刷课的日志、断点都按它分组——单独一份"课程 A 的日志"必须能唯一查到。
 */
val Course.key: String get() = "$courseId:$classId"

/**
 * 课程中间页下发的 enc 包。
 * 注意：作业列表的 enc= 要用 workEnc，stuenc= 才用 enc，两者来源互换是最大的坑。
 */
data class EncPack(
    val enc: String,
    val openc: String,
    val workEnc: String,
    val examEnc: String,
    val fetchedAt: Long,
) {
    val usable: Boolean get() = workEnc.isNotBlank()
}

enum class WorkStatus {
    /** 未交（含已过期未交）：计入首页未完成数 */
    PENDING,
    /** 已交待批阅：不计入 */
    SUBMITTED,
    /** 已批阅 / 已完成：不计入 */
    GRADED,
    OVERDUE,
    UNKNOWN,
    ;

    val countsAsTodo: Boolean get() = this == PENDING
}

/** 作业条目：data 直链里已带独立 enc，详情页直接用它 */
data class Work(
    val workId: String,
    val answerId: String,
    val enc: String,
    val title: String,
    val rawLabel: String,
    val status: WorkStatus,
    val detailUrl: String,
    /**
     * 截止时间（epoch 毫秒），从列表页 aria-label 的「剩余394小时23分钟」换算而来。
     *
     * 存绝对时间而不是"剩余多少"，这样缓存 12 分钟后再打开仍然准——
     * 剩余量会随时间自己变少，不需要重新请求。
     * 解析不出来就是 null（有些作业没有截止时间）。
     */
    val deadline: Long? = null,
)

/**
 * 这份作业现在还能不能交。
 *
 * 红点只提示"还能动手的"——已经截止的作业点了也交不上，
 * 拿它去打扰用户只会让人以为还有事没做。
 * deadline 未知时按"能交"处理（保守，宁可多提醒一次）。
 */
fun Work.isActionable(now: Long): Boolean =
    status == WorkStatus.PENDING && (deadline == null || now < deadline)

data class WorkDetail(
    val questionCount: String? = null,
    val fullScore: String? = null,
    val score: String? = null,
    val timeRange: String? = null,
    val endAt: String? = null,
)

/** 一次刷新的结果：成功拿到几门课、各自几份待办 */
data class CourseDigest(
    val course: Course,
    val todoCount: Int,
    val works: List<Work>,
    val error: String? = null,
    /** 这门课的作业列表是什么时候取到的；增量刷新靠它判断能不能复用 */
    val at: Long = 0L,
) {
    val ok: Boolean get() = error == null

    /** 增量扫描的复用键：课程 id + 班级 id 唯一确定一门课 */
    val key: String get() = "${course.courseId}:${course.classId}"
}

/** 整次刷新的快照，用于首页展示与离线兜底 */
data class Snapshot(
    val at: Long,
    val totalTodo: Int,
    val courses: List<CourseDigest>,
    val variant: Int = 0,
    /** 本次扫描的降级说明（如"课程列表来自备用源"），正常为 null */
    val note: String? = null,
) {
    val failedCount: Int get() = courses.count { !it.ok }
    val courseCount: Int get() = courses.size
}
