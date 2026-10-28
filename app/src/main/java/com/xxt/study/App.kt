package com.xxt.study

import android.app.Application
import android.content.Context
import android.os.Build
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.crypto.SecretStore
import com.xxt.study.core.font.QuizFont
import com.xxt.study.core.net.CookieStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.update.UpdateChecker
import com.xxt.study.core.update.UpdateInstaller
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
         * 版本升级后清一次快照缓存。
         *
         * 用户明确选了这个代价：状态判定口径改了（结课 / 已截止 / 剩余0分钟），
         * 旧缓存里存的是按旧口径算出来的 status 和 todoCount，
         * 留着它会让升级后的第一次打开**仍然是错的**，而且还要等 12 分钟保鲜期过去。
         *
         * 清掉之后第一次打开会全量重扫（41 门课，几十秒），换来状态立刻正确。
         * 登录态、AI Key、刷课设置都存在别的地方，一概不动。
         */
        purgeSnapshotOnUpgrade()
        container = Container(this)
    }

    private fun purgeSnapshotOnUpgrade() {
        val prefs = getSharedPreferences(META, Context.MODE_PRIVATE)
        val current = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        }.getOrDefault(-1L)
        if (current <= 0) return

        val last = prefs.getLong(KEY_LAST_VERSION, -1L)
        if (last == current) return

        // 首次安装也会走到这条：本来就没缓存，清一次无副作用
        SnapshotCache(this).clear()
        prefs.edit().putLong(KEY_LAST_VERSION, current).apply()
    }

    private companion object {
        const val META = "app_meta"
        const val KEY_LAST_VERSION = "last_version_code"
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
