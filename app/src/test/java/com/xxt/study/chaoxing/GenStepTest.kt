package com.xxt.study.chaoxing

import com.xxt.study.feature.work.GenStep
import com.xxt.study.feature.work.GenStepState
import com.xxt.study.feature.work.stepDone
import com.xxt.study.feature.work.stepFail
import com.xxt.study.feature.work.stepFailNow
import com.xxt.study.feature.work.stepNow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作答过程（阶段级进度）的推进逻辑。
 *
 * 弹窗里那五个点看着简单，但索引错一位就会表现成"读取作答页一直是进行中、
 * 而后面的步骤已经打勾了"——用户会以为卡死。这里把"只动指定那一步"钉住。
 */
class GenStepTest {

    private fun steps() = listOf(
        GenStep("读取作答页", GenStepState.WAIT),
        GenStep("解析题目", GenStepState.WAIT),
        GenStep("AI 生成答案", GenStepState.WAIT),
        GenStep("回填到页面", GenStepState.WAIT),
    )

    @Test
    fun `stepDone 只改指定那一步`() {
        val out = steps().stepDone(0)
        assertEquals(GenStepState.DONE, out[0].state)
        // 其余三步必须原样
        assertEquals(GenStepState.WAIT, out[1].state)
        assertEquals(GenStepState.WAIT, out[2].state)
        assertEquals(GenStepState.WAIT, out[3].state)
    }

    @Test
    fun `stepNow 带上说明`() {
        val out = steps().stepDone(1).stepNow(2, "共 12 题")
        assertEquals(GenStepState.NOW, out[2].state)
        assertEquals("共 12 题", out[2].detail)
        assertEquals(GenStepState.DONE, out[1].state)
    }

    @Test
    fun `stepDone 传空说明时保留原有说明`() {
        val out = steps().stepNow(2, "共 12 题").stepDone(2, "")
        assertEquals(GenStepState.DONE, out[2].state)
        assertEquals("原有说明不能被空串冲掉", "共 12 题", out[2].detail)
    }

    @Test
    fun `stepFail 只把失败那一步标红`() {
        val out = steps().stepDone(0).stepNow(1).stepFail(1, "没解析到题目")
        assertEquals(GenStepState.DONE, out[0].state)
        assertEquals(GenStepState.FAIL, out[1].state)
        assertEquals("没解析到题目", out[1].detail)
        assertEquals(GenStepState.WAIT, out[2].state)
    }

    @Test
    fun `stepFailNow 只处理进行中的那一步`() {
        val out = steps().stepDone(0).stepNow(1).stepFailNow()
        assertEquals(GenStepState.DONE, out[0].state)
        assertEquals(GenStepState.FAIL, out[1].state)
        assertEquals("没在跑的步骤不该被标成失败", GenStepState.WAIT, out[2].state)
    }

    @Test
    fun `越界索引不抛异常也不改任何东西`() {
        // 防御性：将来阶段数变了，旧调用点传了越界下标不该把作答流程搞崩
        val out = steps().stepDone(9).stepNow(9)
        assertEquals(steps(), out)
    }

    @Test
    fun `推进过程不修改原列表`() {
        val origin = steps()
        origin.stepDone(0).stepNow(1)
        assertEquals("原列表必须保持不可变", GenStepState.WAIT, origin[0].state)
    }
}
