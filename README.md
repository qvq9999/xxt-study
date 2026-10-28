# 学习通助手（Android）

看未完成作业、进课程看作业清单，用自己配的 AI 模型出答案；另外能按课程协议刷完任务点（视频/音频/文档/阅读/章节测验）。原生 Compose 界面 + 内嵌网页版学习通会话。

**边界：不碰考试，作业只出答案不自动提交。** 章节测验会提交——不提交任务点永远是未完成，这是刷课本身的一部分。

---

## 当前进度

| 环节 | 状态 | 说明 |
|---|---|---|
| 登录（内嵌网页登录取 Cookie） | 完成 | 移动端 UA + 手机版登录页；Cookie 经 AndroidKeyStore AES-GCM 加密落盘 |
| 首页未完成作业数量 | 完成 | 逐课串行；**作业列表走隐藏 WebView**（裸 HTTP 会被判未登录，实测） |
| 首页 → 课程列表 | 完成 | 课程列表走 JSON API（实测 41 门课全拿到） |
| 课程 → 作业列表 | 完成 | 数据来自首页那次刷新，进入不再等网络；未交排最前 |
| 题目解析（题型/题干/选项/填空格数） | 完成 | 按 `div.questionLi` 切题；真实 12 题 DOM 已作 fixture |
| 章节目录 + 任务点解析 | 完成 | `knowledge/cards` 打三次 num=0/1/2 合并；mArg 括号配平抠 JSON |
| 视频/音频进度上报 | 完成 | `enc` MD5 签名 + `multimedia/log`，rt 0.9→1 双档降级 |
| 文档 / 阅读任务点 | 完成 | `ananas/job/document`、`ananas/job/readv2` |
| 字体反爬解码 | 完成 | 手写 TrueType 解析（cmap/loca/glyf），hash 与 fontTools 独立实现逐字节一致 |
| 章节测验作答与提交 | 完成 | 字体解码 → AI 整卷作答 → `addStudentWorkNewWeb`；**任一题没答案就不提交** |
| AI 设置页 | 完成 | 10 家服务商预设（MiMo / Agnes 国内·国际 / DeepSeek / 通义 / Kimi / 智谱 / 硅基 / OpenAI / 自定义）+ `GET /models` 扫描可用模型 + 真发请求的「测试连接」 |
| 作业 AI 答题与回填 | 完成 | WebView 读作答页 → 解析 → AI 整卷作答 → 注入 JS 点选/填写；**不点提交**，最后一击由人按 |
| 底部导航 | 完成 | 「课程」/「我的」两个 tab；设置从「我的」进，课程页右上角只留刷新 |
| 课程搜索 | 完成 | 内存过滤，连续子串 + 子序列双级匹配（「高数」能搜到「高等数学」），多关键词空格分隔 |
| 扫描性能 | 完成 | 拦静态资源 + 目标驱动等待 + WebView 池 + 3 路并发 + 12 分钟增量 |
| 刷课日志 | 完成 | **按课程隔离**（`StudyLogs`），切课程不会串上一门的记录；日志直接铺在正文，不再单开窗口 |
| 模型快捷切换 | 完成 | 答题页底部 / 刷课页右上角一键切已保存的服务；设置页另有一份「已保存的服务」列表 |
| 风控验证码 | 完成 | 扫描/刷课/读页面撞上验证码 → 弹出**学习通原版页面**人工完成 → Cookie 导回 → 自动续跑 |
| 作业状态判定 | 完成 | `服务端状态 × 截止时间 × 课程是否结课 × 当前时间` → 已完成/待批阅/待提交/已截止；结课或过期一律不计红点 |
| 内置更新 | 完成 | **国内镜像优先**取 `latest.json` → versionCode 比较 → 弹窗 → 下载（换源 + 流式 sha256）→ 系统安装器；24h 节流 |
| 前台服务保活 | 完成 | `dataSync` 前台服务 + WakeLock 6 小时上限 |
| 真机联调 | 未做 | 当前 `adb devices` 为空，只做到编译 + **171 个单测全过** |

---

## 构建

```bash
export JAVA_HOME="/e/Java jdk"
export ANDROID_HOME="/e/AndroidSDK"
export GRADLE_USER_HOME="/e/gradle-home"

# 日常自测
"/e/gradle98/bin/gradle.bat" :app:assembleDebug :app:testDebugUnitTest

# 发布包（R8 混淆 + 资源收缩 + 签名）
"/e/gradle98/bin/gradle.bat" :app:assembleRelease
```

| 产物 | 体积 | 包名 | 用途 |
|---|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | **2.35 MB** | `com.xxt.study` | **日常使用就用这个** |
| `app/build/outputs/apk/debug/app-debug.apk` | 13.8 MB | `com.xxt.study.debug` | 调试用，可和 release 共存 |

两个包名不同，所以 debug 和 release 能同时装在一台机器上，互不覆盖。

### 签名

- 密钥库：`signing/release.jks`（PKCS12，别名 `xxt-study`，有效期 30 年）
- 密码：项目根 `keystore.properties`（明文，本机自用；**这个文件和 jks 一起备份**，丢了就没法给已安装的包升级）
- 证书 SHA-256：`49bcd2982f584786ee748b564febb50e779c86be1829c4296db3252cd8428682`

`keystore.properties` 不存在时自动跳过签名，`assembleRelease` 产出未签名包而不是直接报错——这样换机器或 CI 上仍能构建。

### 图标

`res/mipmap-anydpi-v26/ic_launcher.xml`（自适应图标，minSdk 26 全覆盖，不需要 PNG）。
前景是矢量的「打开的书 + 书签」，几何全部收在半径 33 的安全圆内，圆形/方形/squircle 遮罩都裁不到。
预览：`.workbuddy/artifact/icon-preview.png`。

环境事实：AGP 9.4.1 / Kotlin 2.4.21 / Compose 1.12.1 / Material3 1.4.0 / minSdk 26 / compileSdk 37 / JDK 25。
工程路径含中文与空格，`gradle.properties` 必须有 `android.overridePathCheck=true`。

---

## 目录

```
app/src/main/java/com/xxt/study/
  App.kt                        Application + 手写依赖容器
  MainActivity.kt               路由（登录 / 首页 / 作业列表 / 刷课 / 设置）
  core/
    crypto/SecretStore.kt       AndroidKeyStore AES-GCM，存 Cookie 与 AI 密钥
    net/CookieStore.kt          OkHttp CookieJar + 持久化 + WebView Cookie 导入
    net/Http.kt                 单入口客户端：PC UA + 全局串行 + 300ms 间隔 + postForm/postJson
    font/TrueTypeFont.kt        最小 TrueType 解析，只为算字形轮廓 hash
    font/CxFont.kt              字体 base64 提取、静态映射表、康熙部首归一
    font/QuizFont.kt            映射表懒加载 + 带命中率的解码器
    ai/AiClient.kt              OpenAI 兼容 /chat/completions，密钥走 SecretStore
    study/StudyController.kt    刷课总控：协程 + StateFlow + WakeLock
    study/StudyService.kt       前台服务，只保活不跑业务
    study/StudySettings.kt      倍速、自动答测验等偏好
    update/UpdateSource.kt      更新源：国内镜像优先，GitHub 兜底（只改 REPO 一处）
    update/UpdateManifest.kt    latest.json 解析 + 日志简写（纯函数，可单测）
    update/UpdateChecker.kt     24h 节流 + 换源检查；独立 OkHttpClient，不带学习通 Cookie
    update/UpdateInstaller.kt   换源下载 + 流式 sha256 + FileProvider 安装
    result/Cx.kt                统一结果类型，UI 层见不到 HTTP 概念
    ui/Theme.kt Icons24.kt      色板、主题、代码内自绘图标
  domain/                       Model / Question / Study（章节、任务点、进度）
  data/
    remote/chaoxing/Parsers.kt      作业页解析，纯函数
    remote/chaoxing/QuestionParser.kt 作业作答页切题
    remote/chaoxing/StudyParsers.kt   章节目录 + mArg 任务点解析
    remote/chaoxing/VideoSign.kt      视频上报签名与 URL 拼装
    remote/chaoxing/QuizParser.kt     章节测验卷面解析
    remote/chaoxing/ChaoxingClient.kt 作业五跳链路 + 四组参数降级
    remote/chaoxing/StudyClient.kt    刷课全部接口
    remote/web/WebWorkFetcher.kt      隐藏 WebView 取作业列表
    local/SnapshotCache.kt            JSON 快照，不引 Room
    repo/StudyRunner.kt               任务点执行器（可单测，不依赖 Android）
    repo/QuizSolver.kt                AI 答案 → 学习通提交值（AnswerMapper 纯函数）
    repo/QuizHandler.kt               测验处理策略接口 + 默认实现
  feature/login|home|work|study|settings/   Compose 页面
```

分层依赖只向下：`ui → controller/repo → datasource`。Screen 不含业务判断，Controller 不碰 OkHttp 细节。

---

## 刷课协议（对齐 SuperAutoStudy，全部改写成 Kotlin）

原项目是 SpringBoot + MySQL + RabbitMQ + Nacos + Dubbo 的云端服务。手机端不需要那套架构：
**它真正干活的代码全在 `CourseUtil` 一个类里，都是裸 HTTP。** 所以这里只移植协议，不移植中间件。

### 一、视频"倍速"的真相

服务端只认上报里自称的 `playingTime`，不校验真实观看。原项目的循环是：

```
上报 playingTime → 真实 sleep 60 秒 → playingTime += random(100,120) × speed → 再上报
```

因此 `speed=3` 实际约等于 5~6 倍速。这里保留同一公式，`speed` 上限卡在 6，默认 3。

### 二、签名算法（最容易悄悄写错的一环）

```
enc = MD5("[clazzId][uid][jobId][objectId][playingTime*1000][d_yHJ!$pdA~5][duration*1000][0_duration]")
```

salt 是服务端定的常量，不是配置。改一个字符服务端就判签名无效，现象是 403。
所以 `VideoSignTest` 的期望值是用**独立的 Python 实现**算出来的，不是从 Kotlin 自己跑出来的。

`rt` 参数先报 `0.9` 再报 `1`：部分课程只认其中一个，403 就换下一个，两次都 403 才算真失败。

### 三、任务点藏在脚本变量里，不在 DOM

`knowledge/cards` 的响应里，任务点在 `mArg={...};` 这个 JS 变量中。原项目用正则懒匹配，遇到嵌套对象会被内层 `};` 截断；
这里改成**花括号配平扫描**（跳过字符串字面量里的括号），并且不压缩空格——压了空格题干会变样。

一个知识点要打三次（num=0/1/2），每屏的 attachments 只含自己那部分，只打一次会漏。

### 四、字体反爬（章节测验）

页面下发一份自定义 TTF，字形轮廓是正常汉字但码位被打乱。解法：

1. 从 `#cxSecretStyle` 取内联 base64 字体
2. 解析 cmap（码位→字形）、loca/glyf（轮廓点集）
3. 归一化 `MD5(拼接 x,y,(flag&1))`，去静态表反查真实码位
4. 康熙部首归一回常用字（`⺠`→`民`）

`TrueTypeFont` 是手写的，只实现 hash 真正需要的三张表（FontBox 那套对这里是几十倍体积）。
正确性用系统 `simhei.ttf` 双向校验：Kotlin 算出的 hash 与 fontTools 算出的**逐字节相同**。

解不出来时不硬撑：命中率低于 80% 直接放弃这道测验，因为把乱码喂给 AI 只会得到一本正经的错误答案。

### 五、章节测验提交

```
POST mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb
form: 卷面隐藏域 + answer<qid> + answertype<qid> + answerwqbid="id1,id2,"
```

末尾那个逗号是服务端要求，别删。测验页结构与作业页**不同**（`div.singleQuesId` vs `div.questionLi`），两套解析器不能混用。

---

## 扫描为什么快 / 曾经为什么慢

41 门课曾经要 3~5 分钟，因为每门课都 **新建 WebView + 加载两个完整页面 + 等 `onPageFinished`**。
`onPageFinished` 的语义是"所有子资源加载完"，比 DOM 就绪晚 1~3 秒，而我们只读一个 hidden input 和几个 `li`。

五条优化（都在代码注释里标了依据）：

| 优化 | 做法 | 收益 |
|---|---|---|
| 拦静态资源 | `shouldInterceptRequest` 对图片/字体/音视频返回空响应；**不拦 JS 和 XHR** | 单页省掉几十个请求 |
| 目标驱动等待 | 每 150ms 轮询 `workEnc` / `li[data]`，出现即走，不等 `onPageFinished` | 单页省 1~3 秒 |
| WebView 池 | 3 个实例复用，省掉 41 次 Chromium 内核初始化 | 省 ~20 秒 |
| 课程并发 | 池容量即并发上限，3 路 | ÷3 |
| 增量扫描 | 12 分钟内取过的课直接复用缓存；**刷新默认走增量，长按刷新才是全量重扫** | 二次打开接近秒开 |
| 抗风控 | 并发 1 路 + 课程间 250~650ms 随机间隔 + 撞上风控后 **5 分钟冷却**（期间不发任何请求） | 明显降低验证码触发率 |

### 池化复用最大的坑：文档还是上一门课的

WebView 复用时，`loadUrl()` 之后的一小段时间内 `document` **仍然是上一门课**。
早期版本靠 `onPageFinished` 置一个布尔量判断"页面换好了"，但：

- 上一门课的 `onPageFinished` 可能在新 `loadUrl` 之后才回来，把标志又置成 true；
- `about:blank` 清场同样会产生这种迟到的回调。

结果是探针在**上一门课的文档**上跑，把它的 `workEnc` 当成这一门课的。
用错的 enc 去请求作业列表 → 返回错误页、没有 `li[data]` → 一路轮询到超时。
表现就是：**首次启动（池子冷、每个 WebView 都是新造的）结果准确，之后每次刷新又慢又错。**

现在不再依赖任何时间点标志，也不靠"URL 长什么样"猜页面类型（试过：真正的落地 URL 未必含
`studentcourse`，猜错一步就是**全部课程等待超时**）。做法是**给旧文档打标记**（`ScanProbes`）：

```
导航前：evaluateJavascript("window.__xxtNav = Date.now()")
        └─ 标记打在"即将被替换掉的那份文档"上
loadUrl(...)
        └─ 新文档不继承 window 上的变量
探针  ：看到 __xxtNav → WAIT（还是旧页面）
        看不到       → 一定是新导航产生的文档，可以下结论
```

细节：

- 标记带 5 秒有效期。万一标记没赶上、迟到落到新文档上，最坏是晚 5 秒下结论，**而不是永久卡死**；
- `about:blank` 一律 WAIT —— 新建 WebView 的初始文档是 complete 且没有 workEnc，
  不挡掉会把每门课都判成"没有 workEnc"；
- 页面 complete 但没有目标元素时**再观察 3 秒**才认，躲开 302 链里的 HTML 中间页；
- 池里的实例失败，会**换一个全新 WebView 再试一次**（新实例没有"残留文档"这个包袱）。
  只有失败的课才付这个代价，成功路径一分钱不花。

`ScanProbesTest` 把这几条约束钉住了（含"不许再按 URL 猜页面类型"）。

## 课程名曾经为什么是空的

JSON 里字段名骗人：

```
channelList[].content
  id              → classId（不是 classid）
  course.data[0]
    id            → courseId（不是 courseid）
    name          → 课程名
```

旧实现只在同一层找 `courseid` + `classid`，这层根本没有这两个名字，所以要么解析不出、要么全是空名字。
现在先走精确路径，拿不到再退回旧的递归兜底。真实样本 41 门课已覆盖在 `CourseListRealDataTest`。

---

## 作业状态怎么判定（"已结课还显示未完成"的根因）

状态不是"读一个字段"这么简单。真实数据里三方信息互相打架：

| 现象 | 真实原因 |
|---|---|
| 已结课仍显示未完成作业 | 结课课程的作业在服务端仍是"未交"，我们照单全收 |
| 已截止仍显示未提交 | `aria-label` 会写成「未交（已过期）」，旧代码先判"未交"，永远轮不到"已过期" |
| 状态展示不对 | 「剩余0分钟」被当成"算不出截止时间"→ `deadline=null` → 保守判"能交" |

所以判定拆成两层，**只有一处口径**（`domain/Model.kt`）：

```
WorkStatus（服务端说了什么）           WorkView（界面该显示什么）
  PENDING  未交                          DONE      已完成
  SUBMITTED 已交/待批阅        +  时间  SUBMITTED 待批阅
  GRADED    已批阅            +  结课  OPEN      可提交
  OVERDUE   已截止            +  现在  EXPIRED   已截止
  UNKNOWN   没认出来                     UNKNOWN   待确认
```

规则（`Work.view(now, courseClosed)`）：

1. 服务端明说已截止 → `EXPIRED`
2. 知道截止时间 → 以时间为准（缓存 12 分钟后再看也不会算错）
3. 不知道截止时间 + 课程已结课 → `EXPIRED`（结课后补交通道已经关了）
4. 不知道截止时间 + 课程在上 → `OPEN`（**宁可多提醒**，用户明确选了这个）
5. 状态词没认出来 → `UNKNOWN`，**同样计入待办**，但界面写"待确认"

红点只认 `countsAsTodo = OPEN || UNKNOWN`。已截止、已完成、结课课程的遗留"未交"一律不打扰。

### 结课怎么认

课程列表 JSON 里两个字段：`content.state == 1` 或 `course.data[0].coursestate == 1`。

41 门真实样本里只有 5 门命中，全是旧学期课（羽毛球、军事理论、思想道德与法治、国家安全教育、从爱因斯坦到霍金的宇宙），
而在上的课（23 级高数、Java 高级编程…）都是 0 —— 语义方向可信。

`isretire` **不能用**：41 门里 40 门都是 1，没有区分度。

### 三个连带修正

- **状态词表顺序**：已批阅 → 已交 → 已截止 → 未交。前两个是"已经发生的事实"，不该被"已截止"覆盖；"已截止"必须排在"未交"前面。
- **`remainMillis` 与 `parseDeadline` 分开**：前者返回 `null` 表示"没有剩余字样"，返回 `0` 表示"有剩余但归零（已截止）"。混成一个函数就分不清"没截止时间"和"已经截止"。
- **复用缓存时重算待办数**：12 分钟保鲜期内可能跨过截止时间，照抄旧数字会让红点在"已经交不上"的作业上多挂一会儿。

升级到 0.6.0 会**清一次快照缓存**（`App.purgeSnapshotOnUpgrade`）：旧缓存存的是旧口径算出的 `status`/`todoCount`，
留着它第一次打开还是错的。代价是首次打开要全量重扫一遍，换取状态立刻正确。

---

## 内置更新

```
启动 5 秒后（登录后） → latest.json（镜像优先） → versionCode 比较
      → 弹窗【更新】【稍后】 → 下载（换源 + 流式 sha256） → FileProvider → 系统安装器
```

### 更新源顺序

用户要求默认走国内镜像，所以**直连 GitHub 只作最後兜底**（`core/update/UpdateSource.kt`）：

```
https://ghproxy.net/<原始 GitHub 地址>      ← 2026-10-28 实测 200
https://gh-proxy.com/<原始 GitHub 地址>     ← 2026-10-28 实测 200
https://ghfast.top/<原始 GitHub 地址>       ← 2026-10-28 实测 000（连不上，留作复活备用）
<原始 GitHub 地址>                          ← 全挂了才走这条
```

顺序是**逐个 curl 过**才定的，不是抄来的推荐列表：`ghfast.top` 本来排第一，实测 000，
排第一的话每次检查都要先等 8 秒超时，用户会感觉"检查更新很慢"，所以挪到最后。

清单和 APK 都用同一套前缀拼接。一个源连不上/返回 HTML 错误页/校验不过，就换下一个——
镜像缓存旧包是常见情况，`sha256` 不一致时换源重下通常就好了。

### 清单字段（`update/latest.json`）

```json
{
  "versionCode": 16,
  "versionName": "0.6.0",
  "sizeBytes": 2590000,
  "sha256": "<apk 的 sha256>",
  "notes": ["作业状态修正", "内置更新", "升级清一次缓存"],
  "packageName": "com.xxt.study",
  "url": "https://github.com/<owner>/<repo>/releases/download/v0.6.0/app-release.apk"
}
```

- 只比 `versionCode`，不比 `versionName` 字符串（`0.9.0` vs `0.10.0` 比字符串会错）
- `packageName` 不符 → 整份清单作废（装错包比不更新严重得多）
- `sizeBytes` / `sha256` 有一处不符 → 不装，换源重下

### 更新日志一律简写

这是硬约束，两端都收口：

- **软件内弹窗**：`UpdateManifest.shortNotes()` 最多 **3 条**、每条 **36 字**，超出截断加省略号
- **GitHub 发布页**：`tools/release-update.mjs` 拒绝超过 **20 字**的条目，并把同一份文案写进 Release notes

两处用同一份数据，说法不会分叉。

### 节流与例外

| 规则 | 值 | 为什么 |
|---|---|---|
| 自动检查间隔 | 24 小时 | 更新不是秒级的事，一天一次足够 |
| 启动延迟 | 5 秒 | 让冷启动那波课程列表请求先飞完，别抢带宽 |
| 风控冷却期 | 不检查 | 风控按请求特征累积，这时候再冒新域名只会更糟 |
| 点过"稍后"的版本 | 不再自动弹 | 手动检查仍然会告诉他，否则用户以为按钮坏了 |
| 手动检查 | 不受以上限制 | 除了风控冷却 |

### 为什么不用 DownloadManager

1. **没法换源**：镜像挂了只能整次失败；
2. **没法流式校验**：只告诉你"下完了"，想比 sha256 还得把文件再读一遍；
3. 部分 ROM 上它的通知会被拦，进度反而看不见。

自己下就三件事：按顺序换源、边下边算 sha256、下完丢给 FileProvider。包只有 2 MB 出头，进度条在弹窗里显示就够了。

### 发版

```bash
"/e/gradle98/bin/gradle.bat" :app:assembleRelease
node tools/release-update.mjs "作业状态修正" "内置更新" "升级清一次缓存"
git add update/latest.json && git commit -m "release: v0.6.0" && git push
```

脚本从 `app/build.gradle.kts` 读版本号（唯一来源，不手抄）、算 size/sha256、写 `update/latest.json`，
有 `gh` CLI 就顺手建 Release 并传包。**不自动 commit**——发版是要人确认的事。

---

## 获取作业的关键（这是以前项目卡死的地方）

学习通没有公开接口。实测（2026-10-27，真实账号 41 门课）三条硬结论：

**一、裸 HTTP 拿不到课程页，作业列表必须在 WebView 里取。**
同一条 `visit/stucoursemiddle` URL，浏览器能 302 到课程页并带出 `workEnc`；Node/OkHttp 即使 Cookie 完全一致也返回登录页。所以 `WebWorkFetcher` 用隐藏 WebView 走两跳。

**二、`enc` 与 `stuenc` 是两个不同的值。**

```
GET mooc1.chaoxing.com/mooc2/work/list
    ?courseId&classId&cpi&ut=s&t=<当前毫秒>
    &stuenc=<课程页 URL 里的 enc>
    &enc=<课程页隐藏 input #workEnc 的值>
```

填错任一 → 「无权限的操作！」。课程页 `#workEnc` 只存在于浏览器上下文，这是必须用 WebView 的根本原因。

**三、`div.TiMu` 是整卷容器，不是单题。**
1 个 `.TiMu` 里装着 12 个 `div.questionLi[typename][data=<qid>]`。按 `.TiMu` 切题只能拿到第 1 题，答案必然串题。

完整分析与复现脚本见 `docs/作业获取方案-制作步骤.md` 与 `tools/`。

---

## 验证

```bash
"/e/gradle98/bin/gradle.bat" :app:testDebugUnitTest
```

171 个单测，覆盖十八个测试类：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `ParsersTest` | 20 | 隐藏 input 属性顺序、作业行过滤、状态归一优先级、`剩余0分钟`、课程双源 |
| `WorkStateTest` | 11 | **状态机**：截止时间 × 结课 × 当前时间的 11 种组合，以及课程结课字段解析 |
| `QuestionParserTest` | 13 | 作业页切题（真实 12 题 DOM） |
| `QuizParserTest` | 18 | 测验页切题、选项字母分离、AI 答案映射（含判断题正反） |
| `FillScriptTest` | 11 | 回填 JS 生成：单选/多选/填空/简答，先点后填 |
| `StudyParsersTest` | 10 | 章节目录、mArg 嵌套抠取、任务点分流与去重 |
| `ScanProbesTest` | 10 | 探针：旧文档标记、`about:blank` 一律 WAIT、不许按 URL 猜页面 |
| `VerifyDetectionTest` | 10 | 风控页识别：JSON 不算页面、泛词必须带"请"、登录页不误判 |
| `DeadlineTest` | 9 | 剩余时间换算、跨过截止后红点自己消失 |
| `LengthRuleTest` | 9 | 从题干里解析字数要求 |
| `StudyLogsTest` | 8 | 刷课日志按课程隔离 |
| `UpdateManifestTest` | 12 | **内置更新**：清单解析、坏清单作废、日志简写、镜像优先级 |
| `AiPromptTest` | 7 | 提示词拼装与字数约束 |
| `CourseSearchTest` | 7 | 模糊搜索：连续子串 + 子序列 + 多关键词 |
| `VideoSignTest` | 6 | 签名与独立 Python 实现一致、URL 参数齐全、倍速系数 |
| `TrueTypeFontTest` | 5 | 字形 hash 与 fontTools 一致、损坏数据不崩 |
| `CookieDomainTest` | 4 | Cookie 域不能带前导点（否则登录永远失败） |
| `CourseListRealDataTest` | 1 | 41 门真实课程 JSON 全量回归 |

真机链路探针（需自己填 Cookie）：

```bash
"C:/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-6/node.exe" tools/probe-homework.mjs
```

---

## 已知取舍

- **不引 Room / Retrofit / Hilt / Coil / Navigation**：接口不到二十个，手写请求、手写容器、一个状态变量做路由，各省一个依赖。
- **不引 FontBox**：字体反爬只需要三张表和一次 MD5，手写 300 行比拖 3 MB 依赖划算。
- **不引 AI SDK**：只认 OpenAI 兼容的 `/chat/completions`，通义、DeepSeek、Kimi、ollama 都能填。
- **业务不放前台服务里**：服务被系统重建时不该把任务从零点重跑一遍；业务在协程里跟 Application 活，服务只保活。
- **中断后重跑是安全的**：`isPassed=true` 的任务点在解析阶段就被过滤，不会重复上报。
- **不乱猜答案**：AI 答不出的题留空、整卷不提交；单选给了不存在的字母直接判无效。宁可少刷一个任务点，不制造假结果。
