package com.xxt.study.feature.update

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xxt.study.Container
import com.xxt.study.core.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 更新流程的状态机。
 *
 * 抽出来是因为它要跨好几个页面用：启动时自动检查、设置页手动检查、
 * 弹窗下载、缺权限时跳系统设置、装完清理——
 * 摊在 Activity 的 setContent 里会变成一坨，也没法说清"哪一步是谁触发"。
 */
class UpdateController(
    private val container: Container,
    private val scope: CoroutineScope,
    private val context: Context,
) {

    var state by mutableStateOf(UpdateUiState())
        private set

    /** 手动检查进行中：按钮置灰，避免连点发出一串请求 */
    var checking by mutableStateOf(false)
        private set

    // ---------------- 检查 ----------------

    /**
     * 启动时的自动检查。
     *
     * 不弹"已是最新"、不弹错误——用户没主动问，安安静静地什么都不说才对。
     * 真正需要说话的是"有新版本"。
     */
    fun autoCheck() {
        if (checking || state.phase != UpdatePhase.HIDDEN) return
        scope.launch {
            when (val r = container.updater.check(force = false)) {
                is UpdateChecker.Result.Found ->
                    state = UpdateUiState(UpdatePhase.PROMPT, r.available)
                else -> Unit
            }
        }
    }

    /**
     * 用户主动点的检查：结果必须回话——
     * 什么都不发生的话，用户会以为按钮坏了。
     */
    fun checkManually() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                when (val r = container.updater.check(force = true)) {
                    is UpdateChecker.Result.Found ->
                        state = UpdateUiState(UpdatePhase.PROMPT, r.available)
                    is UpdateChecker.Result.UpToDate -> toast("已经是最新版本")
                    is UpdateChecker.Result.Failed -> toast("检查失败：${r.reason}")
                    is UpdateChecker.Result.Skipped ->
                        toast(r.reason.ifBlank { "稍后再试" })
                }
            } finally {
                // 只在成功路径复位会让按钮永久置灰（AI 答题踩过同一个坑）
                checking = false
            }
        }
    }

    // ---------------- 下载与安装 ----------------

    fun download() {
        val available = state.available ?: return

        // 没开"安装未知应用"就先要权限：没有它，下完也装不上，白等
        if (!container.installer.canInstall()) {
            state = state.copy(phase = UpdatePhase.NEED_PERMISSION)
            return
        }

        state = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = null)
        scope.launch {
            val outcome = container.installer.download(available) { pct ->
                state = state.copy(progress = pct)
            }
            when (outcome) {
                is com.xxt.study.core.update.UpdateInstaller.Outcome.Ok -> {
                    val opened = runCatching { container.installer.install(outcome.file) }
                    if (opened.isSuccess) {
                        // 系统安装器已经接管，弹窗收起来；安装包留到下次启动再清
                        state = UpdateUiState()
                    } else {
                        state = state.copy(
                            phase = UpdatePhase.ERROR,
                            message = "打不开系统安装器：${opened.exceptionOrNull()?.message ?: "未知原因"}",
                        )
                    }
                }
                is com.xxt.study.core.update.UpdateInstaller.Outcome.Fail ->
                    state = state.copy(phase = UpdatePhase.ERROR, message = outcome.message)
            }
        }
    }

    /** 拉起"允许安装未知应用"的系统页；有些 ROM 没有这个页面，兜个提示 */
    fun openInstallPermission() {
        val intent = container.installer.permissionIntent()
        val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) toast("请到系统设置里允许本应用安装应用")
    }

    // ---------------- 关闭 ----------------

    /**
     * 用户点了"稍后"。
     *
     * 记住这个版本，同一个版本不再自动弹——但手动检查仍然会告诉他，
     * 否则用户会觉得"点了检查却说没有更新"。
     */
    fun dismiss() {
        val versionCode = state.available?.manifest?.versionCode
        if (versionCode != null) container.updater.dismiss(versionCode)
        state = UpdateUiState()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberUpdateController(container: Container): UpdateController {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(container) { UpdateController(container, scope, context) }
}
