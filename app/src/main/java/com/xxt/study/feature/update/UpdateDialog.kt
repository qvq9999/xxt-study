package com.xxt.study.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.update.UpdateAvailable
import com.xxt.study.core.update.UpdatePhase
import com.xxt.study.core.update.UpdateUiState

/**
 * 更新弹窗。
 *
 * 一个弹窗承载「检查中 / 已是最新 / 检查失败 / 发现新版 / 下载中 / 待安装 / 缺权限」七种形态：
 * 它们都发生在同一个上下文里，用户点完"检查更新"就该在原地看到结果，
 * 而不是点完什么都不发生、只靠 Toast 一闪而过。
 *
 * 下载中可以直接关掉（【后台运行】）：任务跑在
 * [com.xxt.study.core.update.UpdateCenter] 的应用级作用域里，关窗口不影响它，
 * "我的"页那行会继续显示进度。
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onLater: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    if (!state.shouldShowDialog) return

    val available = state.available
    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.card)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = titleOf(state),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )

            subtitleOf(state, available)?.let { sub ->
                Text(
                    text = sub,
                    fontSize = 11.sp,
                    color = Palette.weak,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            when (state.phase) {
                UpdatePhase.CHECKING -> CheckingBody()
                UpdatePhase.DOWNLOADING -> DownloadingBody(state.progress)
                UpdatePhase.PROMPT -> available?.let { PromptBody(it) }
                UpdatePhase.READY -> ReadyBody(available)
                UpdatePhase.NEED_PERMISSION -> PermissionBody()
                UpdatePhase.UP_TO_DATE -> Unit
                UpdatePhase.FAILED, UpdatePhase.ERROR -> MessageBody(state.message)
                UpdatePhase.IDLE -> Unit
            }

            Spacer(Modifier.height(16.dp))

            ButtonsOf(
                phase = state.phase,
                onUpdate = onUpdate,
                onInstall = onInstall,
                onRetryCheck = onRetryCheck,
                onLater = onLater,
                onOpenPermission = onOpenPermission,
            )
        }
    }
}

// ---------------- 文案 ----------------

private fun titleOf(s: UpdateUiState): String = when (s.phase) {
    UpdatePhase.CHECKING -> "正在检查更新"
    UpdatePhase.UP_TO_DATE -> "已是最新版本"
    UpdatePhase.FAILED -> "检查更新失败"
    UpdatePhase.PROMPT -> "发现新版本 v${s.available?.manifest?.versionName.orEmpty()}"
    UpdatePhase.DOWNLOADING -> "正在下载 v${s.available?.manifest?.versionName.orEmpty()}"
    UpdatePhase.READY -> "下载完成"
    UpdatePhase.NEED_PERMISSION -> "需要允许安装应用"
    UpdatePhase.ERROR -> "更新失败"
    UpdatePhase.IDLE -> ""
}

private fun subtitleOf(s: UpdateUiState, a: UpdateAvailable?): String? = when (s.phase) {
    UpdatePhase.CHECKING -> "正在连接更新服务器…"
    UpdatePhase.UP_TO_DATE ->
        if (a != null) "当前 v${a.currentVersionName.ifBlank { a.currentVersionCode.toString() }}" else null
    UpdatePhase.PROMPT, UpdatePhase.DOWNLOADING, UpdatePhase.READY, UpdatePhase.NEED_PERMISSION ->
        if (a == null) null else buildString {
            append("当前 v")
            append(a.currentVersionName.ifBlank { a.currentVersionCode.toString() })
            append(" · ")
            append(a.sizeText)
            if (a.source.isNotBlank() && a.source != "GitHub") {
                append(" · ")
                append(a.source)
            }
        }
    else -> null
}

// ---------------- 内容区 ----------------

@Composable
private fun CheckingBody() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Palette.brand,
        )
        Text(
            text = "请求更新清单中，通常一两秒",
            fontSize = 12.sp,
            color = Palette.sub,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun DownloadingBody(pct: Int) {
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(
        progress = { pct / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = Palette.brand,
        trackColor = Palette.line,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (pct in 1..99) "下载中 $pct%" else "正在准备下载",
            fontSize = 11.sp,
            color = Palette.weak,
        )
        // 说清楚"关掉也不会中断"，用户才敢关
        Text(
            text = "可关掉弹窗，后台继续",
            fontSize = 11.sp,
            color = Palette.brand,
        )
    }
}

@Composable
private fun PromptBody(a: UpdateAvailable) {
    val notes = a.manifest.shortNotes()
    if (notes.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { line ->
            Row {
                Text("·", fontSize = 12.sp, color = Palette.brand)
                Text(
                    text = line,
                    fontSize = 12.sp,
                    color = Palette.sub,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ReadyBody(a: UpdateAvailable?) {
    Text(
        text = "v${a?.manifest?.versionName.orEmpty()} 已下载并校验完成，" +
            "点「立即安装」交给系统安装器。",
        fontSize = 12.sp,
        color = Palette.sub,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun PermissionBody() {
    Text(
        text = "Android 要求手动允许「安装未知应用」，否则下载完也装不上。" +
            "点下面按钮去开启，回来后会自动继续。",
        fontSize = 12.sp,
        color = Palette.alarm,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun MessageBody(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        fontSize = 12.sp,
        color = Palette.alarm,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
}

// ---------------- 按钮 ----------------

@Composable
private fun ButtonsOf(
    phase: UpdatePhase,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onLater: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phase) {
            // 检查中只给"后台"：检查本身几秒，不需要取消
            UpdatePhase.CHECKING -> DialogButton("后台进行", primary = false, onClick = onLater)

            UpdatePhase.UP_TO_DATE -> DialogButton("知道了", primary = true, onClick = onLater)

            UpdatePhase.FAILED -> {
                DialogButton("关闭", primary = false, onClick = onLater)
                Spacer(Modifier.width(10.dp))
                DialogButton("重试", primary = true, onClick = onRetryCheck)
            }

            UpdatePhase.PROMPT -> {
                DialogButton("稍后", primary = false, onClick = onLater)
                Spacer(Modifier.width(10.dp))
                DialogButton("更新", primary = true, onClick = onUpdate)
            }

            // 文案刻意不写"取消"：它确实不会取消下载
            UpdatePhase.DOWNLOADING -> DialogButton("后台运行", primary = false, onClick = onLater)

            UpdatePhase.READY -> {
                DialogButton("稍后", primary = false, onClick = onLater)
                Spacer(Modifier.width(10.dp))
                DialogButton("立即安装", primary = true, onClick = onInstall)
            }

            UpdatePhase.NEED_PERMISSION -> {
                DialogButton("稍后", primary = false, onClick = onLater)
                Spacer(Modifier.width(10.dp))
                DialogButton("去开启", primary = true, onClick = onOpenPermission)
            }

            UpdatePhase.ERROR -> {
                DialogButton("关闭", primary = false, onClick = onLater)
                Spacer(Modifier.width(10.dp))
                DialogButton("重试", primary = true, onClick = onUpdate)
            }

            UpdatePhase.IDLE -> Unit
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Palette.bg
                    primary -> Palette.brand
                    else -> Palette.bg
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> Palette.weak
                primary -> Palette.card
                else -> Palette.sub
            },
        )
    }
}
