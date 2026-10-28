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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 更新弹窗的阶段 */
enum class UpdatePhase {
    /** 不显示 */
    HIDDEN,

    /** 有新版本，问要不要更 */
    PROMPT,

    /** 正在下载 */
    DOWNLOADING,

    /** 缺"安装未知应用"权限 */
    NEED_PERMISSION,

    /** 失败 */
    ERROR,
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.HIDDEN,
    val available: UpdateAvailable? = null,
    val progress: Int = 0,
    val message: String? = null,
)

/**
 * 更新弹窗。
 *
 * 刻意做得很小：一个版本号、三条以内的简写日志、两个按钮。
 * 用户要的是"更不更"，不是读一份 changelog——
 * 完整日志在 GitHub 发布页，这里只给结论。
 *
 * 用 Dialog 而不是整页：更新是打断性很低的事，
 * 用户在首页扫着作业时弹一个小卡片就够了。
 * （这里没有 WebView，不会踩"Dialog 里 WebView 白屏"那个坑。）
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val available = state.available ?: return
    val manifest = available.manifest
    if (state.phase == UpdatePhase.HIDDEN) return

    Dialog(
        onDismissRequest = { if (state.phase != UpdatePhase.DOWNLOADING) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = state.phase != UpdatePhase.DOWNLOADING,
            dismissOnClickOutside = state.phase != UpdatePhase.DOWNLOADING,
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
                text = "发现新版本 v${manifest.versionName}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
            )
            Text(
                text = buildString {
                    append("当前 v${available.currentVersionName.ifBlank { available.currentVersionCode.toString() }}")
                    append(" · ")
                    append(available.sizeText)
                    if (available.source.isNotBlank() && available.source != "GitHub") {
                        append(" · ")
                        append(available.source)
                    }
                },
                fontSize = 11.sp,
                color = Palette.weak,
                modifier = Modifier.padding(top = 4.dp),
            )

            // ---- 更新内容（简写，最多三条）----
            val notes = manifest.shortNotes()
            if (notes.isNotEmpty()) {
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

            // ---- 下载进度 ----
            if (state.phase == UpdatePhase.DOWNLOADING) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Palette.brand,
                    trackColor = Palette.line,
                )
                Text(
                    text = if (state.progress in 1..99) "下载中 ${state.progress}%" else "正在准备下载",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ---- 权限提示 ----
            if (state.phase == UpdatePhase.NEED_PERMISSION) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "系统需要你允许「安装未知应用」，下载完才能装。",
                    fontSize = 12.sp,
                    color = Palette.alarm,
                    lineHeight = 17.sp,
                )
            }

            // ---- 错误 ----
            val errorText = state.message
            if (state.phase == UpdatePhase.ERROR && !errorText.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    color = Palette.alarm,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton(
                    text = if (state.phase == UpdatePhase.ERROR) "关闭" else "稍后",
                    primary = false,
                    enabled = state.phase != UpdatePhase.DOWNLOADING,
                    onClick = onLater,
                )
                Spacer(Modifier.width(10.dp))
                when (state.phase) {
                    UpdatePhase.PROMPT -> DialogButton("更新", primary = true, onClick = onUpdate)
                    UpdatePhase.ERROR -> DialogButton("重试", primary = true, onClick = onUpdate)
                    UpdatePhase.NEED_PERMISSION ->
                        DialogButton("去开启", primary = true, onClick = onOpenPermission)
                    UpdatePhase.DOWNLOADING ->
                        DialogButton("下载中…", primary = true, enabled = false, onClick = {})
                    UpdatePhase.HIDDEN -> Unit
                }
            }
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
