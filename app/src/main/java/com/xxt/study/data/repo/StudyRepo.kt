package com.xxt.study.data.repo

import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.result.Cx
import com.xxt.study.data.local.SnapshotCache
import com.xxt.study.data.remote.chaoxing.ChaoxingClient
import com.xxt.study.data.remote.web.WebWorkFetcher
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.domain.Snapshot
import com.xxt.study.domain.Work
import com.xxt.study.domain.isActionable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * 唯一允许"缓存 + 网络"混合决策的层。
 *
 * 刷新策略：逐课串行，单课失败不影响其他课，把失败原因记在该课条目上而不是整次失败。
 * 这样用户看到的是"10 门课里 9 门有数据，1 门暂时取不到"，而不是一片空白。
 */
class StudyRepo(
    private val client: ChaoxingClient,
    private val webFetcher: WebWorkFetcher,
    private val cache: SnapshotCache,
    private val cookies: CookieStore,
) {

    suspend fun session(): Cx<Session> = client.checkSession()

    fun cached(): Snapshot? = cache.load()

    fun loggedIn(): Boolean = cookies.loggedIn()

    fun logout() {
        cookies.clear()
        cache.clear()
        // 隐藏 WebView 自带一份 Cookie，只清自己的不够——不清就会出现
        // "登出后换个账号，看到的还是上一个人的作业"
        webFetcher.clearCookies()
        webFetcher.shutdown()
    }

    /**
     * 刷新。
     *
     * 三层提速，从大到小：
     * 1. **增量**：非 [force] 时，[REUSE_TTL_MS] 内已经取过的课直接复用缓存里的作业列表，
     *    一个请求都不发。进 App 的自动刷新走这条路，通常是秒开。
     * 2. **并发**：需要真正去取的课并发执行（上限 = [WebWorkFetcher] 的池容量）。
     * 3. **单课内部**：拦静态资源 + 等目标元素而不是等 onPageFinished，见 WebWorkFetcher。
     *
     * [force] = true 只在用户主动点刷新时用，含义是"我说的最新，别拿缓存糊弄我"。
     *
     * @param onProgress (已完成课数, 总课数)
     */
    suspend fun refresh(
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Cx<Snapshot> {
        val session = when (val s = client.checkSession()) {
            is Cx.Ok -> s.data
            is Cx.NeedLogin -> return Cx.NeedLogin
            is Cx.Fail -> return s
        }

        val courses = when (val c = client.courses(session.fid)) {
            is Cx.Ok -> c.data
            is Cx.NeedLogin -> return Cx.NeedLogin
            is Cx.Fail -> return c
        }

        if (courses.isEmpty()) {
            val empty = Snapshot(System.currentTimeMillis(), 0, emptyList())
            cache.save(empty)
            return Cx.Ok(empty)
        }

        val now = System.currentTimeMillis()
        val total = courses.size

        // 能复用的课：上次成功、且没超过保鲜期。失败过的必须重试。
        val reusable = HashMap<String, CourseDigest>()
        if (!force) {
            val previous = cache.load()?.courses?.associateBy { it.key }.orEmpty()
            for (course in courses) {
                val key = "${course.courseId}:${course.classId}"
                val old = previous[key] ?: continue
                if (old.ok && old.at > 0 && now - old.at in 0 until REUSE_TTL_MS) {
                    reusable[key] = old
                }
            }
        }

        val toFetch = courses.filter { "${it.courseId}:${it.classId}" !in reusable }
        val finished = AtomicInteger(reusable.size)
        onProgress(finished.get(), total)

        val fetched: Map<String, Cx<List<Work>>> = coroutineScope {
            toFetch.map { course ->
                async {
                    val r = webFetcher.fetch(course)
                    onProgress(finished.incrementAndGet(), total)
                    "${course.courseId}:${course.classId}" to r
                }
            }.awaitAll()
        }.toMap()

        // 任意一门判定掉登录，整次刷新按掉登录处理（比继续扫完 40 门更省时间）
        if (fetched.values.any { it is Cx.NeedLogin }) return Cx.NeedLogin

        /*
         * 失败不再在这里重试。
         *
         * [WebWorkFetcher.fetch] 内部已经做了"换一个干净 WebView 再试一次"的兜底，
         * 而且那一层能真正换掉浏览上下文；在这里用同一个池子重来一遍，
         * 只是把同样的失败再跑一次，白白多花十几秒。
         */

        var todoTotal = 0
        val digests = courses.map { course ->
            val key = "${course.courseId}:${course.classId}"
            reusable[key]?.let { old ->
                todoTotal += old.todoCount
                return@map old
            }
            when (val r = fetched[key]) {
                is Cx.Ok -> {
                    val todo = r.data.count { it.isActionable(now) }
                    todoTotal += todo
                    CourseDigest(course, todo, r.data, null, at = now)
                }
                is Cx.Fail -> CourseDigest(course, 0, emptyList(), r.detail, at = 0)
                // 上面已拦掉，这里只为穷尽分支
                Cx.NeedLogin -> CourseDigest(course, 0, emptyList(), "登录态失效", at = 0)
                null -> CourseDigest(course, 0, emptyList(), "未取到结果", at = 0)
            }
        }

        val snapshot = Snapshot(
            at = now,
            totalTodo = todoTotal,
            note = if (client.lastCoursesDegraded) {
                "课程列表来自备用源（JSON 未取到），个别课程可能缺参数、点不开"
            } else {
                null
            },
            courses = digests.sortedWith(
                compareByDescending<CourseDigest> { it.todoCount }.thenBy { it.course.name }
            ),
        )
        cache.save(snapshot)
        return Cx.Ok(snapshot)
    }

    private companion object {
        /**
         * 缓存保鲜期。
         *
         * 12 分钟：比学习通 enc 的会话寿命略短，避免复用到已经失效的参数。
         * 作业状态本来也不是秒级变化，这个窗口足够。
         */
        const val REUSE_TTL_MS = 12 * 60 * 1000L

    }
}
