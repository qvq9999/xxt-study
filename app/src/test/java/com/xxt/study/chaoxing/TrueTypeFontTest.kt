package com.xxt.study.chaoxing

import com.xxt.study.core.font.TrueTypeFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TrueType 解析的真实校验。
 *
 * 期望值**不是**从 Kotlin 实现里跑出来的，而是用 fontTools（Python）对同一份
 * 系统字体独立算出来的。两边算法一致，才能证明：
 * cmap 找字形、loca/glyf 取轮廓、flags 与增量坐标解码、hash 拼接，四步都没错。
 *
 * 这四步里任何一步错，表现都是「字体反爬解不出来」，但现场没有任何报错可看——
 * 所以只能在这里钉死。
 *
 * 字体是 Windows 自带的 simhei.ttf（黑体）。机器上没有就跳过，不算失败。
 */
class TrueTypeFontTest {

    private val candidates = listOf(
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/simkai.ttf",
        "C:/Windows/Fonts/simsun.ttc",
    )

    private fun load(): TrueTypeFont? {
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.length() > 100_000) {
                // ttc 是集合格式，本解析器不支持，直接跳过
                if (path.endsWith(".ttc")) continue
                return TrueTypeFont.parse(f.readBytes())
            }
        }
        return null
    }

    @Test
    fun `汉字字形 hash 与 fontTools 独立实现一致`() {
        val font = load()
        assumeTrue("本机找不到可用的 ttf 字体，跳过", font != null)

        // fontTools 对 simhei.ttf 的计算结果（脚本见 commit message）
        val expected = mapOf(
            '中' to "6f4a57d0143d1fbeb57325dc04a9aa6a",
            '国' to "171cc4340e02c079ac5649db37c34d32",
            '学' to "e16831b605ebdf67a8081ea16a1f11b2",
            '习' to "44d792a49b5872f4c351731c75571c35",
            '通' to "4a98e522a10fda85c4468e79d9c45506",
            '测' to "bf6e3f093679e3bffbc1964c7b03cd77",
        )
        for ((ch, hash) in expected) {
            assertEquals("字符 $ch 的轮廓 hash 不对", hash, font!!.hashOfCodePoint(ch.code))
        }
    }

    @Test
    fun `ASCII 也走同一套 cmap 解析`() {
        val font = load() ?: run {
            assumeTrue(false)
            return
        }
        assertEquals("c0ca3a4dfa44f2c708df3111609c8a97", font.hashOfCodePoint('A'.code))
    }

    @Test
    fun `没有字形的码位返回 null 而不是空 hash`() {
        val font = load() ?: run {
            assumeTrue(false)
            return
        }
        // 私用区一般没有映射
        assertNull(font.hashOfCodePoint(0xE000))
        // 空 hash（md5 空串）绝不能当有效结果返回
        assertNotNull(font.hashOfCodePoint('一'.code))
    }

    @Test
    fun `同一字符重复计算结果稳定`() {
        val font = load() ?: run {
            assumeTrue(false)
            return
        }
        val a = font.hashOfCodePoint('教'.code)
        val b = font.hashOfCodePoint('教'.code)
        assertEquals(a, b)
    }

    @Test
    fun `损坏的字体数据返回 null 不抛异常`() {
        assertNull(TrueTypeFont.parse(ByteArray(64) { it.toByte() }))
        assertNull(TrueTypeFont.parse(ByteArray(0)))
    }
}
