package com.xxt.study

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.xxt.study.core.net.VerifyGate
import com.xxt.study.core.study.ThemeMode
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.ui.StudyTheme
import com.xxt.study.core.result.Cx
import com.xxt.study.core.update.UpdateNotifier
import com.xxt.study.domain.Course
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.domain.Work
import com.xxt.study.feature.MainShell
import com.xxt.study.feature.MainTab
import com.xxt.study.feature.login.LoginScreen
import com.xxt.study.feature.mine.GuideScreen
import com.xxt.study.feature.perm.PermissionSetupDialog
import com.xxt.study.feature.settings.SettingsScreen
import com.xxt.study.feature.course.CourseScreen
import com.xxt.study.feature.update.UpdateDialog
import com.xxt.study.feature.verify.VerifyScreen
import com.xxt.study.feature.work.WorkAnswerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val container: Container get() = (application as App).container

    /**
     * 通知点进来的。
     *
     * 用 Flow 而不是普通字段：点通知时 Activity 可能已经存在（onNewIntent），
     * Compose 那边需要一个可观察的入口才知道"该把更新弹窗拉起来了"。
     */
    private val openUpdateSignal = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 上次下载的安装包如果没装成（用户在系统安装器里取消了），这里顺手清掉，
        // 免得它一直占着外部目录。注意只在 onCreate 做：正在等安装的那份不能被删。
        runCatching { container.installer.cleanup() }

        handleIntent(intent)

        setContent {
            // 主题：跟随系统 / 强制浅色 / 强制深色（深色背景是纯黑，OLED 关像素）
            val themeMode by container.settings.theme.collectAsStateWithLifecycle()
            val dark = themeMode.resolveDark(isSystemInDarkTheme())

            /*
             * 窗口背景也跟着走。
             *
             * 内容还没绘制出来的那一瞬间（冷启动、Activity 重建），
             * 状态栏区露出的是 Window 背景而不是我们的 Box；
             * 不设的话深色模式会闪一下浅灰，纯黑就没意义了。
             */
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_BG else LIGHT_BG))
            }

            StudyTheme(dark = dark) {
                val activityContext = LocalContext.current
                // 页面不多，用状态变量管路由就够，不引导航库。
                // 层级：登录 → 主壳（底部 tab：课程 / 我的）→ 课程列表 → 作业作答 / 刷课；
                // 设置从「我的」进，不再挂在首页右上角
                var loggedIn by remember { mutableStateOf(container.cookies.loggedIn()) }
                var showSettings by remember { mutableStateOf(false) }
                var showGuide by remember { mutableStateOf(false) }
                var openCourse by remember { mutableStateOf<CourseDigest?>(null) }
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
                 * 权限：两项都要，但申请方式完全不同。
                 *
                 * - **通知**（Android 13+）是运行时权限，系统对话框能直接弹；
                 * - **安装未知应用**（Android 8+）**不是**运行时权限，
                 *   `requestPermissions` 对它无效，只能跳
                 *   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 让用户自己打开。
                 *   有的 ROM 会在这个页面里再弹一次确认框，有的不会，所以还要给出路径提示。
                 *
                 * 首次启动替用户走一遍；之后不再自动打扰，入口留在「我的 → 权限」。
                 */
                val meta = remember {
                    activityContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                }
                // onResume 就自增一次：从系统设置页回来时权限可能已经变了
                var permTick by remember { mutableIntStateOf(0) }
                var showPermSetup by remember { mutableStateOf(false) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            permTick++
                            // 如果刚才是因为缺"安装应用"权限卡住的更新，顺手接着下载
                            container.updateCenter.onPermissionMaybeGranted()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val notifGranted = remember(permTick) { activityContext.hasNotificationPermission() }
                val installGranted = remember(permTick) { container.installer.canInstall() }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    permTick++
                    // 通知弹窗处理完，接着引导安装权限——两项一起说清楚，别分两次打断
                    if (!container.installer.canInstall()) showPermSetup = true
                }

                LaunchedEffect(Unit) {
                    if (meta.getBoolean(KEY_PERM_PROMPTED, false)) return@LaunchedEffect
                    meta.edit().putBoolean(KEY_PERM_PROMPTED, true).apply()

                    when {
                        // 先弹系统的通知权限对话框（用户要求"自动弹出申请"）
                        !activityContext.hasNotificationPermission() ->
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        // 通知已经有了，只剩安装权限要引导
                        !container.installer.canInstall() -> showPermSetup = true
                    }
                }

                /*
                 * 内置更新。
                 *
                 * 自动检查放在**登录之后 5 秒**：冷启动那一会儿正在跑课程列表请求，
                 * 这时候再去外网拉清单，只是多抢一份带宽；等服务端那波先飞完再说。
                 * 自动检查是安静的——没新版、检查失败都不打扰，只有发现新版才弹。
                 */
                val updateState by container.updateCenter.state.collectAsStateWithLifecycle()

                LaunchedEffect(loggedIn) {
                    if (!loggedIn) return@LaunchedEffect
                    delay(AUTO_CHECK_DELAY_MS)
                    container.updateCenter.check(manual = false)
                }

                // 点"更新已下载"的通知回来：直接把弹窗拉到当前状态
                val openUpdate by openUpdateSignal.collectAsStateWithLifecycle()
                LaunchedEffect(openUpdate) {
                    if (!openUpdate) return@LaunchedEffect
                    openUpdateSignal.value = false
                    container.updateCenter.reopen()
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
                        answerWork != null || openCourse != null ||
                            showSettings || showGuide || tab != MainTab.Courses
                        ),
                ) {
                    when {
                        answerWork != null -> answerWork = null
                        openCourse != null -> openCourse = null
                        showGuide -> showGuide = false
                        showSettings -> showSettings = false
                        tab != MainTab.Courses -> tab = MainTab.Courses
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

                        answerWork != null -> WorkAnswerScreen(
                            work = answerWork!!,
                            ai = container.ai,
                            onBack = { answerWork = null },
                            onOpenSettings = { showSettings = true },
                        )

                        openCourse != null -> CourseScreen(
                            digest = openCourse!!,
                            repo = container.repo,
                            controller = container.study,
                            ai = container.ai,
                            cookies = container.cookies,
                            activities = container.activityClient,
                            onBack = { openCourse = null },
                            // 单课刷新后把新结果顶回状态，页面和首页数字一起更新
                            onDigestUpdated = { openCourse = it },
                            onOpenWork = { answerWork = it },
                            onOpenSettings = { showSettings = true },
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
                            updateState = updateState,
                            // 有活干（下载中/待安装）就是"查看"，否则才发起检查
                            onUpdateClick = {
                                if (updateState.hasOpenableState) {
                                    container.updateCenter.reopen()
                                } else {
                                    container.updateCenter.check(manual = true)
                                }
                            },
                            onOpenPermissions = { showPermSetup = true },
                        )
                    }

                    // 弹窗是独立窗口，放在 Box 里哪个位置都一样；
                    // 放在 when 外面是为了在设置页/答题页也能收到"有新版本"
                    UpdateDialog(
                        state = updateState,
                        onUpdate = { container.updateCenter.download() },
                        onInstall = { container.updateCenter.install() },
                        onRetryCheck = { container.updateCenter.check(manual = true) },
                        onLater = { container.updateCenter.dismiss() },
                        onOpenPermission = {
                            if (!container.updateCenter.openInstallPermission()) {
                                Toast.makeText(
                                    activityContext,
                                    "这个系统没有「安装未知应用」页面，请到系统设置 → 应用 → 本应用 里手动允许",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )

                    if (showPermSetup) {
                        PermissionSetupDialog(
                            notifGranted = notifGranted,
                            installGranted = installGranted,
                            onRequestNotif = {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onOpenInstallSettings = {
                                if (!container.updateCenter.openInstallPermission()) {
                                    Toast.makeText(
                                        activityContext,
                                        "这个系统没有「安装未知应用」页面，请到系统设置 → 应用 → 本应用 里手动允许",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onDismiss = { showPermSetup = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 通知带过来的"打开更新"信号；不认识的 intent 一律忽略 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, false) == true) {
            openUpdateSignal.value = true
            // 消费掉：否则旋转屏幕/进程重建时会被当成又一次点击
            intent.removeExtra(UpdateNotifier.EXTRA_OPEN_UPDATE)
        }
    }

    private companion object {
        /** 自动检查更新的延迟：让课程列表那波请求先飞完 */
        const val AUTO_CHECK_DELAY_MS = 5_000L

        const val META_PREFS = "app_meta"

        /** 权限引导只在首次启动自动走一遍，之后从「我的 → 权限」进 */
        const val KEY_PERM_PROMPTED = "perm_prompted"

        /** 窗口背景色：和 Palette.bg 保持一致，避免内容绘制前闪一下 */
        const val LIGHT_BG = 0xFFF5F6F8.toInt()
        const val DARK_BG = 0xFF000000.toInt()
    }
}

/** Android 13 之前没有通知运行时权限，一律视为已授权 */
private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
