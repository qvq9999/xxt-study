package com.xxt.study.core.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 更新任务当前处在哪一步。
 *
 * 这是**全局任务状态**，和"弹窗开没开"是两件事——
 * 用户关掉弹窗，下载照样继续（[UpdateCenter] 的任务挂在应用作用域上，不跟着界面走）。
 */
enum class UpdatePhase {
    /** 什么都没发生 */
    IDLE,

    /** 正在拉清单 */
    CHECKING,

    /** 已是最新 */
    UP_TO_DATE,

    /** 检查失败（网络/清单坏了） */
    FAILED,

    /** 发现新版本，等用户决定 */
    PROMPT,

    /** 正在下载 */
    DOWNLOADING,

    /** 下载并校验完成，等用户点安装 */
    READY,

    /** 缺"安装未知应用"权限 */
    NEED_PERMISSION,

    /** 下载或安装出错 */
    ERROR,
}

/**
 * 更新的全部界面状态。
 *
 * @param dialogOpen 弹窗是否展开。**和 [phase] 无关**：下载中可以关掉弹窗
 *                   （`dialogOpen=false`，`phase=DOWNLOADING`），
 *                   用户从"我的"页再点一次就把它重新拉起来。
 * @param checkedAt 上次检查完成的时间，用来在按钮上写"刚刚检查过"
 */
data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val available: UpdateAvailable? = null,
    val progress: Int = 0,
    val message: String? = null,
    val checkedAt: Long = 0L,
    val dialogOpen: Boolean = false,
) {
    /** 检查/下载进行中：按钮要禁用，避免重复触发 */
    val busy: Boolean get() = phase == UpdatePhase.CHECKING

    /** 有活干并且用户可以随时把弹窗拉回来看 */
    val hasBackgroundWork: Boolean
        get() = phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.READY

    /**
     * 有没有"值得重新打开"的东西。
     *
     * 有就说明任务停在某个中间态（检查结果 / 下载中 / 待安装 / 缺权限 / 出错），
     * "我的"页那一行点进去应该恢复它；没有则是"从没检查过"，点了就发起一次检查。
     */
    val hasOpenableState: Boolean get() = phase != UpdatePhase.IDLE

    /** 弹窗该显示什么：只要 dialogOpen 且 phase 不是 IDLE 就显示 */
    val shouldShowDialog: Boolean get() = dialogOpen && phase != UpdatePhase.IDLE
}

/**
 * 更新任务的中枢。
 *
 * 为什么必须是应用级单例而不是 ViewModel/Composable 里的状态：
 *
 * 1. 用户点了"更新"之后可以随手关掉弹窗继续刷课，下载**不能因此中断**——
 *    挂在界面作用域上的协程会跟着界面一起被取消；
 * 2. 检查更新的入口有两处（启动自动检查、"我的"页手动检查），状态必须是同一份，
 *    否则从设置页点完再回"我的"，会看到两个互相矛盾的进度；
 * 3. 下载完成要能发通知、通知点开要能回到同一个状态。
 *
 * 并发的三条护栏（重复点击不能出乱子）：
 * - [check]：正在检查时再点直接忽略；
 * - [download]：下载任务用 [downloadJob] 去重，已经在跑就不再起第二个；
 * - 下载进行中不接受新的检查，避免状态被覆盖。
 */
class UpdateCenter(
    private val appContext: Context,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    private val notifier: UpdateNotifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 下载任务句柄：重复点"更新"不会起第二个下载 */
    private var downloadJob: Job? = null

    // ---------------- 检查 ----------------

    /**
     * 检查更新。
     *
     * @param manual 用户主动点的：**立刻**把弹窗拉起来显示"正在检查"，
     *               让点击有即时反馈；自动检查则安静进行，只有发现新版才弹。
     */
    fun check(manual: Boolean) {
        val s = _state.value
        if (s.phase == UpdatePhase.CHECKING) return
        // 下载中不去动状态：进度条正跑着，被一次检查覆盖掉就再也回不来了
        if (downloadJob?.isActive == true) return

        _state.update {
            it.copy(
                phase = UpdatePhase.CHECKING,
                message = null,
                progress = 0,
                // 手动检查立刻开弹窗，是"点了要有反应"的关键
                dialogOpen = manual,
            )
        }

        scope.launch {
            when (val r = checker.check(force = manual)) {
                is UpdateChecker.Result.Found -> _state.update {
                    it.copy(
                        phase = UpdatePhase.PROMPT,
                        available = r.available,
                        checkedAt = System.currentTimeMillis(),
                        dialogOpen = true,
                    )
                }

                is UpdateChecker.Result.UpToDate -> _state.update {
                    it.copy(
                        phase = UpdatePhase.UP_TO_DATE,
                        checkedAt = System.currentTimeMillis(),
                        // 手动检查时留在弹窗里给个明确的"已是最新"，自动检查就悄悄收起来
                        dialogOpen = manual,
                    )
                }

                is UpdateChecker.Result.Failed -> _state.update {
                    it.copy(
                        phase = UpdatePhase.FAILED,
                        message = r.reason,
                        checkedAt = System.currentTimeMillis(),
                        // 手动检查失败必须让用户看见；自动检查失败**不能**弹窗——
                        // 用户没问，冷启动时甩一个"检查更新失败"只会让人莫名其妙
                        dialogOpen = manual,
                    )
                }

                is UpdateChecker.Result.Skipped -> _state.update {
                    it.copy(
                        phase = if (manual) UpdatePhase.FAILED else UpdatePhase.IDLE,
                        message = r.reason,
                        dialogOpen = manual && r.reason.isNotBlank(),
                    )
                }
            }
        }
    }

    // ---------------- 下载 ----------------

    fun download() {
        // 已经在下载 / 已下完，重复点忽略
        if (downloadJob?.isActive == true) return
        val s = _state.value
        if (s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.READY) return
        val available = s.available ?: return

        // 没有"安装未知应用"权限时先要权限：不然后面下完了也装不上，白等一场
        if (!installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.NEED_PERMISSION) }
            return
        }

        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = null) }

        // 注意：这里用的是应用级 scope，不是界面 scope。
        // 用户关掉弹窗、甚至退出设置页，下载都不会被取消。
        downloadJob = scope.launch {
            val outcome = installer.download(available) { pct ->
                _state.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(progress = pct) else it }
            }
            when (outcome) {
                is UpdateInstaller.Outcome.Ok -> {
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.READY,
                            progress = 100,
                            message = null,
                            available = available.copy(file = outcome.file),
                        )
                    }
                    // 通知是"后台完成"的必要条件：用户可能已经切走了
                    notifier.notifyReady(available.manifest.versionName)
                }

                is UpdateInstaller.Outcome.Fail -> _state.update {
                    it.copy(phase = UpdatePhase.ERROR, message = outcome.message)
                }
            }
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 交给系统安装器。
     *
     * 安装器一起，我们这边就回到 IDLE（任务结束）—— 装没装成由系统界面负责，
     * 我们没法可靠地知道结果，留一个假的"安装中"只会误导。
     */
    fun install() {
        val s = _state.value
        val file = s.available?.file
        val available = s.available ?: return
        if (file == null || !file.exists()) {
            _state.update { it.copy(phase = UpdatePhase.ERROR, message = "安装包不见了，请重新下载") }
            return
        }
        if (!installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.NEED_PERMISSION) }
            return
        }

        val opened = runCatching { installer.install(file) }
        if (opened.isSuccess) {
            notifier.cancel()
            _state.update {
                it.copy(
                    phase = UpdatePhase.IDLE,
                    available = null,
                    dialogOpen = false,
                    progress = 0,
                    message = null,
                )
            }
        } else {
            _state.update {
                it.copy(
                    phase = UpdatePhase.ERROR,
                    message = "打不开系统安装器：${opened.exceptionOrNull()?.message ?: "未知原因"}",
                    available = available,
                )
            }
        }
    }

    // ---------------- 弹窗开关 ----------------

    /**
     * 关掉弹窗。
     *
     * **只关弹窗，不动任务**：下载继续、进度继续走，
     * "我的"页那一行会显示"正在下载 43%"，点它就能把弹窗拉回来。
     *
     * 唯一的例外是"发现新版本但用户点了稍后"：记住这个版本，不再自动弹。
     */
    fun dismiss() {
        val s = _state.value
        if (s.phase == UpdatePhase.PROMPT) {
            s.available?.manifest?.versionCode?.let { checker.dismiss(it) }
            _state.update { it.copy(phase = UpdatePhase.IDLE, dialogOpen = false) }
            return
        }
        // 检查结果/错误这类"一次性结论"，关掉就回到空闲；
        // 下载中/已下载这类"还有活没干完"的，保留 phase，只收起窗口
        _state.update {
            when (it.phase) {
                UpdatePhase.UP_TO_DATE, UpdatePhase.FAILED -> it.copy(phase = UpdatePhase.IDLE, dialogOpen = false)
                UpdatePhase.ERROR -> it.copy(phase = UpdatePhase.IDLE, dialogOpen = false, message = null)
                else -> it.copy(dialogOpen = false)
            }
        }
    }

    /**
     * 从"我的"页把那行状态点开。
     *
     * 只在任务真的停在中间态时恢复现场；已经回到 IDLE（比如点过"稍后"）就**重新查一次**——
     * 用户点的是"检查更新"，给他弹一份刚才自己关掉的旧提示很奇怪。
     */
    fun reopen() {
        if (_state.value.phase != UpdatePhase.IDLE) {
            _state.update { it.copy(dialogOpen = true) }
        } else {
            check(manual = true)
        }
    }

    /** 拉起"允许安装未知应用"的系统页 */
    fun openInstallPermission(): Boolean {
        val intent = installer.permissionIntent() ?: return false
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    /** 权限页回来之后调用：权限拿到了就自动接着下载 */
    fun onPermissionMaybeGranted() {
        val s = _state.value
        if (s.phase == UpdatePhase.NEED_PERMISSION && installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.PROMPT) }
            download()
        }
    }
}
