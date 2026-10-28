package com.xxt.study.data.local

import android.content.Context
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Snapshot
import com.xxt.study.domain.Work
import com.xxt.study.domain.WorkStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 上一次刷新结果的本地快照。
 *
 * 用 JSON + SharedPreferences 而不是数据库：数据量就是一屏列表，
 * 引 Room + KSP 只为存几十条记录不划算——APK 更大、编译更慢、还多一个会出错的环节。
 */
class SnapshotCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("study_cache", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Snapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<SnapshotDto>(raw).toDomain() }.getOrNull()
    }

    fun save(snapshot: Snapshot) {
        val raw = runCatching { json.encodeToString(SnapshotDto.from(snapshot)) }.getOrNull()
            ?: return
        prefs.edit().putString(KEY, raw).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // ---------------- 序列化模型 ----------------

    @Serializable
    private data class SnapshotDto(
        val at: Long,
        val totalTodo: Int,
        val variant: Int,
        val courses: List<CourseDto>,
        val note: String? = null,
    ) {
        fun toDomain() = Snapshot(
            at = at,
            totalTodo = totalTodo,
            variant = variant,
            courses = courses.map { it.toDomain() },
            note = note,
        )

        companion object {
            fun from(s: Snapshot) = SnapshotDto(
                at = s.at,
                totalTodo = s.totalTodo,
                variant = s.variant,
                courses = s.courses.map { CourseDto.from(it) },
                note = s.note,
            )
        }
    }

    @Serializable
    private data class CourseDto(
        val courseId: String,
        val classId: String,
        val cpi: String,
        val name: String,
        val todo: Int,
        val error: String? = null,
        /** 旧缓存没有这个字段，默认 0 = 过期，等于强制重扫一次，升级后不会用到脏数据 */
        val at: Long = 0L,
        val works: List<WorkDto> = emptyList(),
    ) {
        fun toDomain() = CourseDigest(
            course = Course(courseId, classId, cpi, name),
            todoCount = todo,
            works = works.map { it.toDomain() },
            error = error,
            at = at,
        )

        companion object {
            fun from(d: CourseDigest) = CourseDto(
                courseId = d.course.courseId,
                classId = d.course.classId,
                cpi = d.course.cpi,
                name = d.course.name,
                todo = d.todoCount,
                error = d.error,
                at = d.at,
                works = d.works.map { WorkDto.from(it) },
            )
        }
    }

    @Serializable
    private data class WorkDto(
        val workId: String,
        val answerId: String,
        val enc: String,
        val title: String,
        val label: String,
        val status: String,
        val url: String,
        /** 旧缓存没有这个字段，默认 null = 不知道截止时间，不影响其它功能 */
        val deadline: Long? = null,
    ) {
        fun toDomain() = Work(
            workId = workId,
            answerId = answerId,
            enc = enc,
            title = title,
            rawLabel = label,
            status = runCatching { WorkStatus.valueOf(status) }.getOrDefault(WorkStatus.UNKNOWN),
            detailUrl = url,
            deadline = deadline,
        )

        companion object {
            fun from(w: Work) = WorkDto(
                workId = w.workId,
                answerId = w.answerId,
                enc = w.enc,
                title = w.title,
                label = w.rawLabel,
                status = w.status.name,
                url = w.detailUrl,
                deadline = w.deadline,
            )
        }
    }

    private companion object {
        const val KEY = "snapshot_v1"
    }
}
