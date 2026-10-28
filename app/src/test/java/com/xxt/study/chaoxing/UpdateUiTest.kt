package com.xxt.study.chaoxing

import com.xxt.study.core.update.UpdateAvailable
import com.xxt.study.core.update.UpdateManifest
import com.xxt.study.core.update.UpdatePhase
import com.xxt.study.core.update.UpdateUiState
import com.xxt.study.feature.update.updateRowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新入口的可见反馈。
 *
 * "点了没反应"是这次要修的核心体验问题之一，而"我的"页那一行是用户唯一的常驻观察点——
 * 所以每一种状态都必须有明确文案，且下载中的进度要能在这里看到（哪怕弹窗被关掉了）。
 */
class UpdateUiTest {

    private fun available(v: String = "0.7.0") = UpdateAvailable(
        manifest = UpdateManifest(
            versionCode = 20,
            versionName = v,
            sizeBytes = 2_000_000,
            url = "https://example.com/a.apk",
        ),
        source = "ghproxy.net",
        currentVersionCode = 17,
        currentVersionName = "0.6.1",
    )

    private fun state(
        phase: UpdatePhase,
        progress: Int = 0,
        message: String? = null,
        dialogOpen: Boolean = false,
        avail: UpdateAvailable? = null,
    ) = UpdateUiState(
        phase = phase,
        available = avail,
        progress = progress,
        message = message,
        dialogOpen = dialogOpen,
    )

    // ---------- 每个阶段都要有话说 ----------

    @Test
    fun `空闲时显示当前版本并提示可检查`() {
        val r = updateRowText(state(UpdatePhase.IDLE), "0.6.1")
        assertEquals("检查更新", r.title)
        assertTrue("副标题要带版本号", r.subtitle.contains("0.6.1"))
        assertFalse(r.viewable)
    }

    @Test
    fun `检查中要明确说正在检查`() {
        val r = updateRowText(state(UpdatePhase.CHECKING), "0.6.1")
        assertTrue(r.title.contains("正在检查"))
        assertFalse("检查中不该被当成可重开的任务", r.viewable)
    }

    @Test
    fun `已是最新要给出结论而不是沉默`() {
        val r = updateRowText(state(UpdatePhase.UP_TO_DATE), "0.6.1")
        assertTrue(r.title.contains("最新"))
    }

    @Test
    fun `检查失败要带上原因并提供重试入口`() {
        val r = updateRowText(state(UpdatePhase.FAILED, message = "ghproxy.net 连不上"), "0.6.1")
        assertTrue(r.title.contains("失败"))
        assertTrue("失败原因要露出来，否则用户没法判断是网络还是软件坏了", r.subtitle.contains("连不上"))
        assertTrue("要提示可以重试", r.subtitle.contains("重试"))
    }

    @Test
    fun `检查失败但没原因时不能出现空白`() {
        val r = updateRowText(state(UpdatePhase.FAILED), "0.6.1")
        assertTrue(r.subtitle.isNotBlank())
    }

    @Test
    fun `发现新版本显示版本号且可点开`() {
        val r = updateRowText(state(UpdatePhase.PROMPT, avail = available("0.7.0")), "0.6.1")
        assertTrue(r.title.contains("0.7.0"))
        assertTrue(r.viewable)
    }

    @Test
    fun `下载中要能在这里看到进度`() {
        // 用户关掉弹窗之后，这一行就是唯一能看到下载进度的入口
        val r = updateRowText(
            state(UpdatePhase.DOWNLOADING, progress = 43, avail = available()),
            "0.6.1",
        )
        assertTrue(r.title.contains("下载"))
        assertTrue("必须显示百分比", r.subtitle.contains("43%"))
        assertTrue(r.viewable)
    }

    @Test
    fun `下载刚开始时进度为 0 也不能显示成 0 百分比`() {
        val r = updateRowText(state(UpdatePhase.DOWNLOADING, progress = 0, avail = available()), "0.6.1")
        assertFalse(r.subtitle.contains("0%"))
        assertTrue(r.subtitle.contains("准备"))
    }

    @Test
    fun `下载完成提示立即安装`() {
        val r = updateRowText(state(UpdatePhase.READY, progress = 100, avail = available()), "0.6.1")
        assertTrue(r.title.contains("下载完成"))
        assertTrue(r.subtitle.contains("安装"))
        assertTrue(r.viewable)
    }

    @Test
    fun `缺权限时告诉用户点哪里`() {
        val r = updateRowText(state(UpdatePhase.NEED_PERMISSION, avail = available()), "0.6.1")
        assertTrue("要点名是哪一项权限", r.title.contains("允许安装"))
        assertTrue(r.viewable)
    }

    @Test
    fun `更新出错要保留错误信息`() {
        val r = updateRowText(state(UpdatePhase.ERROR, message = "校验失败，可能缓存了旧包"), "0.6.1")
        assertTrue(r.title.contains("失败"))
        assertTrue(r.subtitle.contains("旧包"))
        assertTrue(r.viewable)
    }

    // ---------- 弹窗可见性 / 可重开 ----------

    @Test
    fun `弹窗只在 dialogOpen 且非空闲时显示`() {
        assertFalse(state(UpdatePhase.IDLE, dialogOpen = true).shouldShowDialog)
        assertFalse(state(UpdatePhase.PROMPT, dialogOpen = false).shouldShowDialog)
        assertTrue(state(UpdatePhase.PROMPT, dialogOpen = true).shouldShowDialog)
    }

    @Test
    fun `下载中关掉弹窗任务仍然可重开`() {
        // 关键语义：dialogOpen=false 但 phase 还是 DOWNLOADING，
        // 所以"我的"页那一行依然是"查看"而不是"重新检查"
        val s = state(UpdatePhase.DOWNLOADING, progress = 60, dialogOpen = false, avail = available())
        assertFalse("弹窗已关", s.shouldShowDialog)
        assertTrue("但任务还在，点一下应该能拉回来", s.hasOpenableState)
        assertTrue(s.hasBackgroundWork)
    }

    @Test
    fun `空闲状态没有可重开的东西`() {
        assertFalse(state(UpdatePhase.IDLE).hasOpenableState)
    }
}
