package com.xxt.study.feature.work

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 优化对话里的一条记录 */
internal enum class ChatRole { USER, AI, NOTE }

internal data class ChatTurn(
    val role: ChatRole,
    val text: String,
)

/** 输入框占位文案：给两个真实可用的例子，比"请输入要求"有用 */
internal const val CHAT_PLACEHOLDER = "说说要改什么：简答再短一点 / 第3题改成要点式"

/**
 * AI 作答窗口。
 *
 * 0.9.0 起是居中弹窗（[AppModal]）；0.9.1 把"改提示词"换成**对话式优化**。
 *
 * 为什么要换：提示词是给"工程师"用的旋钮，用户想表达的是"这段写太长""第 3 题别那么口语"，
 * 一句话说清楚最快。所以现在：
 *
 * - 底部常驻输入框（放在 footer 里，不跟着内容滚，键盘弹起也不会被推走）
 * - 每说一句就是一轮优化：AI 拿**当前答案**改，改完覆盖回同一个答案区并重新回填到页面
 * - 每一轮都进 [chat]，用户能看到自己提过什么、AI 改了几题
 * - 改坏了可以【撤销本次优化】回到上一版（[canUndo]）
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
    chat: List<ChatTurn>,
    chatInput: String,
    chatHint: String?,
    canUndo: Boolean,
    onChatInput: (String) -> Unit,
    onSend: () -> Unit,
    onUndo: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppModal(
        title = "AI 作答",
        subtitle = modelLabel,
        onDismiss = onDismiss,
        footer = {
            Column {
                // ---- 输入框：常驻在底部，不参与内容滚动 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.bg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (chatInput.isEmpty()) {
                            Text(
                                text = CHAT_PLACEHOLDER,
                                fontSize = 11.sp,
                                color = Palette.weak,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = chatInput,
                            onValueChange = onChatInput,
                            enabled = !generating,
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = Palette.ink),
                            cursorBrush = SolidColor(Palette.brand),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    /*
                     * 发送键在"没输入"时**也保持可点**：点了会给出明确提示。
                     * 灰掉不给反馈的按钮，只会让用户以为坏了。
                     */
                    ModalButton(
                        text = if (generating) "优化中…" else "发送",
                        primary = true,
                        enabled = !generating,
                        onClick = onSend,
                    )
                }

                if (chatHint != null) {
                    Text(
                        text = chatHint,
                        fontSize = 10.sp,
                        color = Palette.alarm,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canUndo) {
                        ModalButton("撤销本次优化", enabled = !generating, onClick = onUndo)
                    }
                    Spacer(Modifier.weight(1f))
                    ModalButton(
                        text = when {
                            generating -> "生成中…"
                            rows.isEmpty() -> "开始作答"
                            else -> "重新生成"
                        },
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

        // ---------- 思考过程（默认折叠）----------
        /*
         * 默认**收起来**：思考过程动辄上千字，一展开就把答案挤到看不见的地方，
         * 而用户打开这个窗口第一眼要看的恰恰是答案。需要时再点开。
         *
         * 折叠状态用 remember 存在弹窗内部：同一次打开里换成"重新生成"也不会自己弹开，
         * 每次重新打开弹窗回到默认的折叠态。
         */
        var thinkingOpen by remember { mutableStateOf(false) }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { thinkingOpen = !thinkingOpen }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "思考过程",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.sub,
            )
            Text(
                text = when {
                    reasoning != null -> "（${reasoning.length} 字）"
                    generating -> "（生成中）"
                    else -> "（无）"
                },
                fontSize = 10.sp,
                color = Palette.weak,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (thinkingOpen) "收起" else "展开",
                fontSize = 10.sp,
                color = Palette.brand,
            )
        }
        if (thinkingOpen) {
            when {
                reasoning != null -> Text(
                    // 不截断：思考内容整段给出来，长了由弹窗自己滚
                    text = reasoning,
                    fontSize = 11.sp,
                    color = Palette.weak,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                generating -> Text(
                    text = "模型正在生成，返回后这里会显示它的推理内容（部分模型不提供）。",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                else -> Text(
                    text = "这次没有返回思考内容（多数模型只在请求里带 reasoning 时才给）。",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ---------- 逐题结果（答案区）----------
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

        // ---------- 优化记录（紧挨底部输入框）----------
        if (chat.isNotEmpty() || generating) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("优化记录")
            chat.forEach { turn -> ChatBubble(turn) }
            if (generating) {
                ChatBubble(
                    ChatTurn(ChatRole.AI, "正在按你的要求修改…（已等待 ${elapsedSec}s）"),
                    pending = true,
                )
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

/** 一轮对话：用户要求靠右、AI 回应靠左、系统注记整行灰字 */
@Composable
private fun ChatBubble(turn: ChatTurn, pending: Boolean = false) {
    when (turn.role) {
        ChatRole.NOTE -> Text(
            text = turn.text,
            fontSize = 10.sp,
            color = Palette.weak,
            lineHeight = 14.sp,
            modifier = Modifier.padding(vertical = 3.dp),
        )

        ChatRole.USER -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = turn.text,
                fontSize = 11.sp,
                color = Palette.ink,
                lineHeight = 16.sp,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.brandSoft)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }

        ChatRole.AI -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text(
                text = turn.text,
                fontSize = 11.sp,
                color = if (pending) Palette.brand else Palette.sub,
                lineHeight = 16.sp,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bg)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
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
