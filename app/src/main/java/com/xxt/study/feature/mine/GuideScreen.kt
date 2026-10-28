package com.xxt.study.feature.mine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
                fontWeight = FontWeight.Bold,
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
            Section("start", "30 秒上手", openSet, ::toggle) {
                Step("1", "授权与更新", "首次打开会给「通知」「安装应用」两项，跳过也能用，之后在「我的 → 权限」补。更新在「我的 → 检查更新」，走国内镜像，下载中关掉弹窗不会停。")
                Step("2", "登录", "手机号或扫码登录，走学习通官方登录页。密码不经过本机，只在本机加密保存登录凭据。")
                Step("3", "看课程", "首页一张主卡：待交份数、覆盖课数、刷新时间，数据较旧时同一张卡里会标出来。下面是课程列表，置顶的在最前。")
                Step("4", "四个手势", "点课程进课程页 · 长按课程置顶或取消 · 行尾刷新键只刷这一门 · 右上角点一下增量刷新，长按是全量重扫。")
                Step("5", "换外观", "「我的 → 设置 → 外观」可选跟随系统 / 浅色 / 深色。深色背景是纯黑，OLED 更省电。")
            }

            Section("course", "课程页：任务 / 章节 / 作业", openSet, ::toggle) {
                Step("1", "任务", "这门课发过的课堂活动，目前接的是随堂练习。点一条看题目，可以让 AI 作答后再交。")
                Step("2", "章节", "刷课在这里：任务点、实时页面、运行日志都在同一页，右上角能换 AI 模型、刷新页面。")
                Step("3", "作业", "这门课的作业列表。右上角「刷新」只重拉这一门，不动其它课。")
                Step("4", "顶栏", "返回键旁是课程名，右边显示这门课当前还能交的份数。三个页签随时可切换，切走不会中断正在跑的刷课。")
            }

            Section("practice", "随堂练习", openSet, ::toggle) {
                Step("1", "能看到什么", "只列随堂练习这一种活动（进行中或已结束）。没签到、被老师限制时取卷会报错，界面上会写原因。")
                Step("2", "AI 作答", "点开卷面 →「AI 作答」。整卷一次问完；返回的格式坏了会自动退成逐题作答，不会一题坏全卷空。")
                Step("3", "逐题改", "每题一个独立输入框，多道简答互不串答案；多空填空题按「每行一空」拆开写。")
                Step("4", "已交过的", "再打开会把上次的答案填回框里，副标题带「· 已作答」，左边按钮变成「修改」，右边提交会覆盖上一次。")
                Step("5", "提交", "点两次才真发：第一次变成「确认提交 N 题」。老师没开修改时底部会提示重交可能被拒绝。")
            }

            Section("study", "刷课", openSet, ::toggle) {
                Step("1", "入口", "课程页 →「章节」→「开始刷课」。")
                Step("2", "倍速固定", "按平台上限 ${StudyLimits.rateLabel} 执行。上报进度始终等于「真实经过时间 × 倍速」，不做假速度。")
                Step("3", "界面", "上方是实时页面（能直接看到任务点状态在变），下方是运行日志。章节测验会自动作答，任一题没答案就不提交。")
                Step("4", "后台与停止", "可以切后台，通知栏显示进度；要停就在通知栏或页面里点「停止」，返回不会中断任务。")
            }

            Section("ai", "配 AI（做作业 / 随堂练习前必做）", openSet, ::toggle) {
                Text(
                    text = "软件本身不带 AI，需要你自己的模型接口。四步：选服务商 → 填 Key → 扫描模型 → 测试连接并保存。每个服务商独立保存，切换不覆盖。",
                    fontSize = 12.sp,
                    color = Palette.sub,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "去哪里拿 Key（点右侧「复制」）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Spacer(Modifier.size(8.dp))
                KEY_SOURCES.forEach { src -> KeyRow(src, ctx) }
            }

            Section("answer", "AI 做作业", openSet, ::toggle) {
                Step("1", "开始作答", "打开作业 → 点「AI 作答」。窗口里能看到四个阶段：读取作答页 → 解析题目 → 生成答案（带等待秒数）→ 回填。哪步失败会标红。")
                Step("2", "看逐题结果", "✓ 表示页面已接受，✗ 表示没填上（多为编辑器类型特殊，手补一句即可）。题干与答案完整显示，长了在窗口里往上滚。")
                Step("3", "不满意就直说", "底部输入框用一句话提要求，例如「简答再短一点」「第 3 题改成要点式」，可多轮；【撤销本次优化】回上一版，【重新生成】清空重来。")
                Step("4", "提交由你点", "作业只把答案回填到作答页，提交那一下由你自己点。随堂练习不同：它的提交在 App 里，且要点两次。")
                Step("5", "再点按钮", "关窗后按钮变成「查看作答」，再点只是回看，不会重复请求。思考过程默认折叠，点开才显示。")
            }

            Section("faq", "常见问题", openSet, ::toggle) {
                Faq("任务页里没有随堂练习", "只列这门课已发布的活动。网页端刚发出来的，退出课程页重进一次即可；一直空就说明这门课确实没发过。")
                Faq("打开已交的练习是空框", "先看副标题有没有「· 已作答」。有：服务端存了但我没解析出来，把卷面发我；没有：那次提交没落库，需要重新交。")
                Faq("提示老师未开启修改", "服务端标记这份只读，网页端同样只能看。这时重交会被拒，属于老师侧设置，不是软件问题。")
                Faq("AI 报「没按要求输出 JSON」", "随堂练习答案长、容易把 JSON 写坏。已做自动修复与逐题兜底；仍报错就换个模型（设置 → AI）。")
                Faq("卡在登录网页", "顶部小字会标出卡在哪一步，按提示操作，或点「重试」。")
                Faq("弹出「需要完成验证」", "触发了风控。窗口里就是学习通原版页面，手动过验证后点继续即可。")
                Faq("提示「冷却 X 分」或「X 秒内刚刷过」", "防连点保护：冷却期内一个请求都不发，等计时结束。")
                Faq("想换个模型", "不用回设置页：答题页底部、「章节」页右上角都能直接切已保存的服务商。")
                Faq("置顶的课去哪了", "排在列表最前，不参与「暂无待办」的折叠。")
            }

            Section("risk", "边界与风险", openSet, ::toggle) {
                Text(
                    text = "· 只处理课程任务点（视频 / 音频 / 文档 / 阅读 / 章节测验）与随堂练习，不碰考试。\n" +
                        "· 作业只回填答案，提交由你自己点；随堂练习的提交在 App 内完成，需要连点两次确认。\n" +
                        "· 刷课与自动作答都违反学习通用户协议，风险由使用者承担。",
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
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.card)
            .border(1.dp, Palette.line, RoundedCornerShape(18.dp)),
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
