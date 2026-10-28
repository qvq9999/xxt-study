package com.xxt.study.chaoxing

import com.xxt.study.feature.home.fuzzyMatch
import com.xxt.study.feature.home.isSubsequence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程模糊搜索。
 *
 * 真实课程名混着编号、空格、中英混排（如「25-26-2 中国共产党党史」「大数据可视化技术与应用」），
 * 搜索必须在这类名字上也正常，否则用户会觉得"搜不到"。
 */
class CourseSearchTest {

    @Test
    fun `连续子串命中`() {
        assertTrue(fuzzyMatch("专业英语", "英语"))
        assertTrue(fuzzyMatch("大数据可视化技术与应用", "可视化"))
    }

    @Test
    fun `子序列命中_输入缩写也能搜到`() {
        // 「高数」不是「高等数学」的连续子串，但字符按顺序出现 → 应该命中
        assertTrue(fuzzyMatch("高等数学", "高数"))
        assertTrue(fuzzyMatch("大学生就业指导与创新创业教育", "就业创业"))
    }

    @Test
    fun `多关键词要求全部命中`() {
        assertTrue(fuzzyMatch("24级计算机应用专2班", "计算机 应用"))
        assertTrue(fuzzyMatch("24级计算机应用专2班", "计算机 英语").not())
    }

    @Test
    fun `大小写与空白一律忽略`() {
        assertTrue(fuzzyMatch("25-26-2 中国共产党党史", "党史"))
        assertTrue(fuzzyMatch("Programming Design", "programming"))
        assertTrue(fuzzyMatch(" 图形图像处理 ", "图像"))
    }

    @Test
    fun `不相关的不该命中`() {
        assertFalse(fuzzyMatch("高等数学", "英语"))
        assertFalse(fuzzyMatch("", "数学"))
    }

    @Test
    fun `空查询全部命中`() {
        assertTrue(fuzzyMatch("任何课程", ""))
        assertTrue(fuzzyMatch("任何课程", "   "))
    }

    @Test
    fun `子序列判断本身`() {
        assertTrue(isSubsequence("高数", "高等数学"))
        assertFalse(isSubsequence("数高", "高等数学"))
        assertTrue(isSubsequence("", "任意"))
    }
}
