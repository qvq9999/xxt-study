package com.xxt.study.data.repo

import com.xxt.study.core.font.QuizFont
import com.xxt.study.core.result.Cx
import com.xxt.study.data.remote.chaoxing.QuizParser
import com.xxt.study.data.remote.chaoxing.StudyClient
import com.xxt.study.domain.CardDefaults
import com.xxt.study.domain.Course
import com.xxt.study.domain.Job

/** 章节测验的作答策略，抽成接口是为了让 [StudyRunner] 不依赖 Android（能单测） */
interface QuizHandler {
    /**
     * @param onProgress 给界面看的一句话进度（"解析到 5 题""AI 作答中""回填中"…）。
     *        章节测验是全自动跑的，如果不把过程透出来，用户只能看到"卡住了"。
     */
    suspend fun handle(
        course: Course,
        job: Job,
        html: String,
        defaults: CardDefaults,
        onProgress: (String) -> Unit = {},
    ): Cx<String>
}

/**
 * 默认实现：字体解码 → 解析卷面 → AI 作答 → 提交。
 *
 * 两级闸门，都是为了不出假结果：
 * 1. **字体解码命中率不足 80% 就放弃**。解不出来的字会变成乱码，
 *    喂给 AI 只会得到一本正经的错误答案，比直接报错糟糕得多。
 * 2. **有题没拿到答案也放弃整卷**。学习通的测验提交是不可撤回的，
 *    留空交上去等于自己把分数扔了。宁可让用户去网页上手动补。
 */
class DefaultQuizHandler(
    private val client: StudyClient,
    private val font: QuizFont,
    private val solver: QuizSolver,
) : QuizHandler {

    override suspend fun handle(
        course: Course,
        job: Job,
        html: String,
        defaults: CardDefaults,
        onProgress: (String) -> Unit,
    ): Cx<String> {
        val decoder = font.decoderFor(html)
        val page = QuizParser.parse(html) { text -> decoder?.decode(text) ?: text }

        if (page.isEmpty) {
            return Cx.Fail(Cx.Kind.PARSE, "测验页没解析出题目，可能已提交过或页面改版")
        }
        if (decoder != null && !decoder.usable) {
            val pct = (decoder.hitRate * 100).toInt()
            return Cx.Fail(
                Cx.Kind.PARSE,
                "字体反爬解析失败（命中 $pct%），题目是乱码，已跳过而不是瞎答",
            )
        }

        onProgress("解析到 ${page.questions.size} 题，AI 作答中")
        val solution = when (val r = solver.solve(page)) {
            is Cx.Ok -> r.data
            is Cx.Fail -> return r
            Cx.NeedLogin -> return Cx.Fail(Cx.Kind.UNKNOWN, "答题流程不需要登录态")
        }

        val missing = solution.missing
        if (missing.isNotEmpty()) {
            return Cx.Fail(
                Cx.Kind.DENIED,
                "有 ${missing.size}/${page.questions.size} 题没拿到答案，为免提交后无法撤回，本次不提交",
            )
        }

        onProgress("答案已就绪，提交中")
        val done = client.submitQuiz(page, solution.answerMap)
        if (done is Cx.Ok) onProgress("测验已提交")
        return done
    }
}
