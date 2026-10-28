package com.xxt.study.chaoxing

import com.xxt.study.core.ai.LengthRule
import com.xxt.study.core.ai.LengthRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字数要求解析。
 *
 * 用户实测：题干写「150-250 字」，AI 只给 90 字。提示词约束不住，
 * 只能把要求解析出来、答完再验一遍。这里把各种写法钉死。
 */
class LengthRuleTest {

    @Test
    fun `区间写法`() {
        assertEquals(LengthRule(150, 250), LengthRules.parse("结合材料谈谈你的看法（150-250字）"))
        assertEquals(LengthRule(200, 300), LengthRules.parse("不少于200字，不超过300字"))
    }

    @Test
    fun `中文连接符与波浪号`() {
        assertEquals(LengthRule(100, 200), LengthRules.parse("字数 100~200 字"))
        assertEquals(LengthRule(100, 200), LengthRules.parse("100—200字"))
        assertEquals(LengthRule(100, 200), LengthRules.parse("100 至 200 字"))
    }

    @Test
    fun `下限写法`() {
        assertEquals(LengthRule(300, null), LengthRules.parse("不少于300字"))
        assertEquals(LengthRule(300, null), LengthRules.parse("不低于 300 字"))
        assertEquals(LengthRule(500, null), LengthRules.parse("500字以上"))
    }

    @Test
    fun `上限写法`() {
        assertEquals(LengthRule(null, 500), LengthRules.parse("不超过500字"))
        assertEquals(LengthRule(null, 500), LengthRules.parse("最多 500 字"))
    }

    @Test
    fun `左右按正负两成放宽`() {
        assertEquals(LengthRule(80, 120), LengthRules.parse("100字左右"))
    }

    @Test
    fun `没有要求返回 null`() {
        assertNull(LengthRules.parse("简述一下什么是递归"))
        assertNull(LengthRules.parse(""))
        // 只有数字没有"字"这个单位的，不算字数要求
        assertNull(LengthRules.parse("第 200 页的内容"))
        assertNull(LengthRules.parse("请说明 3 个要点"))
    }

    @Test
    fun `字数按去空白后的字符计`() {
        assertEquals(6, LengthRules.count("今天 天气 不错"))
        assertEquals(10, LengthRules.count("  Hello  World  "))
        assertEquals(0, LengthRules.count("   "))
    }

    @Test
    fun `合规判定`() {
        val rule = LengthRule(150, 250)
        assertFalse("90 字不够", rule.fits("字".repeat(90)))
        assertTrue("150 字刚好", rule.fits("字".repeat(150)))
        assertTrue("250 字刚好", rule.fits("字".repeat(250)))
        assertFalse("300 字超了", rule.fits("字".repeat(300)))
    }

    @Test
    fun `只有下限时上限不限制`() {
        val rule = LengthRule(150, null)
        assertTrue(rule.fits("字".repeat(1000)))
        assertFalse(rule.fits("字".repeat(100)))
    }
}
