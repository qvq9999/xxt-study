package com.xxt.study.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.ai.AiProviders
import com.xxt.study.core.result.Cx
import com.xxt.study.core.study.StudyLimits
import com.xxt.study.core.study.StudySettings
import com.xxt.study.core.ui.Palette
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 设置页。
 *
 * 配 AI 有三个坑，页面分别对应：
 * 1. **地址填错** → 预设胶囊一点就填好，不用记
 * 2. **模型名记不住** → 「扫描模型」直接向服务商要列表，点一下选中
 * 3. **配完不知道通不通** → 「测试连接」真发一次请求，只说结论不说术语
 */
@Composable
fun SettingsScreen(
    ai: AiClient,
    settings: StudySettings,
    onBack: () -> Unit,
) {
    // 每个预设各存一份配置，进来先看"当前用的是哪家"以及它自己的草稿
    val initialId = remember { ai.activeProviderId() }
    val initialDraft = remember { ai.draft(initialId) }

    var providerId by remember { mutableStateOf(initialId) }
    var baseUrl by remember { mutableStateOf(initialDraft?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initialDraft?.apiKey.orEmpty()) }
    var model by remember { mutableStateOf(initialDraft?.model.orEmpty()) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var autoQuiz by remember { mutableStateOf(settings.autoQuiz) }

    // 已保存的服务：读密钥库，用版本号缓存；保存/切换后 +1 让列表重算
    var savedTick by remember { mutableStateOf(0) }
    val savedServices = remember(savedTick) { ai.savedProviders() }
    val activeNow = remember(savedTick) { ai.activeProviderId() }

    val scope = rememberCoroutineScope()
    val provider = AiProviders.byId(providerId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        SettingsHeader(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            SettingsCard(title = "AI 答题接口") {
                Text("服务商", fontSize = 12.sp, color = Palette.sub)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    AiProviders.all.forEach { p ->
                        SelectChip(text = p.label, selected = p.id == providerId) {
                            providerId = p.id
                            message = null
                            models = emptyList()
                            // 切到别家：把这家的草稿读出来；没配过才用预设默认值。
                            // 关键是不能清空——否则换个预设再换回来，Key 就没了
                            val d = ai.draft(p.id)
                            baseUrl = d?.baseUrl?.takeIf { it.isNotBlank() } ?: p.baseUrl
                            apiKey = d?.apiKey.orEmpty()
                            model = d?.model?.takeIf { it.isNotBlank() } ?: p.defaultModel
                        }
                    }
                }
                provider?.hint?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, fontSize = 11.sp, color = Palette.weak, lineHeight = 17.sp)
                }

                Spacer(Modifier.height(14.dp))
                SettingsField(
                    label = "Base URL（填到 /v1 为止）",
                    value = baseUrl,
                    placeholder = "https://api.xiaomimimo.com/v1",
                    onChange = { baseUrl = it; message = null },
                )
                Spacer(Modifier.height(10.dp))
                SettingsField(
                    label = "API Key",
                    value = apiKey,
                    placeholder = "sk-...",
                    password = true,
                    onChange = { apiKey = it; message = null },
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SettingsField(
                            label = "模型名",
                            value = model,
                            placeholder = "点右边「扫描」自动列出",
                            onChange = { model = it; message = null },
                        )
                    }
                    Row(Modifier.padding(start = 8.dp)) {
                        SettingsButton(
                            text = if (scanning) "扫描中…" else "扫描模型",
                            primary = false,
                            enabled = !scanning && apiKey.isNotBlank() && baseUrl.isNotBlank(),
                            onClick = {
                                scanning = true
                                message = null
                                scope.launch {
                                    when (val r = ai.listModels(baseUrl, apiKey)) {
                                        is Cx.Ok -> {
                                            models = r.data
                                            if (model !in r.data) model = r.data.first()
                                            message = "扫到 ${r.data.size} 个模型，点下面的名字可切换"
                                        }
                                        is Cx.Fail -> message = r.detail
                                        Cx.NeedLogin -> message = "扫描不需要登录态"
                                    }
                                    scanning = false
                                }
                            },
                        )
                    }
                }

                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ChipFlow {
                        models.forEach { m ->
                            SelectChip(text = m, selected = m == model) { model = m }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsButton(
                        text = if (testing) "测试中…" else "测试连接",
                        primary = false,
                        enabled = !testing,
                        onClick = {
                            testing = true
                            message = null
                            scope.launch {
                                message = validate(baseUrl, apiKey, model)
                                    ?: ai.probe(AiClient.Config(baseUrl.trim(), apiKey.trim(), model.trim()))
                                testing = false
                            }
                        },
                    )
                    SettingsButton(
                        text = "保存",
                        primary = true,
                        onClick = {
                            val err = validate(baseUrl, apiKey, model)
                            if (err != null) {
                                message = err
                            } else {
                                val ok = ai.saveFor(
                                    providerId,
                                    AiClient.Config(baseUrl.trim(), apiKey.trim(), model.trim()),
                                )
                                if (ok) savedTick++
                                message = if (ok) {
                                    "已保存到「${provider?.label ?: providerId}」，并已设为当前使用；其它预设的配置没动"
                                } else {
                                    "保存失败：系统密钥库不可用"
                                }
                            }
                        },
                    )
                }

                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = it, fontSize = 12.sp, color = Palette.sub, lineHeight = 18.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            SettingsCard(title = "已保存的服务") {
                Text(
                    text = "这里列出所有**配齐了三项**（地址 / Key / 模型）的服务。点一个就切过去，" +
                        "答题页和刷课页会立刻使用它；只切当前用哪个，不会改动任何配置。",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))

                if (savedServices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.bg)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "还没有配好任何一个服务。把上面的地址、Key、模型填齐后点「保存」，这里就会出现它。",
                            fontSize = 12.sp,
                            color = Palette.sub,
                            lineHeight = 18.sp,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedServices.forEach { item ->
                            val active = item.id == activeNow
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) Palette.brandSoft else Palette.bg)
                                    .clickable {
                                        ai.setActive(item.id)
                                        savedTick++
                                        message = "已切换到「${item.label}」"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (active) Palette.brand else Palette.ink,
                                    )
                                    Text(
                                        text = item.model,
                                        fontSize = 11.sp,
                                        color = Palette.weak,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Text(
                                    text = if (active) "使用中" else "切换",
                                    fontSize = 12.sp,
                                    color = if (active) Palette.brand else Palette.sub,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            SettingsCard(title = "刷课") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("倍速", fontSize = 13.sp, color = Palette.ink, modifier = Modifier.weight(1f))
                    Text(
                        text = StudyLimits.rateLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.brand,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "固定按学习通允许的最高倍速执行，不再提供自定义档位。\n" +
                        "上报的进度始终等于「真实经过的时间 × 平台倍速」，" +
                        "不会出现平台播放器根本做不到的速度。",
                    fontSize = 11.sp,
                    color = Palette.weak,
                    lineHeight = 17.sp,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动作答章节测验", fontSize = 13.sp, color = Palette.ink)
                        Text(
                            text = "题目先过字体反爬解码，再交给 AI；任一题没答案就不提交",
                            fontSize = 11.sp,
                            color = Palette.weak,
                            lineHeight = 16.sp,
                        )
                    }
                    Switch(
                        checked = autoQuiz,
                        onCheckedChange = {
                            autoQuiz = it
                            settings.autoQuiz = it
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            SettingsCard(title = "边界与风险") {
                Text(
                    text = "· 只处理课程任务点（视频/音频/文档/阅读/章节测验），不碰考试。\n" +
                        "· 作业只把答案回填到作答页，提交那一下由你自己点。\n" +
                        "· 刷课违反学习通用户协议，异常上报可能被判定为异常学习；\n" +
                        "  这个风险由使用者承担，软件只是把协议跑了一遍。",
                    fontSize = 12.sp,
                    color = Palette.sub,
                    lineHeight = 19.sp,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun validate(baseUrl: String, apiKey: String, model: String): String? {
    if (baseUrl.isBlank()) return "Base URL 不能为空"
    if (apiKey.isBlank()) return "API Key 不能为空"
    if (model.isBlank()) return "模型名不能为空（点「扫描模型」可列出可用模型）"
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return "Base URL 不是合法地址"
    if (uri.scheme != "http" && uri.scheme != "https") return "Base URL 必须以 http(s):// 开头"
    if (uri.host.isNullOrBlank()) return "Base URL 缺少主机名"
    if (baseUrl.trim().endsWith("/chat/completions")) return "Base URL 填到 /v1 为止，不要带 /chat/completions"
    return null
}
