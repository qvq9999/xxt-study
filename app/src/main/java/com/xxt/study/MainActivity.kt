package com.xxt.study

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.ui.StudyTheme
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.domain.Work
import com.xxt.study.feature.MainShell
import com.xxt.study.feature.MainTab
import com.xxt.study.feature.login.LoginScreen
import com.xxt.study.feature.mine.GuideScreen
import com.xxt.study.feature.settings.SettingsScreen
import com.xxt.study.feature.study.StudyScreen
import com.xxt.study.feature.update.UpdateDialog
import com.xxt.study.feature.update.rememberUpdateController
import com.xxt.study.feature.verify.VerifyScreen
import com.xxt.study.feature.work.WorkAnswerScreen
import com.xxt.study.feature.work.WorkListScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container

        // 上次下载的安装包如果没装成（用户在系统安装器里取消了），这里顺手清掉，
        // 免得它一直占着外部目录。注意只在 onCreate 做：正在等安装的那份不能被删。
        runCatching { container.installer.cleanup() }

        // Android 13 起前台服务的通知也要授权，否则刷课跑着但通知栏看不见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        setContent {
            StudyTheme {
                val activityContext = LocalContext.current
                // 页面不多，用状态变量管路由就够，不引导航库。
                // 层级：登录 → 主壳（底部 tab：课程 / 我的）→ 课程列表 → 作业作答 / 刷课；
                // 设置从「我的」进，不再挂在首页右上角
                var loggedIn by remember { mutableStateOf(container.cookies.loggedIn()) }
                var showSettings by remember { mutableStateOf(false) }
                var showGuide by remember { mutableStateOf(false) }
                var openCourse by remember { mutableStateOf<CourseDigest?>(null) }
                var studyCourse by remember { mutableStateOf<Course?>(null) }
                var answerWork by remember { mutableStateOf<Work?>(null) }
                var tab by remember { mutableStateOf(MainTab.Courses) }

                // 账号信息只取一次，跟着 Activity 活：进出设置页不再重新请求，
                // 也就不会出现"已登录 → 真实账户名"的跳动
                var session by remember { mutableStateOf<Session?>(null) }
                LaunchedEffect(loggedIn) {
                    if (!loggedIn) {
                        session = null
                        return@LaunchedEffect
                    }
                    if (session == null) {
                        val r = container.repo.session()
                        if (r is Cx.Ok) session = r.data
                    }
                }

                /*
                 * 内置更新。
                 *
                 * 自动检查放在**登录之后 5 秒**：冷启动那一会儿正在跑课程列表请求，
                 * 这时候再去外网拉清单，只是多抢一份带宽；等服务端那波先飞完再说。
                 *
                 * 只在登录后检查，是因为没登录的用户连作业都看不到，
                 * 这时候推一个更新弹窗纯属打扰。
                 */
                val update = rememberUpdateController(container)
                LaunchedEffect(loggedIn) {
                    if (!loggedIn) return@LaunchedEffect
                    delay(AUTO_CHECK_DELAY_MS)
                    update.autoCheck()
                }

                /**
                 * 统一返回栈。
                 *
                 * 之前只有登录页和作业页接管了返回键，其它页面按返回会直接 finish Activity ——
                 * 用户感觉是"按一下退到桌面了"。
                 *
                 * 顺序就是用户的心智模型：一层层退，退到课程首页才让系统退出 App。
                 * 作业页有自己的 BackHandler（先让页面后退），它注册得比这里晚，优先级更高。
                 */
                BackHandler(
                    enabled = loggedIn && (
                        answerWork != null || studyCourse != null || openCourse != null ||
                            showSettings || showGuide || tab == MainTab.Mine
                        ),
                ) {
                    when {
                        answerWork != null -> answerWork = null
                        studyCourse != null -> studyCourse = null
                        openCourse != null -> openCourse = null
                        showGuide -> showGuide = false
                        showSettings -> showSettings = false
                        tab == MainTab.Mine -> tab = MainTab.Courses
                    }
                }

                // 需要人工验证时整页替换。不用 Dialog：WebView 放进 Dialog 窗口会渲染成白屏
                // （刷课页的课程页踩过同一个坑），而验证本身是阻塞态，整页也说得通。
                val verifyUrl by VerifyGate.pending.collectAsStateWithLifecycle()

                /*
                 * "验证疑似没生效"。
                 *
                 * 用户刚点完继续、业务立刻重试又被风控——说明验证其实没真的过
                 * （最常见的是验证码输了但没点提交）。这时候 VerifyGate 不再重复弹窗，
                 * 只发这个信号，由这里告诉用户原因，别让他对着同一页反复点。
                 */
                LaunchedEffect(Unit) {
                    VerifyGate.suspected.collect {
                        Toast.makeText(
                            activityContext,
                            "验证似乎没生效（可能验证码没提交成功）。已暂停请求 1 分钟，稍后再刷新",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.bg),
                ) {
                    when {
                        verifyUrl != null -> VerifyScreen(
                            url = verifyUrl!!,
                            cookies = container.cookies,
                            // VerifyScreen 内部已经调过 VerifyGate.finish()（导 Cookie + markPassed），
                            // 这里**不能再 markPassed 一次**：重复放行会让业务收到两次"验证完成"，
                            // 各自发起一轮重试，等于把风控撞得更狠
                            onPassed = {},
                            onCancel = { VerifyGate.cancel() },
                        )

                        !loggedIn -> LoginScreen(onLoggedIn = { loggedIn = true })

                        showGuide -> GuideScreen(onBack = { showGuide = false })

                        showSettings -> SettingsScreen(
                            ai = container.ai,
                            settings = container.settings,
                            onBack = { showSettings = false },
                        )

                        studyCourse != null -> StudyScreen(
                            course = studyCourse!!,
                            controller = container.study,
                            ai = container.ai,
                            cookies = container.cookies,
                            onBack = { studyCourse = null },
                            onOpenSettings = { showSettings = true },
                        )

                        answerWork != null -> WorkAnswerScreen(
                            work = answerWork!!,
                            ai = container.ai,
                            onBack = { answerWork = null },
                            onOpenSettings = { showSettings = true },
                        )

                        openCourse != null -> WorkListScreen(
                            digest = openCourse!!,
                            onBack = { openCourse = null },
                            onOpenWork = { answerWork = it },
                            onStartStudy = { studyCourse = openCourse!!.course },
                        )

                        else -> MainShell(
                            session = session,
                            tab = tab,
                            onTabChange = { tab = it },
                            onNeedLogin = { loggedIn = false },
                            onLogout = {
                                container.study.shutdown()
                                container.repo.logout()
                                loggedIn = false
                            },
                            onOpenCourse = { openCourse = it },
                            onOpenSettings = { showSettings = true },
                            onOpenGuide = { showGuide = true },
                            onCheckUpdate = { update.checkManually() },
                            checkingUpdate = update.checking,
                        )
                    }

                    // 弹窗是独立窗口，放在 Box 里哪个位置都一样；
                    // 放在 when 外面是为了在设置页/答题页也能收到"有新版本"
                    UpdateDialog(
                        state = update.state,
                        onUpdate = { update.download() },
                        onLater = { update.dismiss() },
                        onOpenPermission = { update.openInstallPermission() },
                    )
                }
            }
        }
    }

    private companion object {
        const val REQ_NOTIFY = 1001

        /** 自动检查更新的延迟：让课程列表那波请求先飞完 */
        const val AUTO_CHECK_DELAY_MS = 5_000L
    }
}
