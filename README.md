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
| 前台服务保活 | 完成 | `dataSync` 前台服务 + WakeLock 6 小时上限 |
| 真机联调 | 未做 | 当前 `adb devices` 为空，只做到编译 + **85 个单测全过** |

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
| 增量扫描 | 12 分钟内取过的课直接复用缓存，用户手点刷新才强制全量 | 二次打开接近秒开 |

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

71 个单测，覆盖六组：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `ParsersTest` | 19 | 隐藏 input 属性顺序、作业行过滤、状态归一优先级、课程双源、无年份日期 |
| `QuestionParserTest` | 13 | 作业页切题（真实 12 题 DOM） |
| `StudyParsersTest` | 10 | 章节目录、mArg 嵌套抠取、任务点分流与去重 |
| `VideoSignTest` | 6 | 签名与独立 Python 实现一致、URL 参数齐全、倍速系数 |
| `QuizParserTest` | 18 | 测验页切题、选项字母分离、AI 答案映射（含判断题正反） |
| `TrueTypeFontTest` | 5 | 字形 hash 与 fontTools 一致、损坏数据不崩 |

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
