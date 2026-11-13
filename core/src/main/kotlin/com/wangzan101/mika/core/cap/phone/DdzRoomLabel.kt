package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame

/**
 * 读真人经典牌桌底部的场次名「经典××场」。
 *
 * 游戏把账号安排进哪个场次,会在匹配画面和牌桌底部把场次写出来。拿它和模型要的场次比,就能发现
 * 游戏把高豆账号放进了别的场(2026-11-13: room=master 却进了「经典至尊场」)。
 *
 * 这个标签是正中的五个字,只有中间两个字随场次不同。每个已知场次存中间两个字的 20×20 二值字形;
 * 认不出的场(大师场、精英场,或者被弹窗挡住)对不上任何样本,返回 null——调用方只能当成「看不出」,
 * 绝不能当成场次不符。
 */
internal object DdzRoomLabel {
    /** 场次标签所在的行;读牌桌帧时要把它们一起读进来。 */
    val ROWS: List<Int> = (Y0 until Y1).toList()

    private const val X0 = 1115
    private const val X1 = 1310
    private const val Y0 = 1028
    private const val Y1 = 1066
    private const val GW = 20
    private const val GH = 20
    /** 两个字的 800 个格子里最多差这么多格才算同一个场次。 */
    private const val SURE = 120
    /** 和第二像的场次至少拉开这么多格,否则不认。 */
    private const val MARGIN = 60
    private const val MIN_GLYPH_W = 20
    private const val MAX_GLYPH_W = 40
    private const val MIN_HEIGHT = 20
    private const val MAX_HEIGHT = 36

    private class Known(val label: String, val bits: BooleanArray)

    private val known: List<Known> by lazy {
        javaClass.getResourceAsStream("/ddz/rooms.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (label, hex) = line.trim().split(Regex("\\s+"), limit = 2)
                val bytes = hex.chunked(2).map { it.toInt(16) }
                Known(label, BooleanArray(GW * GH) { i -> bytes[i / 8] shr (7 - i % 8) and 1 == 1 })
            }
    }

    /** 底部五个字里的场次名(如「至尊场」「土豪场」「普通场」);认不出为 null。 */
    fun read(frame: RawFrame): String? {
        if (frame.width != 2412 || frame.height != 1080) return null
        val w = X1 - X0
        val h = Y1 - Y0
        val luma = Array(h) { IntArray(w) }
        try {
            for (r in 0 until h) for (c in 0 until w) {
                val p = frame.pixel(X0 + c, Y0 + r)
                luma[r][c] = ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
            }
        } catch (_: RuntimeException) {
            // 这一帧没读这几行(例如只读了牌桌的行):看不出场次,不是不符。
            return null
        }
        // 标签是浅色字压在深色底上:用中位数和 98 分位定阈值,和画面主题无关。
        val sorted = IntArray(h * w)
        var at = 0
        for (r in 0 until h) for (c in 0 until w) sorted[at++] = luma[r][c]
        sorted.sort()
        val threshold = (sorted[sorted.size / 2] + sorted[minOf(sorted.size - 1, sorted.size * 98 / 100)]) / 2
        val ink = Array(h) { r -> BooleanArray(w) { c -> luma[r][c] > threshold } }

        val groups = glyphColumns(ink, w, h)
        if (groups.size != 5) return null
        val top = (0 until h).firstOrNull { r -> (0 until w).any { c -> ink[r][c] } } ?: return null
        val bottom = (h - 1 downTo 0).first { r -> (0 until w).any { c -> ink[r][c] } }
        if (bottom - top + 1 !in MIN_HEIGHT..MAX_HEIGHT) return null
        if (groups.any { it.last - it.first + 1 !in MIN_GLYPH_W..MAX_GLYPH_W }) return null

        val signature = BooleanArray(GW * GH * 2)
        var offset = 0
        for (index in 2..3) {
            val group = groups[index]
            boxSig(ink, top, bottom, group.first, group.last).copyInto(signature, offset)
            offset += GW * GH
        }
        val ranked = known.map { it.label to distance(it.bits, signature) }.sortedBy { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: Int.MAX_VALUE
        return best.first.takeIf { best.second <= SURE && second - best.second >= MARGIN }
    }

    /** 有墨迹的列按间隙切成几段(隔一个空列不切,隔两个以上才切)。 */
    private fun glyphColumns(ink: Array<BooleanArray>, w: Int, h: Int): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var start = -1
        var last = -1
        for (c in 0 until w) {
            val any = (0 until h).any { r -> ink[r][c] }
            if (any) {
                if (start < 0) start = c
                last = c
            } else if (start >= 0 && c - last >= 2) {
                groups += start..last
                start = -1
            }
        }
        if (start >= 0) groups += start..last
        return groups
    }

    /** 把 [top]..[bottom] 行、[left]..[right] 列的字形按面积缩到 GW×GH;一格四成以上有墨就算有。 */
    private fun boxSig(ink: Array<BooleanArray>, top: Int, bottom: Int, left: Int, right: Int): BooleanArray {
        val sh = bottom - top + 1
        val sw = right - left + 1
        return BooleanArray(GW * GH) { index ->
            val i = index / GW
            val j = index % GW
            val r0 = i * sh / GH
            val r1 = maxOf(r0 + 1, (i + 1) * sh / GH)
            val c0 = j * sw / GW
            val c1 = maxOf(c0 + 1, (j + 1) * sw / GW)
            var on = 0
            for (r in r0 until r1) for (c in c0 until c1) if (ink[top + r][left + c]) on++
            on >= 0.4 * (r1 - r0) * (c1 - c0)
        }
    }

    private fun distance(a: BooleanArray, b: BooleanArray): Int {
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        return diff
    }
}
