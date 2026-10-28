package com.xxt.study.core.font

import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 最小 TrueType 解析器，只做一件事：把「码位 → 字形轮廓 hash」算出来。
 *
 * 为什么不用现成库：学习通的字体反爬是把常用字的轮廓保持不变、只打乱码位，
 * 所以只要能把某个码位的字形轮廓归一化成 hash，就能通过静态映射表反查出原字。
 * 全量 TrueType 库（FontBox 那套）对这里来说是几十倍的体积，不值。
 *
 * 只实现 hash 真正需要的三张表：cmap（码位→字形）、loca/glyf（字形轮廓）。
 *
 * 关于复合字形（composite）：汉字在 TrueType 里基本都是简单轮廓，
 * 复合字形集中在带声调的拉丁字母（À、é）。这里仍然做递归展开 + 变换，
 * 但不实现「点对齐」（point matching）——真碰上就用原样偏移兜底，
 * 最坏结果是这个字符解不出来，而不是解错。
 */
class TrueTypeFont private constructor(private val d: ByteArray) {

    private class Entry(val offset: Int, val length: Int)

    private val tables = LinkedHashMap<String, Entry>()
    private val numGlyphs: Int
    private val loca: IntArray
    private val glyfOffset: Int

    /** 码位 → 字形 id */
    val cmap: Map<Int, Int>

    init {
        check(d.size > 12) { "字体数据太短" }
        val count = u16(4)
        for (i in 0 until count) {
            val rec = 12 + i * 16
            if (rec + 16 > d.size) break
            val tag = String(d, rec, 4, Charsets.US_ASCII)
            tables[tag] = Entry(u32(rec + 8).coerceIn(0, d.size), u32(rec + 12).coerceAtLeast(0))
        }
        val head = tables["head"] ?: error("缺 head 表")
        val maxp = tables["maxp"] ?: error("缺 maxp 表")
        val locaTable = tables["loca"] ?: error("缺 loca 表")
        val glyf = tables["glyf"] ?: error("缺 glyf 表")

        numGlyphs = u16(maxp.offset + 4).coerceAtLeast(0)
        val longFormat = i16(head.offset + 50) == 1
        glyfOffset = glyf.offset
        loca = IntArray(numGlyphs + 1) {
            val at = locaTable.offset + if (longFormat) it * 4 else it * 2
            val width = if (longFormat) 4 else 2
            if (at < 0 || at + width > d.size) 0
            else if (longFormat) u32(at) else u16(at) * 2
        }
        cmap = runCatching { parseCmap() }.getOrDefault(emptyMap())
    }

    // ---------------- 对外 ----------------

    /** 码位 → 轮廓 hash；该码位没有字形时返回 null */
    fun hashOfCodePoint(codePoint: Int): String? {
        val gid = cmap[codePoint] ?: return null
        if (gid <= 0 || gid >= numGlyphs) return null
        val points = runCatching { pointsOf(gid, 0) }.getOrNull() ?: return null
        if (points.isEmpty()) return null
        val sb = StringBuilder(points.size * 8)
        for (p in points) {
            // 与原项目 hashGlyph 完全一致的拼法：x、y、(flag & 1) 十进制直接相接
            sb.append(p[0]).append(p[1]).append(p[2] and 1)
        }
        return md5Hex(sb.toString())
    }

    // ---------------- cmap ----------------

    private fun parseCmap(): Map<Int, Int> {
        val entry = tables["cmap"] ?: return emptyMap()
        val base = entry.offset
        val n = u16(base + 2)
        var chosen = -1
        var chosenRank = -1
        for (i in 0 until n) {
            val rec = base + 4 + i * 8
            if (rec + 8 > d.size) break
            val platform = u16(rec)
            val encoding = u16(rec + 2)
            val sub = base + u32(rec + 4)
            val rank = when {
                platform == 3 && encoding == 10 -> 3 // Windows UCS-4
                platform == 3 && encoding == 1 -> 2  // Windows BMP
                platform == 0 -> 1                   // Unicode
                else -> 0
            }
            if (rank > chosenRank) {
                chosenRank = rank
                chosen = sub
            }
        }
        if (chosen < 0) return emptyMap()
        return when (u16(chosen)) {
            4 -> parseCmap4(chosen)
            12 -> parseCmap12(chosen)
            else -> emptyMap()
        }
    }

    private fun parseCmap4(base: Int): Map<Int, Int> {
        val segCount = u16(base + 6) / 2
        if (segCount <= 0) return emptyMap()
        val endBase = base + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val rangeBase = deltaBase + segCount * 2
        if (rangeBase + segCount * 2 > d.size) return emptyMap()

        val out = HashMap<Int, Int>(4096)
        for (i in 0 until segCount) {
            val start = u16(startBase + i * 2)
            val end = u16(endBase + i * 2)
            if (start == 0xFFFF) continue
            if (end < start) continue
            val delta = i16(deltaBase + i * 2)
            val rangeOffset = u16(rangeBase + i * 2)
            var c = start
            while (c <= end) {
                val gid = if (rangeOffset == 0) {
                    (c + delta) and 0xFFFF
                } else {
                    val at = rangeBase + i * 2 + rangeOffset + (c - start) * 2
                    if (at + 2 > d.size) 0 else {
                        val g = u16(at)
                        if (g == 0) 0 else (g + delta) and 0xFFFF
                    }
                }
                if (gid != 0) out[c] = gid
                c++
            }
        }
        return out
    }

    private fun parseCmap12(base: Int): Map<Int, Int> {
        val groups = u32(base + 12)
        if (groups <= 0 || groups > 200_000) return emptyMap()
        val out = HashMap<Int, Int>(4096)
        for (i in 0 until groups) {
            val at = base + 16 + i * 12
            if (at + 12 > d.size) break
            val start = u32(at)
            val end = u32(at + 4)
            val startGid = u32(at + 8)
            if (end < start || end - start > 200_000) continue
            var c = start
            while (c <= end) {
                out[c] = startGid + (c - start)
                c++
            }
        }
        return out
    }

    // ---------------- glyf ----------------

    private fun pointsOf(gid: Int, depth: Int): List<IntArray>? {
        if (depth > 6 || gid < 0 || gid >= numGlyphs) return null
        val start = loca[gid]
        val end = loca[gid + 1]
        if (end <= start) return emptyList()
        val off = glyfOffset + start
        if (off + 10 > d.size) return null
        val contours = i16(off)
        return if (contours >= 0) simplePoints(off, contours) else compositePoints(off, depth)
    }

    private fun simplePoints(off: Int, contours: Int): List<IntArray>? {
        var p = off + 10
        if (contours == 0) return emptyList()
        if (p + contours * 2 + 2 > d.size) return null
        val endPts = IntArray(contours) { u16(p + it * 2) }
        p += contours * 2
        val instrLen = u16(p)
        p += 2 + instrLen
        val n = endPts[contours - 1] + 1
        if (n <= 0 || n > 100_000) return null

        val flags = IntArray(n)
        var i = 0
        while (i < n && p < d.size) {
            val f = d[p++].toInt() and 0xFF
            flags[i++] = f
            if (f and 0x08 != 0) {
                var repeat = d[p++].toInt() and 0xFF
                while (repeat-- > 0 && i < n) flags[i++] = f
            }
        }

        val xs = IntArray(n)
        var x = 0
        for (j in 0 until n) {
            val f = flags[j]
            if (f and 0x02 != 0) {
                if (p >= d.size) return null
                val dx = d[p++].toInt() and 0xFF
                x += if (f and 0x10 != 0) dx else -dx
            } else if (f and 0x10 == 0) {
                if (p + 2 > d.size) return null
                x += i16(p)
                p += 2
            }
            xs[j] = x
        }

        val ys = IntArray(n)
        var y = 0
        for (j in 0 until n) {
            val f = flags[j]
            if (f and 0x04 != 0) {
                if (p >= d.size) return null
                val dy = d[p++].toInt() and 0xFF
                y += if (f and 0x20 != 0) dy else -dy
            } else if (f and 0x20 == 0) {
                if (p + 2 > d.size) return null
                y += i16(p)
                p += 2
            }
            ys[j] = y
        }

        return List(n) { intArrayOf(xs[it], ys[it], flags[it]) }
    }

    private fun compositePoints(off: Int, depth: Int): List<IntArray>? {
        val out = ArrayList<IntArray>()
        var p = off + 10
        var more = true
        var guard = 0
        while (more && guard++ < 32) {
            if (p + 4 > d.size) return null
            val flags = u16(p)
            val gid = u16(p + 2)
            p += 4

            val words = flags and 0x0001 != 0
            val xyValues = flags and 0x0002 != 0
            var arg1 = 0
            var arg2 = 0
            if (words) {
                if (p + 4 > d.size) return null
                // 参数按有符号解读：偏移量可以为负
                arg1 = i16(p)
                arg2 = i16(p + 2)
                p += 4
            } else {
                if (p + 2 > d.size) return null
                arg1 = d[p].toInt()
                arg2 = d[p + 1].toInt()
                p += 2
            }

            var a = 1.0
            var b = 0.0
            var c = 0.0
            var dd = 1.0
            when {
                flags and 0x0008 != 0 -> {
                    val s = f2dot14(p); p += 2
                    a = s; dd = s
                }
                flags and 0x0040 != 0 -> {
                    a = f2dot14(p); dd = f2dot14(p + 2); p += 4
                }
                flags and 0x0080 != 0 -> {
                    a = f2dot14(p); b = f2dot14(p + 2)
                    c = f2dot14(p + 4); dd = f2dot14(p + 6)
                    p += 8
                }
            }

            val sub = pointsOf(gid, depth + 1) ?: return null
            if (xyValues) {
                for (pt in sub) {
                    out += intArrayOf(
                        (a * pt[0] + c * pt[1] + arg1).roundToInt(),
                        (b * pt[0] + dd * pt[1] + arg2).roundToInt(),
                        pt[2],
                    )
                }
            } else {
                // 点对齐（point matching）不实现：直接原样并入，宁可解不出也不解错
                out.addAll(sub)
            }
            more = flags and 0x0020 != 0
        }
        return out
    }

    // ---------------- 字节读 ----------------

    private fun u8(at: Int): Int = if (at in d.indices) d[at].toInt() and 0xFF else 0

    private fun u16(at: Int): Int =
        if (at < 0 || at + 2 > d.size) 0 else ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private fun i16(at: Int): Int = u16(at).let { if (it and 0x8000 != 0) it - 0x10000 else it }

    private fun u32(at: Int): Int =
        if (at < 0 || at + 4 > d.size) 0 else {
            ((d[at].toInt() and 0xFF) shl 24) or
                ((d[at + 1].toInt() and 0xFF) shl 16) or
                ((d[at + 2].toInt() and 0xFF) shl 8) or
                (d[at + 3].toInt() and 0xFF)
        }

    /** F2Dot14：16 位定点数，低 14 位是小数 */
    private fun f2dot14(at: Int): Double = i16(at) / 16384.0

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append(String.format("%02x", byte))
        return sb.toString()
    }

    companion object {
        /** 解析失败返回 null：字体坏了不该让整个答题流程崩掉 */
        fun parse(data: ByteArray): TrueTypeFont? = runCatching { TrueTypeFont(data) }.getOrNull()
    }
}
