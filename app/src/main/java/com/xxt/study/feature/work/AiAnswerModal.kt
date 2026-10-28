package com.xxt.study.feature.work

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ai.AiPrompt
import com.xxt.study.core.ui.AppModal
import com.xxt.study.core.ui.ModalButton
import com.xxt.study.core.ui.Palette

/** 弹窗里的一行：这道题 AI 给了什么、页面接受了没有 */
internal data class AnswerRow(
    val number: Int,
    val typeLabel: String,
    val stem: String,
    val answer: String,
    val ok: Boolean,
)

/** 生成过程的一个阶段 */
internal enum class GenStepState { DONE, NOW, WAIT, FAIL }

/**
 * 一个阶段。
 *
 * 我们的接口是**非流式**的（一次请求拿完整 JSON），所以"思考过程"只能做到
 * **阶段级**：读到哪、想了多久、回填了几题。等模型返回后才拿得到它真正的
 * reasoning 文本（DeepSeek/MiMo 这类会额外给），那部分单独显示。
 * 这里如实呈现，不假装在逐字打字。
 */
internal data class GenStep(
    val title: String,
    val state: GenStepState,
    val detail: String = "",
)

/**
 * AI 作答窗口。
 *
 * 0.9.0 起由底部抽屉改成居中弹窗（[AppModal]）。除了原来的"结果 + 思考过程"，
 * 新增两块：
 *
 * 1. **生成过程**：五个阶段逐个点亮，AI 阶段带已等待秒数——
 *    原来只有一个 `phase` 字符串原地变，用户看不出"卡住了还是在跑"；
 * 2. **提示词**：默认是内置的系统提示（`AiPrompt.SYSTEM`），可点【编辑提示词】改，
 *    改完按【应用并重新生成】。**改完不点等于没改**，这两步刻意分开，
 *    免得边打字边触发请求、把 token 烧在草稿上。
 */
@Composable
internal fun AiAnswerModal(
    phase: String,
    stat: String,
    modelLabel: String,
    steps: List<GenStep>,
    elapsedSec: Long,
    reasoning: String?,
    rows: List<AnswerRow>,
    generating: Boolean,
    promptDraft: String,
    promptOpen: Boolean,
    promptDirty: Boolean,
    /** 已保存的提示词是不是用户自定义的（决定收起时那一行怎么写） */
    promptCustom: Boolean,
    onTogglePrompt: () -> Unit,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    onApplyAndRegenerate: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppModal(
        title = "AI 作答",
        subtitle = modelLabel,
        onDismiss = onDismiss,
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (promptOpen) {
                    ModalButton("恢复默认", enabled = !generating, onClick = onResetPrompt)
                    Spacer(Modifier.weight(1f))
                    ModalButton(
                        text = if (generating) "生成中…" else "应用并重新生成",
                        primary = true,
                        enabled = !generating,
                        onClick = onApplyAndRegenerate,
                    )
                } else {
                    ModalButton("编辑提示词", enabled = !generating, onClick = onTogglePrompt)
                    Spacer(Modifier.weight(1f))
                    ModalButton(
                        text = when {
                            generating -> "生成中…"
                            rows.isEmpty() -> "开始作答"
                            else -> "重新生成"
                        },
                        primary = true,
                        enabled = !generating,
                        onClick = onRegenerate,
                    )
                }
            }
        },
    ) {
        // ---------- 当前状态 ----------
        Text(
            text = phase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (generating) Palette.brand else Palette.ink,
        )
        if (stat.isNotBlank()) {
            Text(
                text = stat,
                fontSize = 11.sp,
                color = Palette.sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (generating) {
            // 一条不确定进度条：阶段知道自己在第几步，但拿不到"还剩几秒"
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(2.dp),
                color = Palette.brand,
                trackColor = Palette.line,
            )
        }

        // ---------- 生成过程 ----------
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("生成过程")
            steps.forEach { step ->
                StepRow(
                    step = step,
                    // 只有"进行中"的那一步才显示秒数，否则每条都带时间看着很吵
                    elapsed = if (step.state == GenStepState.NOW && generating) elapsedSec else null,
                )
            }
        }

        // ---------- 提示词编辑 ----------
        if (promptOpen) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("提示词")
            Text(
                text = "这是发给模型的系统提示。改完点【应用并重新生成】才会生效；" +
                    "不想改就点【恢复默认】回到内置版本。",
                fontSize = 10.sp,
                color = Palette.weak,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (promptDraft.isEmpty()) {
                    Text(
                        text = "在这里写系统提示…",
                        fontSize = 11.sp,
                        color = Palette.weak,
                    )
                }
                /*
                 * 不用自带滚动的高度限制：输入框只设最小高度，让它随内容长高，
                 * 由弹窗统一滚动。否则框内框外两层滚动，改一段长提示词时会很难受。
                 */
                BasicTextField(
                    value = promptDraft,
                    onValueChange = onPromptChange,
                    enabled = !generating,
                    textStyle = TextStyle(fontSize = 11.sp, color = Palette.ink, lineHeight = 17.sp),
                    cursorBrush = SolidColor(Palette.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        promptDirty -> "已修改，尚未应用——点右下角【应用并重新生成】"
                        promptCustom -> "当前是自定义提示词（已保存）"
                        else -> "当前是内置默认提示词"
                    },
                    fontSize = 10.sp,
                    color = if (promptDirty) Palette.alarm else Palette.weak,
                    lineHeight = 14.sp,
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        promptDirty -> "提示词：有未应用的修改"
                        promptCustom -> "提示词：自定义"
                        else -> "提示词：内置默认"
                    },
                    fontSize = 10.sp,
                    color = if (promptDirty) Palette.alarm else Palette.weak,
                )
            }
        }

        // ---------- 思考过程 ----------
        Spacer(Modifier.height(12.dp))
        SectionTitle("思考过程")
        when {
            reasoning != null -> Text(
                text = reasoning,
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            generating -> Text(
                text = "模型正在生成，返回后这里会显示它的推理内容（部分模型不提供）。",
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            else -> Text(
                text = "这次没有返回思考内容（多数模型只在请求里带 reasoning 时才给）。",
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // ---------- 逐题结果 ----------
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("逐题结果")
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = if (row.ok) "✓" else "✗",
                        fontSize = 12.sp,
                        color = if (row.ok) Palette.ok else Palette.alarm,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${row.number}. [${row.typeLabel}] ${row.stem}",
                            fontSize = 11.sp,
                            color = Palette.ink,
                            lineHeight = 16.sp,
                        )
                        Text(
                            text = row.answer,
                            fontSize = 11.sp,
                            color = if (row.ok) Palette.ok else Palette.alarm,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Palette.sub,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** 一个阶段：圆点 + 标题（+ 等待秒数） */
@Composable
private fun StepRow(step: GenStep, elapsed: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when (step.state) {
                        GenStepState.DONE -> Palette.ok
                        GenStepState.NOW -> Palette.brand
                        GenStepState.FAIL -> Palette.alarm
                        GenStepState.WAIT -> Palette.line
                    },
                ),
        )
        Text(
            text = step.title,
            fontSize = 11.sp,
            color = when (step.state) {
                GenStepState.DONE -> Palette.sub
                GenStepState.NOW -> Palette.brand
                GenStepState.FAIL -> Palette.alarm
                GenStepState.WAIT -> Palette.weak
            },
            lineHeight = 16.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        val tail = when {
            step.detail.isNotBlank() -> step.detail
            elapsed != null -> "已等待 ${elapsed}s"
            step.state == GenStepState.DONE -> "完成"
            step.state == GenStepState.FAIL -> "失败"
            else -> ""
        }
        if (tail.isNotBlank()) {
            Text(
                text = tail,
                fontSize = 10.sp,
                color = Palette.weak,
            )
        }
    }
}
