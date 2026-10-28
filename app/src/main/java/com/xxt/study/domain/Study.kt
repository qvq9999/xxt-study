package com.xxt.study.domain

/**
 * 刷课相关的领域模型。
 *
 * 命名对齐 SuperAutoStudy（CourseUtil / Decode）的字段语义，方便两边对照排查：
 * 同一个坑在 Java 里踩过，Kotlin 这边就不必再踩一遍。
 */

/** 一个章节任务点（知识点）。id 对应学习通里的 knowledgeId，`cur` 前缀已剥掉 */
data class ChapterNode(
    val id: String,
    val title: String,
    val jobCount: Int,
    val locked: Boolean = false,
)

/** 一学期课程的完整章节目录 */
data class CourseTree(
    val nodes: List<ChapterNode>,
    /** 存在未解锁章节：说明课程有先后顺序要求，后面的章节暂时抓不到任务点 */
    val hasLocked: Boolean = false,
) {
    val totalJobs: Int get() = nodes.sumOf { it.jobCount }
}

/** 任务点类型。学习通 attachments[].type 的取值，外加 audio（它是 video 的兄弟结构） */
enum class JobKind {
    VIDEO,
    AUDIO,
    DOCUMENT,
    READ,
    /** 章节测验，type=workid */
    QUIZ,
    UNKNOWN,
    ;

    val label: String
        get() = when (this) {
            VIDEO -> "视频"
            AUDIO -> "音频"
            DOCUMENT -> "文档"
            READ -> "阅读"
            QUIZ -> "测验"
            UNKNOWN -> "其它"
        }
}

/**
 * 单个任务点。
 *
 * 字段含义（对应 knowledge/cards 响应里 attachments[] 的元素）：
 * - [jobId]     任务点 id，进度上报的 jobid 参数
 * - [objectId]  资源对象 id，视频上报的 objectId 参数
 * - [otherInfo] 追加上报参数，视频日志里原样拼进 query
 * - [jtoken]    文档/阅读任务点的鉴权串
 * - [enc]       作业类任务点的 enc
 * - [mid]       转码后媒体 id，视频必填（缺失说明转码失败，无法刷）
 */
data class Job(
    val kind: JobKind,
    val jobId: String,
    val name: String = "",
    val objectId: String = "",
    val otherInfo: String = "",
    val jtoken: String = "",
    val enc: String = "",
    val mid: String = "",
    val aid: String = "",
) {
    /** 有名字用名字，没有就用类型 + id 尾号，保证 UI 上不出现空行 */
    val displayName: String
        get() = name.ifBlank { "${kind.label} ${jobId.takeLast(6)}" }
}

/**
 * cards 页面 defaults 里下发的一组会话参数。
 *
 * [reportInterval] 是服务端期望的上报间隔（秒），比硬编码 60 更贴合服务端节奏。
 */
data class CardDefaults(
    val knowledgeId: String = "",
    val cpi: String = "",
    val ktoken: String = "",
    val cardId: String = "",
    val defenc: String = "",
    val mtEnc: String = "",
    val qnenc: String = "",
    val reportInterval: Int = 60,
)

/** 一个章节的任务点抓取结果 */
data class ChapterJobs(
    val jobs: List<Job>,
    val defaults: CardDefaults,
    /** 章节未开放（还没到时间 / 前一章没学完），本次不处理 */
    val notOpen: Boolean = false,
)

/** 刷课进度里单个任务点的状态 */
enum class JobState {
    WAITING,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED,
}

data class JobProgress(
    val job: Job,
    val state: JobState = JobState.WAITING,
    /** 0..1，仅视频/音频有意义 */
    val fraction: Float = 0f,
    val note: String = "",
)
