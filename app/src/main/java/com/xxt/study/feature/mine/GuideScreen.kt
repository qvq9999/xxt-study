package com.xxt.study.feature.mine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.study.StudyLimits
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette

/** Key 获取地址：一行一个，右侧带复制按钮 */
private data class KeySource(val name: String, val url: String, val note: String)

private val KEY_SOURCES = listOf(
    KeySource("MiMo（小米）", "https://platform.xiaomimimo.com", "Key 以 sk- 开头"),
    KeySource("Agnes 国内站", "https://platform.agnes-ai.cn", "文本模型目前免费"),
    KeySource("DeepSeek", "https://platform.deepseek.com", "便宜稳定，推荐"),
    KeySource("通义千问", "https://bailian.console.aliyun.com", "阿里云账号登录"),
    KeySource("Kimi", "https://platform.moonshot.cn", "月之暗面"),
    KeySource("智谱 GLM", "https://open.bigmodel.cn", "glm-4-flash 有免费额度"),
    KeySource("硅基流动", "https://cloud.siliconflow.cn", "聚合站，模型多"),
    KeySource("OpenAI", "https://platform.openai.com", "需要能直连外网"),
)

/**
 * 使用说明。
 *
 * 分成可折叠的几节：手机上一次性铺满整屏文字没人看，默认只展开第一节。
 * Key 获取地址做成**点一下复制**——用户拿到地址还要去浏览器里手敲，
 * 这一步纯属折腾人。
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var openSet by remember { mutableStateOf(setOf("start")) }

    fun toggle(id: String) {
        openSet = if (id in openSet) openSet - id else openSet + id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons24.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(22.dp),
                    tint = Palette.sub,
                )
            }
            Text(
                text = "使用说明",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Section("start", "第一次使用", openSet, ::toggle) {
                Step("1", "登录", "用手机号或扫码登录学习通。走的是官方登录页，密码不经过本软件，本机只保存登录凭据（系统密钥加密）。")
                Step("2", "看课程", "首页列出全部课程。右侧红字是「现在还能交的作业份数」；已截止的不显示红点，只标「已截止」。")
                Step("3", "进课程", "首页点课程看作业清单，已按「待提交 / 已截止 / 已完成」分好，点标题可折叠。")
            }

            Section("study", "刷课", openSet, ::toggle) {
                Step("1", "开始", "课程页右上角点「刷课」，再点「开始刷课」。")
                Step("2", "倍速", "固定按学习通允许的最高倍速 ${StudyLimits.rateLabel} 执行，不可调。上报进度始终等于「真实经过的时间 × 平台倍速」，不会出现播放器做不到的速度。")
                Step("3", "看进度", "页面分两栏：**实时页面**显示课程主页（能直接看到任务点状态在变），**运行日志**逐条记录处理结果。")
                Step("4", "后台", "可以切后台，通知栏显示进度。返回不会停止任务，要停在通知栏或页面里点「停止」。")
                Step("5", "章节测验", "会自动作答并显示 AI 处理过程（解析 → 作答 → 提交）。**任一题没拿到答案就不提交**，避免交出低分卷。可在设置里关掉自动作答。")
            }

            Section("ai", "配置 AI（做作业前必做）", openSet, ::toggle) {
                Text(
                    text = "软件本身不带 AI，需要你自己准备一个模型接口。",
                    fontSize = 12.sp,
                    color = Palette.sub,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.size(10.dp))
                Step("1", "选服务商", "「我的 → 设置 → AI 答题接口」，点一个服务商。推荐 DeepSeek 或 Agnes 国内站。")
                Step("2", "填 Key", "粘贴 API Key。")
                Step("3", "扫描模型", "点「扫描模型」，几秒后列出可用模型，点一个选上。")
                Step("4", "测试", "点「测试连接」，出现「连接正常」即配置成功。")
                Step("5", "保存", "点「保存」。每个服务商独立保存，切换不会覆盖彼此的 Key。")

                Spacer(Modifier.size(14.dp))
                Text(
                    text = "去哪里拿 Key（点地址可复制）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Spacer(Modifier.size(8.dp))
                KEY_SOURCES.forEach { src -> KeyRow(src, ctx) }
            }

            Section("answer", "用 AI 做作业", openSet, ::toggle) {
                Step("1", "打开作业", "点进一份作业，页面和网页版一致，支持双指缩放。")
                Step("2", "第一次点「AI 作答」", "会展示生成过程，逐题把答案填进输入框。")
                Step("3", "再点一次", "按钮变成「查看作答」，**不会重新请求**，直接打开结果窗口回看已答内容。")
                Step("4", "窗口里的两个按钮", "「生成」只补没答上的题，已答好的不动；「重新生成」清空重来，会重新消耗额度。")
                Step("5", "自己提交", "关掉窗口，核对后**自己点提交**。软件不会替你提交作业。")
            }

            Section("faq", "常见问题", openSet, ::toggle) {
                Faq("登录后卡在网页", "顶部小字会变红显示卡在哪一步，按提示操作；也可以点「重试」。")
                Faq("课程名显示「未命名课程」", "课程列表走了备用数据源，顶部会红字提示。点刷新重试。")
                Faq("某门课报「没有 workEnc」", "该课可能已结课或权限受限。会自动重试一次，仍失败则跳过该课。")
                Faq("简答题显示了但没填进去", "结果窗口里那题会标 ✗，说明页面接受了但编辑器类型特殊，可手动补。")
                Faq("字数不达标", "题干要求 150-250 字时，AI 会先校验再单题重写（最多两次）；仍不达标会在窗口标「实际 N 字」。")
                Faq("弹出了「需要完成验证」", "学习通触发了风控。窗口里是**学习通原版页面**，手动输验证码/拖滑块后点「已完成验证，继续」，扫描或刷课会自动接着跑。")
                Faq("想换个 AI 模型", "不用回设置页。答题页底部、刷课页右上角都有模型入口，点一下就能切换已保存的服务。")
                Faq("什么时候会全量重扫", "平时点刷新只重扫过期的课；**长按刷新**才是全区全量。频繁全量最容易触发风控，别当常规操作。")
                Faq("提示「冷却 X 分 Y 秒」", "刚撞过风控，这期间软件一个请求都不发，等计时结束即可——越刷只会越紧。")
            }

            Section("risk", "边界与风险", openSet, ::toggle) {
                Text(
                    text = "· 只处理课程任务点（视频 / 音频 / 文档 / 阅读 / 章节测验），不碰考试。\n" +
                        "· 作业只把答案回填到作答页，提交那一下由你自己点。\n" +
                        "· 刷课违反学习通用户协议，异常上报可能被判定为异常学习，风险由使用者承担。",
                    fontSize = 12.sp,
                    color = Palette.sub,
                    lineHeight = 19.sp,
                )
            }

            Spacer(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun Section(
    id: String,
    title: String,
    openSet: Set<String>,
    onToggle: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val open = id in openSet
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(id) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (open) "收起" else "展开",
                fontSize = 11.sp,
                color = Palette.brand,
            )
        }
        if (open) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun Step(no: String, title: String, body: String) {
    Row(modifier = Modifier.padding(bottom = 10.dp)) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Palette.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = no, fontSize = 10.sp, color = Palette.brand, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
            Text(
                text = body,
                fontSize = 11.sp,
                color = Palette.sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun Faq(q: String, a: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(text = q, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
        Text(
            text = a,
            fontSize = 11.sp,
            color = Palette.sub,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** 地址 + 一键复制：用户拿到地址还要手敲进浏览器，纯属折腾 */
@Composable
private fun KeyRow(src: KeySource, ctx: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = src.name, fontSize = 12.sp, color = Palette.ink)
            Text(
                text = src.url.removePrefix("https://"),
                fontSize = 10.sp,
                color = Palette.weak,
            )
            Text(text = src.note, fontSize = 10.sp, color = Palette.weak)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.brandSoft)
                .clickable {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", src.url))
                    Toast.makeText(ctx, "已复制 ${src.name} 的地址", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("复制", fontSize = 11.sp, color = Palette.brand, fontWeight = FontWeight.Medium)
        }
    }
}
