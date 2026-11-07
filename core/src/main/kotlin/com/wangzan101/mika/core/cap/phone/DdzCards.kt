package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import kotlin.math.roundToInt

/** 牌桌上除了自己以外的两家。 */
internal enum class DdzSide { Left, Right }

/** 一家出牌区此刻的样子。 */
internal sealed interface DdzSeen {
    data class Cards(val ranks: List<String>) : DdzSeen
    data object Pass : DdzSeen
    data object Empty : DdzSeen
    /** 有牌,但有一张认不准:这一手不能拿去算。 */
    data object Unsure : DdzSeen
}

/**
 * 认牌:手牌、两家出的牌、底牌,还有谁桌前摆着闹钟(轮到谁)。点数按真实截图里核对过的字形找最像的那个;
 * 不够像、或者和第二像的分不开,就当认不准,宁可交出去也不猜。坐标按主屏量的,Mika 的屏铺满了黑边,横向按内容宽度换算。
 */
internal object DdzCards {
    private class Exemplar(val rank: String, val bits: BooleanArray)

    private const val HAND_W = 32
    private const val HAND_H = 50
    private const val TABLE_W = 24
    private const val TABLE_H = 36
    /** 桌面上的 10 和手牌样本有点走样,所以主要看和第二像的拉不拉得开;带绶带认错的那几张都拉不开。 */
    private const val SURE = 0.28
    private const val MARGIN = 0.08
    /** 底牌更小,笔画少。 */
    private const val BOTTOM_SURE = 0.25
    private const val BOTTOM_MARGIN = 0.05
    private const val CONTENT_WIDTH = 2292.0

    private val handGlyphs by lazy { load("/ddz/hand.txt", HAND_W * HAND_H) }
    private val tableGlyphs by lazy { load("/ddz/table.txt", TABLE_W * TABLE_H) }

    /** 认牌要读的行;只在要认的那一刻读,平时轮询只读 [DdzScreen.ROWS]。 */
    val ROWS: List<Int> = ((5 until 105) + (220 until 420) + (600 until 800)).toList()

    /** 手牌从左到右;有一张认不准、或者不是从大到小排的(还在发牌),返回 null。 */
    fun hand(frame: RawFrame, view: DdzView): List<String>? {
        val ranks = (0 until view.handCount).map { i ->
            val (ink, red) = handGlyph(frame, view.handLeft + i * view.pitch + 8) ?: return null
            nearest(handGlyphs, boxSig(ink, HAND_W, HAND_H), sure = 0.2, margin = 0.0)?.let { joker(it, red) } ?: return null
        }
        return ranks.takeIf { it.zipWithNext().all { (a, b) -> ORDER.getValue(a) >= ORDER.getValue(b) } }
    }

    /** 一家出牌区:亮着的牌、「不出」,还是空的。地主最后一张右上角压着绶带,认不准时按牌型补。 */
    fun play(frame: RawFrame, side: DdzSide): DdzSeen {
        val (bar, k) = content(frame)
        val (x0, x1) = if (side == DdzSide.Left) bar + (280 * k).toInt() to bar + (880 * k).toInt()
        else bar + (1360 * k).toInt() to bar + (1990 * k).toInt()
        val cards = strip(frame, x0, x1, 220, 420, pitch = 53 * k, width = 103 * k, glyphRows = 60, glyphWidth = (44 * k).toInt(), k = k)
        if (cards == null) return if (passed(frame, side, bar, k)) DdzSeen.Pass else DdzSeen.Empty
        val ranks = cards.toMutableList()
        val last = ranks.lastIndex
        if (ranks[last] == null && last > 0) ranks[last] = byShape(ranks.subList(0, last).filterNotNull().takeIf { it.size == last })
        return if (ranks.any { it == null }) DdzSeen.Unsure else DdzSeen.Cards(ranks.map { it!! })
    }

    /** 三张底牌;没摆出来或认不准为 null。 */
    fun bottom(frame: RawFrame): List<String>? {
        val (bar, k) = content(frame)
        val cards = strip(frame, bar + (1050 * k).toInt(), bar + (1380 * k).toInt(), 5, 105, pitch = 44 * k, width = 57 * k,
            glyphRows = 46, glyphWidth = (32 * k).toInt(), k = k, minRows = 60, sure = BOTTOM_SURE, margin = BOTTOM_MARGIN) ?: return null
        return cards.takeIf { it.size == 3 && it.all { r -> r != null } }?.map { it!! }
    }

    /** 闹钟在谁桌前;在自己这里或者没有为 null。 */
    fun clock(frame: RawFrame): DdzSide? {
        val (bar, k) = content(frame)
        fun gold(x: Int) = count(frame, x, x + (90 * k).toInt(), 220, 320) { r, g, b -> r > 200 && g in 151..214 && b < 90 }
        return when {
            gold(bar + (310 * k).toInt()) >= 300 -> DdzSide.Left
            gold(bar + (1900 * k).toInt()) >= 300 -> DdzSide.Right
            else -> null
        }
    }

    private fun passed(frame: RawFrame, side: DdzSide, bar: Int, k: Double): Boolean {
        val x = bar + ((if (side == DdzSide.Left) 320 else 1830) * k).toInt()
        return count(frame, x, x + (160 * k).toInt(), 320, 410) { r, g, b -> minOf(r, g, b) > 235 && maxOf(r, g, b) - minOf(r, g, b) < 25 } >= 300
    }

    /**
     * 一排桌面上的牌:按白色牌面的总宽算张数,只在每张牌左上角找点数(避开右上角的绶带)。没有牌面为 null;
     * 列表里认不准的那张为 null。
     */
    private fun strip(
        frame: RawFrame, x0: Int, x1: Int, y0: Int, y1: Int, pitch: Double, width: Double, glyphRows: Int, glyphWidth: Int,
        k: Double, minRows: Int = 100, sure: Double = SURE, margin: Double = MARGIN,
    ): List<String?>? {
        val right = minOf(x1, frame.width)
        fun white(x: Int, y: Int) = frame.pixel(x, y).let { p -> min(p) > 190 && spread(p) < 45 }
        val rich = (y0 until y1).map { y -> (x0 until right).count { white(it, y) } >= (30 * k).toInt() }
        var run = 0
        var top = -1
        for ((i, v) in rich.withIndex()) {
            run = if (v) run + 1 else 0
            if (run >= minRows) { top = y0 + i - minRows + 1; break }
        }
        if (top < 0) return null
        val probe = top + minRows
        val span = DdzScreen.runs(right - x0, gap = 12, minWidth = 40) { white(x0 + it, probe) }.maxByOrNull { it.last - it.first } ?: return null
        val n = ((span.last - span.first + 1 - width) / pitch).roundToInt() + 1
        if (n !in 1..20) return null
        return (0 until n).map { i ->
            val cx = x0 + span.first + (i * pitch).toInt() + 3
            // 只有最后一张右上角可能压着绶带;前面的牌右边是下一张,点数(10 有两位)可以看全
            val wide = (if (i == n - 1) minOf(glyphWidth.toDouble(), pitch) else pitch).toInt() - 3
            val ink = Array(glyphRows) { r -> BooleanArray(wide) { c -> textInk(frame.pixel(cx + c, top + 4 + r)) } }
            val box = rankBox(ink) ?: return@map null
            val red = share(frame, cx, top + 4, ink) { r, g, _ -> r > 140 && g < 100 } > 0.5
            nearest(tableGlyphs, boxSig(box, TABLE_W, TABLE_H), sure, margin)?.let { joker(it, red) }
        }
    }

    /**
     * 绶带挡住的最后一张,按牌型补:别的点数都是 m 张、只有最后那个点数少一张(对子、三张、连对、飞机),补那个点数;
     * 都是单张且连着(顺子,从大到小排),补下一个。补不出为 null。
     */
    private fun byShape(rest: List<String>?): String? {
        if (rest.isNullOrEmpty()) return null
        val counts = rest.groupingBy { it }.eachCount()
        val last = rest.last()
        val m = counts.values.max()
        if (counts.all { (rank, n) -> n == if (rank == last) m - 1 else m } || counts.size == 1) return last
        val ord = rest.map { ORDER.getValue(it) }
        val straight = m == 1 && ord.zipWithNext().all { (a, b) -> a - b == 1 } && ord.last() > ORDER.getValue("3")
        return if (straight) RANKS[ord.last() - 1] else null
    }

    private fun handGlyph(frame: RawFrame, x: Int): Pair<Array<BooleanArray>, Boolean>? {
        val rows = 600 until 800
        val top = rows.firstOrNull { y -> (x until x + 60).count { card(frame.pixel(it, y)) } > 48 } ?: return null
        val ink = { y: Int -> (x until x + 60).count { min(frame.pixel(it, y)) < 150 } >= 3 }
        val start = (top until 800 - 84).firstOrNull(ink) ?: return null
        val glyph = Array(84) { r -> BooleanArray(60) { c -> min(frame.pixel(x + c, start + r)) < 150 } }
        return glyph to (share(frame, x, start, glyph) { r, _, b -> r - b > 60 } > 0.5)
    }

    /** 墨迹里有多少比例是红的;手牌和桌面按各自生成样本时的口径判。 */
    private inline fun share(frame: RawFrame, x: Int, y: Int, ink: Array<BooleanArray>, red: (Int, Int, Int) -> Boolean): Double {
        var hits = 0
        var all = 0
        for (r in ink.indices) for (c in ink[r].indices) if (ink[r][c]) {
            all++
            val p = frame.pixel(x + c, y + r)
            if (red(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)) hits++
        }
        return if (all == 0) 0.0 else hits.toDouble() / all
    }

    /** 只留点数字:从第一行墨迹到第一行空白,再收紧左右;太矮的不算。 */
    private fun rankBox(ink: Array<BooleanArray>): Array<BooleanArray>? {
        val top = ink.indexOfFirst { row -> row.any { it } }.takeIf { it >= 0 } ?: return null
        var end = top
        while (end < ink.size && ink[end].any { it }) end++
        if (end - top < 15) return null
        val rows = ink.copyOfRange(top, end)
        val left = rows.minOf { row -> row.indexOfFirst { it }.let { if (it < 0) Int.MAX_VALUE else it } }
        val right = rows.maxOf { row -> row.indexOfLast { it } }
        return Array(rows.size) { r -> rows[r].copyOfRange(left, right + 1) }
    }

    /** 盒式缩放到 [w]×[h],一格里四成以上是墨迹就算有。和生成样本的脚本逐格同样算。 */
    private fun boxSig(ink: Array<BooleanArray>, w: Int, h: Int): BooleanArray {
        val sh = ink.size
        val sw = ink[0].size
        return BooleanArray(w * h) { idx ->
            val i = idx / w
            val j = idx % w
            val r0 = i * sh / h
            val r1 = maxOf(r0 + 1, (i + 1) * sh / h)
            val c0 = j * sw / w
            val c1 = maxOf(c0 + 1, (j + 1) * sw / w)
            var on = 0
            for (r in r0 until r1) for (c in c0 until c1) if (ink[r][c]) on++
            on >= 0.4 * (r1 - r0) * (c1 - c0)
        }
    }

    private fun nearest(glyphs: List<Exemplar>, sig: BooleanArray, sure: Double, margin: Double): String? {
        var best = Double.MAX_VALUE
        var bestRank = ""
        val byRank = HashMap<String, Double>()
        for (g in glyphs) {
            var diff = 0
            for (i in sig.indices) if (sig[i] != g.bits[i]) diff++
            val d = diff.toDouble() / sig.size
            if (d < (byRank[g.rank] ?: Double.MAX_VALUE)) byRank[g.rank] = d
            if (d < best) { best = d; bestRank = g.rank }
        }
        val second = byRank.filterKeys { it != bestRank }.values.minOrNull() ?: 1.0
        return bestRank.takeIf { best <= sure && second - best >= margin }
    }

    private fun joker(rank: String, red: Boolean) = if (rank == "JOKER") (if (red) "BJ" else "SJ") else rank

    private fun content(frame: RawFrame): Pair<Int, Double> {
        val bar = DdzScreen.blackBar(frame)
        return bar to (frame.width - bar) / CONTENT_WIDTH
    }

    private inline fun count(frame: RawFrame, x0: Int, x1: Int, y0: Int, y1: Int, hit: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until minOf(x1, frame.width)) {
            val p = frame.pixel(x, y)
            if (hit(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)) n++
        }
        return n
    }

    private fun textInk(p: Int): Boolean {
        val r = p shr 16 and 0xff
        val g = p shr 8 and 0xff
        val b = p and 0xff
        // 点数的红比地主绶带暗;绶带那种亮红不算字
        return maxOf(r, g, b) < 120 || (r in 141..205 && g < 100 && b < 100)
    }

    private fun card(p: Int) = min(p) > 140 && spread(p) < 40
    private fun min(p: Int) = minOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
    private fun spread(p: Int) = maxOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) - min(p)

    private fun load(path: String, size: Int): List<Exemplar> =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (rank, hex) = line.split(' ')
            val bytes = hex.chunked(2).map { it.toInt(16) }
            Exemplar(rank, BooleanArray(size) { i -> bytes[i / 8] shr (7 - i % 8) and 1 == 1 })
        }

    val RANKS = listOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "SJ", "BJ")
    val ORDER = RANKS.withIndex().associate { (i, r) -> r to i }
}
