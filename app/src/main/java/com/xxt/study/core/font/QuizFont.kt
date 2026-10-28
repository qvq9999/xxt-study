package com.xxt.study.core.font

import android.content.res.AssetManager

/**
 * 一次测验页的字体解码器。
 *
 * 命中率是**跨多次调用累计**的：题干和选项要分别解，单看一句判断不了对错，
 * 整卷解完才知道这份字体能不能用。所以做成有状态对象，由调用方在最后读 [hitRate]。
 */
class QuizTextDecoder internal constructor(
    private val font: TrueTypeFont,
    private val table: Map<String, Int>,
) {
    private var total = 0
    private var hit = 0

    val hitRate: Float get() = if (total == 0) 1f else hit.toFloat() / total

    /** 低于这个命中率就别把乱码喂给 AI 了，直接报「字体反爬解析失败」 */
    val usable: Boolean get() = hitRate >= 0.8f

    fun decode(text: String): String {
        if (text.isEmpty()) return text
        val r = CxFont.decode(font, table, text)
        total += r.total
        hit += r.hit
        return r.text
    }
}

/**
 * 字体映射表的加载入口。
 *
 * 1.5 MB 的 JSON、3 万条记录，解析一次约 200~400ms，所以：
 * - 懒加载，只在真碰到带反爬的测验页时才付这个代价
 * - 加载失败不抛异常，降级成「解不出字」——宁可放弃这次测验，
 *   也不能让整个刷课流程崩在字体上
 */
class QuizFont(private val assets: AssetManager) {

    @Volatile
    private var table: Map<String, Int>? = null

    private fun table(): Map<String, Int> {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val loaded = runCatching {
                assets.open(TABLE_ASSET).bufferedReader(Charsets.UTF_8).use {
                    CxFont.parseTable(it.readText())
                }
            }.getOrDefault(emptyMap())
            table = loaded
            return loaded
        }
    }

    /** 页面里没有内联字体时返回 null（作业页就是这样，本来就没有反爬） */
    fun decoderFor(html: String): QuizTextDecoder? {
        val b64 = CxFont.extractFontBase64(html) ?: return null
        val bytes = runCatching {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        val font = TrueTypeFont.parse(bytes) ?: return null
        val map = table()
        if (map.isEmpty()) return null
        return QuizTextDecoder(font, map)
    }

    private companion object {
        const val TABLE_ASSET = "font_map_table.json"
    }
}
