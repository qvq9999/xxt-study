package com.xxt.study.domain

/**
 * 课程四要素：学习通所有接口都用这四个定位一门课。
 */
data class Course(
    val courseId: String,
    val classId: String,
    val cpi: String,
    val name: String,
    /**
     * 课程是否已结课 / 已归档。
     *
     * 判定来源是课程列表 JSON 的两个字段：
     * `content.state == 1` 或 `course.data[0].coursestate == 1`。
     *
     * 41 门真实样本里只有 5 门命中，且全是旧学期课（羽毛球、军事理论、
     * 思想道德与法治…），而 23 级、Java 高级编程这些正在上的课都是 0 ——
     * 语义方向可信。
     *
     * 它只做一件事：结课课程里"显示未交"的作业按已截止处理，不再挂红点。
     * 这是"已结课仍显示未完成作业"的直接修法。
     */
    val closed: Boolean = false,
    /**
     * 课程创建时间（epoch 毫秒），来自 `course.data[0].createtime`。
     *
     * 用来决定**先扫哪几门**。学习通不给"最近访问时间"（41 门样本里翻遍了，
     * 只有这一个时间字段；`cataName` 全是"课程"、`norder` 只是用户拖拽排序），
     * 所以"新开的课"是现有数据里最好的近似。
     *
     * 0 表示未知（旧缓存或字段缺失），排序时排在后面。
     */
    val createdAt: Long = 0L,
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

/**
 * 服务端给的状态（已归一）。
 *
 * 只保留"服务端说了什么"，不掺任何时间判断——
 * 时间相关的结论全部交给 [WorkView]，两边混在一起时，
 * "已截止"到底是谁判的就说不清了。
 */
enum class WorkStatus {
    /** 未交（学习通对过期未交也常写"未交"，所以这里不代表还能交） */
    PENDING,
    /** 已交，等批阅 */
    SUBMITTED,
    /** 已批阅 / 已完成 */
    GRADED,
    /** 服务端明说已过期 / 已截止 */
    OVERDUE,
    /** 词表没认出来 */
    UNKNOWN,
}

/**
 * 界面上真正要展示的状态。
 *
 * 为什么要在 [WorkStatus] 之外再造一层：
 *
 * 1. 学习通对**过期未交**的作业照样写"未交"，只看服务端状态会把一堆
 *    交不上的作业永远挂在待办里（"已截止仍显示未提交"就是这么来的）；
 * 2. **已结课**的课程里全是这种历史遗留"未交"，它们会污染首页红点
 *    （"已结课仍显示未完成作业"）；
 * 3. 「剩余0分钟」这种写法表示已经截止，但状态词还是"未交"。
 *
 * 所以最终形态 = 服务端状态 × 截止时间 × 课程是否结课 × 当前时间。
 * 这个函数是纯的，能单测，也是全App唯一一处判定口径。
 */
enum class WorkView {
    /** 已批阅 / 已完成 */
    DONE,

    /** 已交，待批阅 */
    SUBMITTED,

    /** 还能动手交 */
    OPEN,

    /** 交不了了：已过截止时间，或课程已结课 */
    EXPIRED,

    /** 服务端状态没认出来——按"可能要交"处理，宁可多提醒一次 */
    UNKNOWN,
    ;

    /**
     * 计不计入首页红点 / "待提交"分组。
     *
     * [UNKNOWN] 也算：词表漏掉一种新状态时，用户宁可被多提醒一次，
     * 也不该整条作业从待办里消失。
     */
    val countsAsTodo: Boolean get() = this == OPEN || this == UNKNOWN
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
     * 解析不出来就是 null（有些作业没有截止时间，有些是"剩余0分钟"已经截止）。
     */
    val deadline: Long? = null,
)

/**
 * 这份作业现在应该显示成什么。
 *
 * @param courseClosed 所属课程是否已结课。结课时"未交"一律按已截止，
 *                     因为结课后补交通道已经关了，挂着红点只会让人白跑一趟。
 */
fun Work.view(now: Long, courseClosed: Boolean = false): WorkView = when (status) {
    WorkStatus.GRADED -> WorkView.DONE
    WorkStatus.SUBMITTED -> WorkView.SUBMITTED
    WorkStatus.OVERDUE -> WorkView.EXPIRED
    WorkStatus.PENDING -> when {
        // 知道截止时间就以时间为准（缓存 12 分钟后再看也不会算错）
        deadline != null -> if (now >= deadline) WorkView.EXPIRED else WorkView.OPEN
        // 不知道截止时间、课程又结了：按已截止，不打扰
        courseClosed -> WorkView.EXPIRED
        // 不知道截止时间、课程还在上：保守提醒
        else -> WorkView.OPEN
    }
    WorkStatus.UNKNOWN -> if (courseClosed) WorkView.EXPIRED else WorkView.UNKNOWN
}

/**
 * 这份作业现在还能不能交（= 该不该进待办）。
 *
 * @param courseClosed 结课课程见的"未交"是历史遗留，不算待办。
 */
fun Work.isActionable(now: Long, courseClosed: Boolean = false): Boolean =
    view(now, courseClosed).countsAsTodo

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
