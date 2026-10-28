package com.xxt.study.core.font

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 解码结果：既给文本，也给命中率，让上层能判断「这次解出来到底能不能用」 */
data class FontDecode(
    val text: String,
    /** 参与统计的字符数（只算非 ASCII，ASCII 不加密） */
    val total: Int,
    val hit: Int,
) {
    val hitRate: Float get() = if (total == 0) 1f else hit.toFloat() / total

    /**
     * 命中率低于 0.8 就认为解密失败。
     *
     * 这道闸很重要：字体换过、算法对不上时，乱码照样能拼出一串"文本"，
     * 直接喂给 AI 会得到一本正经的垃圾答案——比报错更糟。
     */
    val usable: Boolean get() = hitRate >= 0.8f
}

/**
 * 学习通字体反爬解码。
 *
 * 原理：页面下发一份自定义 TTF，字形轮廓是正常汉字，但码位被打乱；
 * 同时 `#cxSecretStyle` 里内联了这份字体的 base64。
 * 把轮廓归一化成 MD5，去静态表里反查真实码位，就还原了。
 *
 * 静态表（font_map_table.json，30884 条）来自 SuperAutoStudy，格式是
 * `{"uni4E00": "<字形hash>", "A": "<hash>"}`。表是预生成的，所以只要字形本身
 * 没变，任意一次下发的字体都能查回来——变的是码位，不是轮廓。
 */
object CxFont {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 康熙部首 → 常规汉字。
     *
     * 字体里的字形是「⺠」这种部首码位，但题干原文是「民」。
     * 不做这一步替换，AI 看到的就是部首拼出来的怪话。
     */
    private val KX_KEYS =
        "⼀⼁⼂⼃⼄⼅⼆⼇⼈⼉⼊⼋⼌⼍⼎⼏⼐⼑⼒⼓⼔⼕⼖⼗⼘⼙⼚⼛⼜⼝⼞⼟⼠⼡⼢⼣⼤⼥⼦⼧⼨⼩⼪⼫⼬⼭⼮⼯⼰⼱⼲⼳⼴⼵⼶⼷⼸⼹⼺⼻⼼⼽⼾⼿⽀⽁⽂⽃⽄⽅⽆⽇⽈⽉⽊⽋⽌⽍⽎⽏⽐⽑⽒⽓⽔⽕⽖⽗⽘⽙⽚⽛⽜⽝⽞⽟⽠⽡⽢⽣⽤⽥⽦⽧⽨⽩⽪⽫⽬⽭⽮⽯⽰⽱⽲⽳⽴⽵⽶⽷⽸⽹⽺⽻⽼⽽⽾⽿⾀⾁⾂⾃⾄⾅⾆⾇⾈⾉⾊⾋⾌⾍⾎⾏⾐⾑⾒⾓⾔⾕⾖⾗⾘⾙⾚⾛⾜⾝⾞⾟⾠⾡⾢⾣⾤⾥⾦⾧⾨⾩⾪⾫⾬⾭⾮⾯⾰⾱⾲⾳⾴⾵⾶⾷⾸⾹⾺⾻⾼髙⾽⾾⾿⿀⿁⿂⿃⿄⿅⿆⿇⿈⿉⿊⿋⿌⿍⿎⿏⿐⿑⿒⿓⿔⿕⺠⻬⻩⻢⻜⻅⺟⻓"

    private val KX_VALUES =
        "一丨丶丿乙亅二亠人儿入八冂冖冫几凵刀力勹匕匚匸十卜卩厂厶又口囗土士夂夊夕大女子宀寸小尢尸屮山巛工己巾干幺广廴廾弋弓彐彡彳心戈戶手支攴文斗斤方无日曰月木欠止歹殳毋比毛氏气水火爪父爻爿片牙牛犬玄玉瓜瓦甘生用田疋疒癶白皮皿目矛矢石示禸禾穴立竹米糸缶网羊羽老而耒耳聿肉臣自至臼舌舛舟艮色艸虍虫血行衣襾見角言谷豆豕豸貝赤走足身車辛辰辵邑酉采里金長門阜隶隹雨青非面革韋韭音頁風飛食首香馬骨高高髟鬥鬯鬲鬼魚鳥鹵鹿麥麻黃黍黑黹黽鼎鼓鼠鼻齊齒龍龜龠民齐黄马飞见母长"

    private val RADICALS: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>(KX_KEYS.length)
        val n = minOf(KX_KEYS.length, KX_VALUES.length)
        for (i in 0 until n) m[KX_KEYS[i]] = KX_VALUES[i]
        m
    }

    /** 从页面里抠出内联字体的 base64（`#cxSecretStyle` 的 @font-face） */
    fun extractFontBase64(html: String): String? {
        val idx = html.indexOf("cxSecretStyle")
        if (idx < 0) return null
        // 只在样式块附近找，避免把别处的图片 base64 当成字体
        val window = html.substring(idx, minOf(html.length, idx + 400_000))
        val marker = "base64,"
        val at = window.indexOf(marker)
        if (at < 0) return null
        var end = at + marker.length
        while (end < window.length) {
            val c = window[end]
            // base64 字符集 + 常见换行；遇到引号/括号即结束
            if (!(c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r')) break
            end++
        }
        val body = window.substring(at + marker.length, end).replace("\n", "").replace("\r", "").trim()
        return body.ifBlank { null }
    }

    /**
     * 解析静态映射表，产出 `字形hash → 真实码位`。
     *
     * 只收 key 为 `uniXXXX` 或单字符名的条目：
     * 表里还有 "Aacute"、"AE" 这类连字名，解不回单个码位，直接丢掉——
     * 它们在题干里本来也用不到。
     */
    fun parseTable(tableJson: String): Map<String, Int> {
        val root = runCatching {
            json.parseToJsonElement(tableJson) as? JsonObject
        }.getOrNull() ?: return emptyMap()

        val out = HashMap<String, Int>(root.size * 2)
        for ((name, value) in root) {
            val hash = (value as? JsonPrimitive)?.content?.trim().orEmpty()
            if (hash.length != 32) continue
            val code = codePointOf(name) ?: continue
            out[hash] = code
        }
        return out
    }

    private fun codePointOf(glyphName: String): Int? {
        if (glyphName.startsWith("uni") && glyphName.length >= 7) {
            // uni4E00 / uni4E00.alt 都取前 4 位
            val hex = glyphName.substring(3, 7)
            return hex.toIntOrNull(16)
        }
        if (glyphName.length == 1) return glyphName[0].code
        return null
    }

    /** 解码一段文字 */
    fun decode(font: TrueTypeFont, table: Map<String, Int>, text: String): FontDecode {
        if (text.isEmpty()) return FontDecode("", 0, 0)
        val sb = StringBuilder(text.length)
        var total = 0
        var hit = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            val ch = if (chars == 1) text[i] else '\u0000'

            val hash = font.hashOfCodePoint(cp)
            val decoded = hash?.let { table[it] }

            if (ch != '\u0000' && ch.code >= 0x80) total++
            if (decoded != null) {
                if (ch.code >= 0x80) hit++
                sb.appendCodePoint(decoded)
            } else {
                // 解不出来就保留原字符：至少 ASCII 和标点不会丢
                sb.appendCodePoint(cp)
            }
            i += chars
        }
        return FontDecode(normalizeRadicals(sb.toString()), total, hit)
    }

    private fun normalizeRadicals(text: String): String {
        if (RADICALS.isEmpty()) return text
        var changed = false
        val out = CharArray(text.length)
        for (i in text.indices) {
            val mapped = RADICALS[text[i]]
            if (mapped != null) {
                out[i] = mapped
                changed = true
            } else {
                out[i] = text[i]
            }
        }
        return if (changed) String(out) else text
    }
}
