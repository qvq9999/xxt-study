package com.xxt.study.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxt.study.App
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Course
import com.xxt.study.domain.Snapshot
import com.xxt.study.domain.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val snapshot: Snapshot? = null,
    val loading: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val needLogin: Boolean = false,
    /**
     * 这次失败是风控引起的。
     *
     * 有它才敢在错误条上放【重新验证】：否则"验证却被保护期挡住"的时候，
     * 用户只能干等 60 秒冷却，反复点刷新也只会看到同一条冷却提示。
     */
    val verifyBlocked: Boolean = false,

    /** 正在单独刷新的课程（key = courseId:classId） */
    val refreshingCourses: Set<String> = emptySet(),

    /** 单课刷新的最近结果，3 秒后自动消失 */
    val courseNotes: Map<String, CourseNote> = emptyMap(),
)

/** 单课刷新的结果文案：ok 决定用绿色还是红色 */
data class CourseNote(val text: String, val ok: Boolean)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * 置顶课程（持久化在 StudySettings，换页面/重进 App 都还在）。
     *
     * 直接转发 settings 的 StateFlow，不另外存一份——两份状态迟早会对不上。
     */
    val pinned: StateFlow<Set<String>> = container.settings.pinnedCourses

    fun togglePin(key: String): Boolean = container.settings.togglePinned(key)

    init {
        // 先出缓存里的数字，别让用户对着空屏等网络
        _state.update { it.copy(snapshot = container.repo.cached()) }
        refresh()

        /*
         * 任何改动缓存的路径（整页刷新 / 单课刷新 / 登出）都会让 repo 的版本号 +1，
         * 这里跟着重读缓存。
         *
         * 为什么要这一步：课程页里刷新了某一门，缓存里已经是新的，
         * 但首页 StateFlow 里存的是当初那份 Snapshot，不重读就永远显示旧数字——
         * 用户看到的结果就是"里面 3 份、外面 5 份"。
         *
         * drop(1) 跳过订阅时收到的当前值，避免白白重读一次。
         */
        viewModelScope.launch {
            container.repo.revision.drop(1).collect {
                val fresh = container.repo.cached() ?: return@collect
                _state.update { it.copy(snapshot = fresh) }
            }
        }

        // 人工验证通过后自动补扫一遍：失败过的课没进缓存，这次会重新取它们
        viewModelScope.launch {
            VerifyGate.passed.collect {
                if (_state.value.loading) return@collect
                /*
                 * 验证完**不要**以最快的速度重试。
                 *
                 * 服务端刚判完风险，同一个账号立刻又甩一串请求，是最容易被再拦一次的节奏。
                 * 给 1.5 秒缓冲：用户的观感是"回到列表 → 稍微转一下 → 数据来了"，
                 * 而不是"刚点完继续又被拦"。
                 */
                delay(POST_VERIFY_DELAY_MS)
                if (!_state.value.loading) refresh()
            }
        }
    }

    private companion object {
        /** 验证完成到重新扫描之间的缓冲，见 init 里的注释 */
        const val POST_VERIFY_DELAY_MS = 1_500L

        /** 单课刷新结果文案的停留时间 */
        const val COURSE_NOTE_TTL_MS = 3_000L
    }

    /**
     * @param force true = 忽略缓存全量重扫（用户主动点刷新时用）；
     *              false = 12 分钟内扫过的课直接复用，进 App 时走这条
     */
    fun refresh(force: Boolean = false) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, progress = "正在读取课程列表") }
            val result = container.repo.refresh(force) { done, total ->
                _state.update { it.copy(progress = "正在扫描 $done/$total 门课") }
            }
            when (result) {
                is Cx.Ok -> _state.update {
                    it.copy(
                        snapshot = result.data,
                        loading = false,
                        progress = null,
                        error = null,
                        verifyBlocked = false,
                    )
                }
                is Cx.NeedLogin -> _state.update {
                    it.copy(loading = false, progress = null, needLogin = true, verifyBlocked = false)
                }
                is Cx.Fail -> _state.update {
                    it.copy(
                        loading = false,
                        progress = null,
                        error = result.detail,
                        verifyBlocked = result.kind == Cx.Kind.VERIFY,
                    )
                }
            }
        }
    }

    /**
     * 单独刷新一门课（首页每行那个刷新键）。
     *
     * 与课程页里的刷新走的是**同一个 repo 方法**，所以两处结果必然一致：
     * 成功 → 写缓存 + 版本号 +1 → 首页重读；失败 → 只在本行的 note 上说明。
     */
    fun refreshCourse(course: Course) {
        val key = course.key
        if (key in _state.value.refreshingCourses) return
        _state.update {
            it.copy(
                refreshingCourses = it.refreshingCourses + key,
                courseNotes = it.courseNotes - key,
            )
        }
        viewModelScope.launch {
            val r = container.repo.refreshCourse(course)
            val note = when (r) {
                is Cx.Ok -> CourseNote("已更新 ${r.data.works.size} 份作业", ok = true)
                is Cx.Fail -> CourseNote(r.detail, ok = false)
                Cx.NeedLogin -> CourseNote("登录态失效，请重新登录", ok = false)
            }
            _state.update {
                it.copy(
                    refreshingCourses = it.refreshingCourses - key,
                    courseNotes = it.courseNotes + (key to note),
                    // 登录态失效要让首页立刻反映出来，而不是等下次刷新
                    needLogin = it.needLogin || r is Cx.NeedLogin,
                )
            }
            // 结果只留 3 秒：常驻一行字既占地方，也会让人以为状态没更新
            delay(COURSE_NOTE_TTL_MS)
            _state.update { it.copy(courseNotes = it.courseNotes - key) }
        }
    }

    fun logout() = container.repo.logout()
}
