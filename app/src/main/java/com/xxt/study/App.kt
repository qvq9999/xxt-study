package com.xxt.study

import android.app.Application
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.crypto.SecretStore
import com.xxt.study.core.font.QuizFont
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
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
