package com.xxt.study

import android.app.Application
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.crypto.SecretStore
import com.xxt.study.core.font.QuizFont
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.update.UpdateCenter
import com.xxt.study.core.update.UpdateChecker
import com.xxt.study.core.update.UpdateInstaller
import com.xxt.study.core.update.UpdateNotifier
import com.xxt.study.data.local.SnapshotCache
import com.xxt.study.data.remote.chaoxing.ChaoxingClient
import com.xxt.study.data.remote.chaoxing.StudyClient
import com.xxt.study.data.remote.web.WebWorkFetcher
import com.xxt.study.data.repo.DefaultQuizHandler
import com.xxt.study.data.repo.QuizSolver
import com.xxt.study.data.repo.StudyRepo
import com.xxt.study.core.study.StudyController
import com.xxt.study.core.study.StudySettings

class App : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        /*
         * ⚠️ 这里**不再**"版本升级清一次快照缓存"（0.6.0 加过，0.7.1 撤回）。
         *
         * 当时的想法是：状态判定口径变了，旧缓存里存的是旧口径的结果，清掉重扫能让
         * 状态立刻正确。但漏算了一件事——**"全量重扫"本身就是最容易触发风控的行为**：
         * 41 门课 × 两跳导航 = 82 个页面请求，短时间密集打上去，几乎必被拦。
         * 平时增量刷新只扫 0~2 门，用户从来不会遇到；一升级就清缓存 → 必扫 41 门 → 必被风控。
         * 结果就是"每次更新完，一打开就卡在验证码页"，比红点多几个难看得多。
         *
         * 现在的取舍：**不清缓存**。
         * - 作业状态（可提交/已截止/待批阅）是运行时用 `Work.view()` 算的，旧缓存照样算得对；
         * - 唯一受影响的是课程结课标记（旧缓存没有这个字段，默认 false），
         *   会在下一次增量刷新（12 分钟保鲜期后）自然修正，多几个红点但不影响使用。
         *
         * 结论：**不为了一次性正确去换一次必然的风控。**
         */
        container = Container(this)
    }
}

/**
 * 手写依赖容器，替代 Hilt。
 * 依赖少、生命周期跟 Application 一致，没必要为十来个对象引注解处理器。
 *
 * 这里只有一份 Http：刷课和查作业共用同一条串行通道，
 * 保证同一时间对学习通只有一个请求在飞（风控看的是这个）。
 */
class Container(app: Application) {

    private val secrets = SecretStore(app)
    private val http = Http(CookieStore(secrets))

    val cookies: CookieStore get() = http.cookies

    val repo: StudyRepo = StudyRepo(
        client = ChaoxingClient(http),
        webFetcher = WebWorkFetcher(app.applicationContext),
        cache = SnapshotCache(app),
        cookies = http.cookies,
    )

    val studyClient: StudyClient = StudyClient(http)

    val ai: AiClient = AiClient(secrets, http)

    /**
     * 更新检查用独立的 OkHttpClient：它打的是 GitHub / 镜像站，
     * 不能共用上面那条带学习通 Cookie 的串行通道。
     */
    val updater: UpdateChecker = UpdateChecker(app)

    val installer: UpdateInstaller = UpdateInstaller(app)

    /**
     * 更新任务中枢。
     *
     * 生命周期挂在这里（Application）而不是某个页面——用户关掉更新弹窗去刷课，
     * 下载必须继续；下载完成还要能发通知把他叫回来。
     *
     * 通知的"点一下打开"意图也在这里注入，core 层不认识 MainActivity。
     */
    private val updateNotifier = UpdateNotifier(app) {
        android.content.Intent(app, MainActivity::class.java).apply {
            putExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    val updateCenter: UpdateCenter = UpdateCenter(
        appContext = app.applicationContext,
        checker = updater,
        installer = installer,
        notifier = updateNotifier,
    )

    private val quizFont = QuizFont(app.assets)

    val settings = StudySettings(app)

    /**
     * 测验处理器按需构造：字体映射表 1.5 MB，不做测验就不该付这份内存。
     * 关掉「自动答测验」时返回 null，StudyRunner 只会取卷面不再提交。
     */
    val study: StudyController = StudyController(
        context = app.applicationContext,
        client = studyClient,
        settings = settings,
        quizHandlerProvider = {
            DefaultQuizHandler(
                client = studyClient,
                font = quizFont,
                solver = QuizSolver(ai),
            )
        },
    )
}
