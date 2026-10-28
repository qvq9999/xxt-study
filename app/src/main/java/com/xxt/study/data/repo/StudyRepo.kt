package com.xxt.study.data.repo

import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.result.Cx
import com.xxt.study.data.local.SnapshotCache
import com.xxt.study.data.remote.chaoxing.ChaoxingClient
import com.xxt.study.data.remote.web.WebWorkFetcher
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.domain.Snapshot
import com.xxt.study.domain.Work
import com.xxt.study.domain.isActionable
import com.xxt.study.domain.key
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 每门课各自的上次刷新时间（key = `courseId:classId`）。
     *
     * 以前是**一个全局时间戳**，结果在首页给 A 课点了刷新之后，
     * 10 秒内给 B 课点也会被拒——两门不相干的课被互相牵连，用户只会觉得"按钮坏了"。
     * 节流的意义是防连点同一门，按课分开才符合直觉。
     */
    private val lastCourseRefreshAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 快照版本号：**任何**改动缓存的路径（整页刷新 / 单课刷新 / 登出清缓存）都 +1。
     *
     * 界面侧（首页）订阅它，一有变化就重读缓存。
     * 这是"课程页刷新后首页数字必须跟着变"的唯一可靠做法——
     * 首页的 StateFlow 里存的是当初那份 Snapshot 对象的拷贝，
     * 不重新读，它就永远停在旧数字上。
     */
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    suspend fun session(): Cx<Session> = client.checkSession()

    fun cached(): Snapshot? = cache.load()

    /**
     * 刷新**单门课**的作业列表（课程页那个刷新键用）。
     *
     * ## 为什么这个不会触发风控
     *
     * 一门课只有两跳导航（课程中间页 + 作业列表），共 2 个请求，
     * 和"一次扫 12 门 = 24 个请求"完全不是一个量级。
     *
     * 但它最容易被连点——用户看状态没变就反复戳。所以这里做**硬节流**：
     * [COURSE_REFRESH_GAP_MS] 之内直接拒绝，不发任何请求。
     * 挡在数据层而不是只靠界面禁用，是因为界面状态可能被重建（旋转、切页）。
     */
    suspend fun refreshCourse(course: Course): Cx<CourseDigest> {
        VerifyGate.cooldownText()?.let { return Cx.Fail(Cx.Kind.VERIFY, it) }

        val now = System.currentTimeMillis()
        val key = course.key
        val gap = now - (lastCourseRefreshAt[key] ?: 0L)
        if (gap in 0 until COURSE_REFRESH_GAP_MS) {
            val wait = (COURSE_REFRESH_GAP_MS - gap + 999) / 1000
            return Cx.Fail(Cx.Kind.RATE_LIMITED, "这门课 ${wait} 秒内刚刷过，稍等一下再点（避免触发风控）")
        }
        lastCourseRefreshAt[key] = now

        return when (val r = webFetcher.fetch(course)) {
            is Cx.Ok -> {
                val todo = r.data.count { it.isActionable(now, course.closed) }
                val digest = CourseDigest(course, todo, r.data, null, at = now)
                mergeIntoSnapshot(digest)
                Cx.Ok(digest)
            }
            is Cx.Fail -> r
            Cx.NeedLogin -> Cx.NeedLogin
        }
    }

    /**
     * 把单门课的新结果合并回整份快照。
     *
     * 这样首页那个大数字也会跟着变——用户在课程页刷完一看，退回去数字是对的，
     * 不用再整页重扫一遍。
     */
    private fun mergeIntoSnapshot(digest: CourseDigest) {
        val old = cache.load()
            ?: Snapshot(System.currentTimeMillis(), 0, emptyList())
        val exists = old.courses.any { it.key == digest.key }
        val courses = if (exists) {
            old.courses.map { if (it.key == digest.key) digest else it }
        } else {
            old.courses + digest
        }
        cache.save(
            old.copy(
                at = System.currentTimeMillis(),
                totalTodo = courses.sumOf { it.todoCount },
                courses = courses.sortedWith(
                    compareByDescending<CourseDigest> { it.todoCount }.thenBy { it.course.name },
                ),
            ),
        )
        // 单课刷新也改了缓存，通知界面重读
        bumpRevision()
    }

    fun loggedIn(): Boolean = cookies.loggedIn()

    fun logout() {
        cookies.clear()
        cache.clear()
        bumpRevision()
        // 隐藏 WebView 自带一份 Cookie，只清自己的不够——不清就会出现
        // "登出后换个账号，看到的还是上一个人的作业"
        webFetcher.clearCookies()
        webFetcher.shutdown()
    }

    /**
     * 刷新。
     *
     * 三层提速 + 一层限流，从大到小：
     * 1. **增量**：非 [force] 时，[REUSE_TTL_MS] 内已经取过的课直接复用缓存里的作业列表，
     *    一个请求都不发。进 App 的自动刷新走这条路。
     * 2. **限流**：单次最多真正去取 [maxCoursesPerScan] 门（默认 12）。
     *    这是防风控最关键的一条——41 门全扫 ≈ 82 个页面请求，短时间打上去几乎必被拦。
     *    没扫到的课**保留旧数据**，下次刷新按"最久没更新"优先轮转补齐。
     * 3. **并发**：需要真正去取的课并发执行（上限 = [WebWorkFetcher] 的池容量）。
     * 4. **单课内部**：拦静态资源 + 等目标元素而不是等 onPageFinished，见 WebWorkFetcher。
     *
     * [force] = true 只在**长按刷新**时用，含义是"全量重扫，别拿缓存糊弄我"：
     * 忽略增量、也放开单次上限。这是用户明确要求的行为，撞风控他自己承担。
     *
     * @param maxCoursesPerScan 单次最多真取几门；[Int.MAX_VALUE] 表示不限
     * @param onProgress (已完成课数, 本次要处理的课数)
     */
    suspend fun refresh(
        force: Boolean = false,
        maxCoursesPerScan: Int = DEFAULT_MAX_PER_SCAN,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Cx<Snapshot> {
        // 冷却期内不碰服务端：连 checkSession 都不发，免得把计数越推越高
        VerifyGate.cooldownText()?.let { return Cx.Fail(Cx.Kind.VERIFY, it) }

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

        // 上次的快照：既能拿来做增量复用，也能在本次撞风控时兜住旧数据
        val previous = cache.load()?.courses?.associateBy { it.key }.orEmpty()

        // 能复用的课：上次成功、且没超过保鲜期。失败过的必须重试。
        val reusable = HashMap<String, CourseDigest>()
        if (!force) {
            for (course in courses) {
                val key = "${course.courseId}:${course.classId}"
                val old = previous[key] ?: continue
                if (old.ok && old.at > 0 && now - old.at in 0 until REUSE_TTL_MS) {
                    reusable[key] = old
                }
            }
        }

        val pending = courses.filter { "${it.courseId}:${it.classId}" !in reusable }

        /*
         * 限流：单次最多真取 maxCoursesPerScan 门。
         *
         * 排序三条，依次是：
         * 1. **未结课优先**——结课课程的后补交通道已经关了，不可能有要交的作业；
         * 2. **创建时间降序**——学习通只给得出 `createtime`，没有"最近访问"，
         *    所以"新开的课"是现有数据里对"近期有作业"最好的近似。
         *    实测 41 门课按这个排，前 12 门全是本学期的，2022~2024 的旧课自动沉底；
         * 3. **上次成功时间升序**——同一批里最久没更新的先扫，保证轮转公平，
         *    不会出现"永远是前 12 门最新、后面 29 门停在三天前"。
         */
        val toFetch = if (force || pending.size <= maxCoursesPerScan) {
            pending
        } else {
            pending.sortedWith(
                compareBy<Course> { if (it.closed) 1 else 0 }
                    .thenByDescending { it.createdAt }
                    .thenBy { previous["${it.courseId}:${it.classId}"]?.at ?: 0L },
            ).take(maxCoursesPerScan)
        }
        val deferred = pending.size - toFetch.size

        val finished = AtomicInteger(reusable.size)
        onProgress(finished.get(), reusable.size + toFetch.size)

        /*
         * 风控熔断。
         *
         * 池容量是 1，所以这些 async 实际是**串行**跑的。一旦第一门撞上风控，
         * 后面的每一门还会依次：等 250~650ms 的随机间隔 → 发现冷却中 → 返回失败。
         * 白白多等十几秒，界面一直转圈，用户以为卡死。
         *
         * 主观看还有个更糟的后果：**每门课都被标成"失败"**，首页变成一屏红字，
         * 看起来像软件坏了。实际上只是"这一次没扫到"。
         */
        val aborted = AtomicBoolean(false)

        val fetched: Map<String, Cx<List<Work>>> = coroutineScope {
            toFetch.map { course ->
                async {
                    val key = "${course.courseId}:${course.classId}"
                    if (aborted.get()) {
                        onProgress(finished.incrementAndGet(), reusable.size + toFetch.size)
                        return@async key to Cx.Fail(Cx.Kind.VERIFY, "已因风控中止本次扫描")
                    }
                    val r = webFetcher.fetch(course)
                    if (r is Cx.Fail && r.kind == Cx.Kind.VERIFY) aborted.set(true)
                    onProgress(finished.incrementAndGet(), reusable.size + toFetch.size)
                    key to r
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
                /*
                 * 复用缓存时**重算**待办数，而不是照抄上次的数字。
                 *
                 * 保鲜期内完全可能跨过某个截止时间；照抄旧数字的话，
                 * 用户会在"已经交不上了"的作业上继续看到红点，直到下次真正重扫。
                 */
                val recount = old.works.count { it.isActionable(now, old.course.closed) }
                todoTotal += recount
                return@map if (recount == old.todoCount) old else old.copy(todoCount = recount)
            }
            when (val r = fetched[key]) {
                is Cx.Ok -> {
                    // 结课课程的"未交"是历史遗留，不计入待办（否则首页永远清不干净）
                    val todo = r.data.count { it.isActionable(now, course.closed) }
                    todoTotal += todo
                    CourseDigest(course, todo, r.data, null, at = now)
                }
                is Cx.Fail -> {
                    /*
                     * 没取到的课：**有旧数据就先用旧数据**，别把错误甩到界面上。
                     *
                     * 撞风控中止、或者排队没轮到，都不代表这门课有问题。
                     * 用户要的是一份"稍旧的清单"，而不是一屏"暂时取不到"。
                     * 旧数据 at 不更新，所以下次刷新还会重试。
                     */
                    val old = previous[key]
                    if (old != null && old.works.isNotEmpty()) {
                        val recount = old.works.count { it.isActionable(now, old.course.closed) }
                        todoTotal += recount
                        old.copy(todoCount = recount)
                    } else {
                        CourseDigest(course, 0, emptyList(), r.detail, at = 0)
                    }
                }
                // 上面已拦掉，这里只为穷尽分支
                Cx.NeedLogin -> CourseDigest(course, 0, emptyList(), "登录态失效", at = 0)
                null -> {
                    // 这次没轮到它（单次限流），沿用上次的数据
                    val old = previous[key]
                    if (old != null) {
                        val recount = old.works.count { it.isActionable(now, old.course.closed) }
                        todoTotal += recount
                        old.copy(todoCount = recount)
                    } else {
                        CourseDigest(course, 0, emptyList(), "本次未扫描到", at = 0)
                    }
                }
            }
        }

        val notes = buildList {
            if (client.lastCoursesDegraded) {
                add("课程列表来自备用源（JSON 未取到），个别课程可能缺参数、点不开")
            }
            if (deferred > 0 && !force) {
                add("还有 $deferred 门课数据较旧，下次刷新会继续更新（一次只扫 $DEFAULT_MAX_PER_SCAN 门，避免触发风控）")
            }
        }

        val snapshot = Snapshot(
            at = now,
            totalTodo = todoTotal,
            note = notes.joinToString("；").ifBlank { null },
            courses = digests.sortedWith(
                compareByDescending<CourseDigest> { it.todoCount }.thenBy { it.course.name }
            ),
        )
        cache.save(snapshot)
        bumpRevision()
        return Cx.Ok(snapshot)
    }

    private companion object {
        /**
         * 缓存保鲜期：30 分钟。
         *
         * 原来是 12 分钟，理由是"比 enc 的会话寿命略短"。但这个理由站不住：
         * 复用缓存时**根本不会再发请求**，压根用不到 enc。12 分钟的副作用是
         * "用户隔十几分钟打开一次，41 门课全部过期 → 全量重扫 → 触发风控"。
         *
         * 作业状态本身也不是秒级变化，而且"能不能交/是否已截止"是运行时用
         * `Work.view()` 现算的，缓存放久一点不会把状态算错。30 分钟是折中。
         */
        const val REUSE_TTL_MS = 30 * 60 * 1000L

        /**
         * 单次刷新最多真取几门课。
         *
         * 12 门 ≈ 24 个页面请求 ≈ 40 秒，这个量级不会让风控警觉；
         * 41 门全扫则几乎必被拦。没扫到的课保留旧数据，下次按"最久没更新"轮转。
         */
        const val DEFAULT_MAX_PER_SCAN = 12

        /**
         * 单门课刷新的最小间隔。
         *
         * 10 秒看起来有点长，但这门课刷一次就是 2 个真实页面请求；
         * 用户看状态没变时会连续戳，10 次就是 20 个请求 —— 足够把风控惹出来。
         * 宁可让他等 10 秒，也不要他被验证码拦住 5 分钟。
         */
        const val COURSE_REFRESH_GAP_MS = 10_000L
    }
}
