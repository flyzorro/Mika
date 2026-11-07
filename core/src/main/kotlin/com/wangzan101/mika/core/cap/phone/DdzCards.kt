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
    /**
     * 底牌更小,5 和 6 这种只差几笔。底牌认错只让 DouZero 少一点参考、出的牌照样合规(DouZero 服务会把对不上的底牌丢掉;
     * 出牌历史错了才会对不上),所以取最像的那个,不为它停掉整局。
     */
    private const val BOTTOM_SURE = 0.3
    private const val BOTTOM_MARGIN = 0.0
    /** 认不准、留给 DouZero 服务按牌规补的那一张,后面跟着猜的几个点数。 */
    const val UNKNOWN = "?"
    private const val GUESSES = 3
    /** 手牌的牌顶从这里往下找:闯关牌桌上抬起的牌顶在 582;再往上是按钮。 */
    private const val HAND_TOP = 560
    /**
     * 闯关牌桌(只在 Mika 的屏上)的出牌区:牌更大、更靠上,按实测像素取,不随屏宽缩放。牌顶 156、高 189、间距 67.5、宽 130;
     * 往下 354 起是自己出的牌,所以只读到 400(凑不够 150 行牌面);150 行处也已过了花色,量得出整排的宽。闹钟在对家张数框的里侧。
     */
    private const val STAGE_PLAY_Y0 = 130
    private const val STAGE_PLAY_Y1 = 400
    private const val STAGE_SCALE = 1.27
    /** 左家的牌紧贴着他的张数框(368-432)右边,从 450 起。 */
    private val STAGE_LEFT = 440 to 1100
    private val STAGE_RIGHT = 1300 to 2005
    private const val CONTENT_WIDTH = 2292.0

    private val handGlyphs by lazy { load("/ddz/hand.txt", HAND_W * HAND_H) }
    private val tableGlyphs by lazy { load("/ddz/table.txt", TABLE_W * TABLE_H) }

    /** 认牌要读的行;只在要认的那一刻读,平时轮询只读 [DdzScreen.ROWS]。 */
    val ROWS: List<Int> = ((5 until 105) + (STAGE_PLAY_Y0 until 420) + (HAND_TOP until 800)).toList()

    /** 手牌从左到右;有一张认不准、或者不是从大到小排的(还在发牌),返回 null。 */
    fun hand(frame: RawFrame, view: DdzView): List<String>? {
        val k = content(frame).second
        // Mika 的屏横向放大约 5%,字也跟着变宽:切的窗口一起放大,缩放到同样大小后才对得上
        val width = (60 * k).roundToInt()
        val ranks = (0 until view.handCount).map { i ->
            val (ink, red) = handGlyph(frame, view.handLeft + (i * view.pitch).roundToInt() + (8 * k).roundToInt(), width, view.handRow) ?: return null
            nearest(handGlyphs, boxSig(ink, HAND_W, HAND_H), sure = 0.2, margin = 0.0)?.let { joker(it, red) } ?: return null
        }
        return ranks.takeIf { it.zipWithNext().all { (a, b) -> ORDER.getValue(a) >= ORDER.getValue(b) } }
    }

    /** 一家出牌区:亮着的牌、「不出」,还是空的。地主最后一张右上角压着绶带,认不准时按牌型补。 */
    fun play(frame: RawFrame, side: DdzSide): DdzSeen {
        val (bar, k) = content(frame)
        val stage = singleStageTable(frame)
        val cards = if (stage) {
            val (x0, x1) = if (side == DdzSide.Left) STAGE_LEFT else STAGE_RIGHT
            strip(frame, x0, x1, STAGE_PLAY_Y0, STAGE_PLAY_Y1, pitch = 67.5, width = 130.0, glyphRows = 76, glyphWidth = 58, k = k * STAGE_SCALE, minRows = 150, probeNearBottom = true)
        } else {
            val (x0, x1) = if (side == DdzSide.Left) bar + (280 * k).toInt() to bar + (880 * k).toInt()
            else bar + (1360 * k).toInt() to bar + (1990 * k).toInt()
            strip(frame, x0, x1, 220, 420, pitch = 53 * k, width = 103 * k, glyphRows = 60, glyphWidth = (44 * k).toInt(), k = k)
        }
        if (cards == null) return if (passed(frame, side, bar, k, stage)) DdzSeen.Pass else DdzSeen.Empty
        val ranks = cards.map { it.rank }.toMutableList()
        // 地主出的最后一张右上角压着绶带,常认不准:记成 ?,后面按字形从像到不像带上几个点数(如 ?5,6,9),
        // 由 DouZero 服务按牌规补:只有一种补法就用它,有几种就取头一个说得通的。别的牌认不准就是认不准。
        if (ranks.last() == null && ranks.dropLast(1).all { it != null }) ranks[ranks.lastIndex] = UNKNOWN + cards.last().guesses.joinToString(",")
        return if (ranks.any { it == null }) DdzSeen.Unsure else DdzSeen.Cards(ranks.map { it!! })
    }

    /** 三张底牌;没摆出来或认不准为 null。 */
    fun bottom(frame: RawFrame): List<String>? {
        if (singleStageTable(frame)) return stageBottom(frame)
        val (bar, k) = content(frame)
        val cards = strip(frame, bar + (1050 * k).toInt(), bar + (1380 * k).toInt(), 5, 105, pitch = 44 * k, width = 57 * k,
            glyphRows = 46, glyphWidth = (32 * k).toInt(), k = k, minRows = 60, sure = BOTTOM_SURE, margin = BOTTOM_MARGIN) ?: return null
        return cards.map { it.rank }.takeIf { it.size == 3 && it.all { r -> r != null } }?.map { it!! }
    }

    /** 认大厅、闯关页要读的行。 */
    val LOBBY_ROWS: List<Int> = ((5 until 105) + (885 until 925) + STAGE_BUTTON_ROW).toList()
    private const val STAGE_BUTTON_ROW = 950

    private val lobbyRibbon by lazy { mark("/ddz/lobby.txt") }
    private val stageTitle by lazy { mark("/ddz/stage.txt") }
    private val stageTableTitle by lazy { mark("/ddz/stage_table.txt") }
    private const val MARK_W = 96
    private const val MARK_H = 20

    /**
     * 是不是大厅、右下角快速开始上面标着「单机模式」。只认 Mika 的屏(铺满、没有黑边):主屏左边有黑边,
     * 上面还压着 Mika 自己的悬浮胶囊,认不准。真人模式的标签字不一样,对不上。
     */
    fun singleLobby(frame: RawFrame): Boolean = matches(frame, 2125, 885, 195, 40, lobbyRibbon, MARK_SLACK, ::cream)

    /** 是不是单机模式里的闯关页(左上角写着「闯关模式」,报名首次免费、过关奖励金币)。同样只认 Mika 的屏。 */
    fun singleStage(frame: RawFrame): Boolean = matches(frame, 190, 60, 255, 45, stageTitle, STAGE_SLACK) { min(it) > 200 }

    /**
     * 闯关页上该点的按钮中线横坐标:没报名时是黄色的「报名」,闯到一半是绿色的「继续闯关」(第二个为 true)。
     * 它右边蓝色的是「放弃闯关」,绝不点。两个都找不到为 null。
     */
    fun stageButton(frame: RawFrame): Pair<Int, Boolean>? {
        fun at(hit: (Int, Int, Int) -> Boolean) = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
            val p = frame.pixel(x, STAGE_BUTTON_ROW)
            hit(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
        }.singleOrNull()?.let { (it.first + it.last) / 2 }
        at { r, g, b -> r > 225 && g in 131..214 && b < 120 }?.let { return it to false }
        return at { r, g, b -> g > 150 && r < 140 && b < 120 }?.let { it to true }
    }

    /** 是不是闯关的牌桌(右上角写着「闯关模式」):闯关只在单机模式里有。同样只认 Mika 的屏。 */
    fun singleStageTable(frame: RawFrame): Boolean = matches(frame, 1830, 8, 190, 52, stageTableTitle, STAGE_SLACK) { min(it) > 200 }

    /** 这块地方的字和记下的样子差不多:同一位置、同一大小,差的格子不超过 [slack]。 */
    private inline fun matches(frame: RawFrame, x: Int, y: Int, w: Int, h: Int, mark: BooleanArray, slack: Double, ink: (Int) -> Boolean): Boolean {
        if (frame.height != 1080 || frame.width != 2412 || DdzScreen.blackBar(frame) != 0) return false
        val sig = boxSig(Array(h) { r -> BooleanArray(w) { c -> ink(frame.pixel(x + c, y + r)) } }, MARK_W, MARK_H)
        return sig.indices.count { sig[it] != mark[it] } <= slack * sig.size
    }

    private const val MARK_SLACK = 0.05
    /** 标题字大、笔画多,同一页前后帧也有几格差;别的页差三成。 */
    private const val STAGE_SLACK = 0.08

    private fun mark(path: String): BooleanArray {
        val hex = javaClass.getResourceAsStream(path)!!.bufferedReader().readLines().first { !it.startsWith("#") }.trim()
        val bytes = hex.chunked(2).map { it.toInt(16) }
        return BooleanArray(MARK_W * MARK_H) { i -> bytes[i / 8] shr (7 - i % 8) and 1 == 1 }
    }

    private fun cream(p: Int): Boolean {
        val r = p shr 16 and 0xff
        val g = p shr 8 and 0xff
        val b = p and 0xff
        return r > 200 && g > 170 && b > 110 && r - b < 140
    }

    /** 闹钟在谁桌前;在自己这里或者没有为 null。 */
    fun clock(frame: RawFrame): DdzSide? {
        val (bar, k) = content(frame)
        if (singleStageTable(frame)) {
            fun gold(x: Int) = count(frame, x, x + 80, 140, 230) { r, g, b -> r > 200 && g in 151..214 && b < 90 }
            return when {
                gold(440) >= 300 -> DdzSide.Left
                gold(1940) >= 300 -> DdzSide.Right
                else -> null
            }
        }
        fun gold(x: Int) = count(frame, x, x + (90 * k).toInt(), 220, 320) { r, g, b -> r > 200 && g in 151..214 && b < 90 }
        return when {
            gold(bar + (310 * k).toInt()) >= 300 -> DdzSide.Left
            gold(bar + (1900 * k).toInt()) >= 300 -> DdzSide.Right
            else -> null
        }
    }

    private fun passed(frame: RawFrame, side: DdzSide, bar: Int, k: Double, stage: Boolean): Boolean {
        if (stage) {
            val (x0, x1) = if (side == DdzSide.Left) STAGE_LEFT else STAGE_RIGHT
            return count(frame, x0, x1, 150, 230) { r, g, b -> minOf(r, g, b) > 235 && maxOf(r, g, b) - minOf(r, g, b) < 25 } >= 300
        }
        val x = bar + ((if (side == DdzSide.Left) 320 else 1830) * k).toInt()
        return count(frame, x, x + (160 * k).toInt(), 320, 410) { r, g, b -> minOf(r, g, b) > 235 && maxOf(r, g, b) - minOf(r, g, b) < 25 } >= 300
    }

    /**
     * 一排桌面上的牌:按白色牌面的总宽算张数,只在每张牌左上角找点数(避开右上角的绶带)。没有牌面为 null;
     * 列表里认不准的那张为 null。
     */
    private fun strip(
        frame: RawFrame, x0: Int, x1: Int, y0: Int, y1: Int, pitch: Double, width: Double, glyphRows: Int, glyphWidth: Int,
        k: Double, minRows: Int = 100, sure: Double = SURE, margin: Double = MARGIN, probeNearBottom: Boolean = false,
    ): List<Read>? {
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
        // 牌面贴着区域底边时探测行不能越出读到的行;闯关的大牌中间可能是王的图案,量宽度要到牌的下沿附近
        val bottom = (top - y0 until rich.size).firstOrNull { !rich[it] }?.let { y0 + it } ?: y1
        val probe = if (probeNearBottom) bottom - 10 else minOf(top + minRows, y1 - 1)
        val span = DdzScreen.runs(right - x0, gap = 12, minWidth = 40) { white(x0 + it, probe) }.maxByOrNull { it.last - it.first } ?: return null
        val n = ((span.last - span.first + 1 - width) / pitch).roundToInt() + 1
        if (n !in 1..20) return null
        return (0 until n).map { i ->
            val cx = x0 + span.first + (i * pitch).toInt() + 3
            // 只有最后一张右上角可能压着绶带;前面的牌点数(10 有两位)可以看全,但别碰到下一张牌左边的阴影
            val wide = (if (i == n - 1) minOf(glyphWidth.toDouble(), pitch) - 3 else minOf(pitch - 8, 48 * k)).toInt()
            val ink = Array(glyphRows) { r -> BooleanArray(wide) { c -> textInk(frame.pixel(cx + c, top + 4 + r)) } }
            val box = rankBox(ink) ?: return@map Read(null, emptyList())
            val red = share(frame, cx, top + 4, ink) { r, g, _ -> r - g > 50 } > 0.5
            val ranked = ranked(tableGlyphs, boxSig(box, TABLE_W, TABLE_H))
            Read(sure(ranked, sure, margin)?.let { joker(it, red) }, ranked.take(GUESSES).map { joker(it.first, red) })
        }
    }

    private fun handGlyph(frame: RawFrame, x: Int, width: Int, from: Int): Pair<Array<BooleanArray>, Boolean>? {
        // 从牌面上往上走,走出牌面(桌布、炸弹的火苗)为止:上方自己刚出的牌和手牌之间隔着一道桌布
        // (闯关牌桌上只隔二十几像素),不会连成一张
        // 走到一行大半是蓝色桌布或橙色火苗为止;牌面上的字(连红字边上的粉色)都不算
        fun offCard(y: Int) = (x until x + width).count { frame.pixel(it, y).let { p ->
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            b - r > 50 || (r > 200 && g > 130 && b < 100)
        } } > width / 2
        val top = (from downTo HAND_TOP).firstOrNull(::offCard)?.plus(1) ?: return null
        val ink = { y: Int -> (x until x + width).count { min(frame.pixel(it, y)) < 150 } >= 3 }
        // 牌顶那一行左上是圆角,背景的暗色会当成墨
        val start = (top + 4 until 800 - 84).firstOrNull(ink) ?: return null
        val glyph = Array(84) { r -> BooleanArray(width) { c -> min(frame.pixel(x + c, start + r)) < 150 } }
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

    /**
     * 闯关牌桌的底牌:顶上正中三张分开摆的小牌,点数在左、花色在右。按第 10 行的白色牌面找出三张,
     * 每张按列切开,头一段墨迹是点数(10 的 1 很窄,和后面的 0 并成一段)。
     */
    private fun stageBottom(frame: RawFrame): List<String>? {
        fun white(x: Int) = frame.pixel(x, 10).let { p -> min(p) > 190 && spread(p) < 45 }
        val cards = DdzScreen.runs(1380, gap = 3, minWidth = 60) { x -> x >= 1040 && white(x) }
        if (cards.size != 3) return null
        return cards.map { card ->
            val x = card.first + 3
            val all = Array(46) { r -> BooleanArray(card.last - card.first - 6) { c ->
                frame.pixel(x + c, 8 + r).let { p -> maxOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) < 120 || ((p shr 16 and 0xff) > 120 && (p shr 8 and 0xff) < 100 && (p and 0xff) < 100) }
            } }
            val segments = DdzScreen.runs(all[0].size, gap = 1, minWidth = 0) { c -> all.any { it[c] } }
            val first = segments.firstOrNull() ?: return null
            val end = (if (first.last - first.first < 12) segments.getOrNull(1) ?: first else first).last
            val ink = Array(all.size) { r -> all[r].copyOfRange(0, end + 1) }
            val box = rankBox(ink) ?: return null
            val red = share(frame, x, 8, ink) { r, g, _ -> r - g > 50 } > 0.5
            // 样本是经典牌桌的底牌,闯关的字体略有不同(K 差 0.31);底牌只是参考,放宽些、但要和第二像的拉开
            sure(ranked(tableGlyphs, boxSig(box, TABLE_W, TABLE_H)), STAGE_BOTTOM_SURE, STAGE_BOTTOM_MARGIN)?.let { joker(it, red) } ?: return null
        }
    }
    private const val STAGE_BOTTOM_SURE = 0.36
    private const val STAGE_BOTTOM_MARGIN = 0.03

    /** 一张桌面牌:[rank] 认准了的点数,认不准为 null;[guesses] 字形从像到不像的几个点数,给认不准的绶带牌用。 */
    private class Read(val rank: String?, val guesses: List<String>)

    /** 每个点数最像的样本有多像,从像到不像排。 */
    private fun ranked(glyphs: List<Exemplar>, sig: BooleanArray): List<Pair<String, Double>> {
        val byRank = HashMap<String, Double>()
        for (g in glyphs) {
            var diff = 0
            for (i in sig.indices) if (sig[i] != g.bits[i]) diff++
            val d = diff.toDouble() / sig.size
            if (d < (byRank[g.rank] ?: Double.MAX_VALUE)) byRank[g.rank] = d
        }
        return byRank.toList().sortedBy { it.second }
    }

    /** 最像的点数;够像([sure] 以内)、又和第二像的点数拉得开([margin])才算认准,否则 null。 */
    private fun sure(ranked: List<Pair<String, Double>>, sure: Double, margin: Double): String? {
        val (best, d) = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 1.0
        return best.takeIf { d <= sure && second - d >= margin }
    }

    private fun nearest(glyphs: List<Exemplar>, sig: BooleanArray, sure: Double, margin: Double): String? = sure(ranked(glyphs, sig), sure, margin)

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
        // 点数的红比地主绶带暗;绶带那种亮红不算字。闯关大牌上王的红字更暗,到 120
        return maxOf(r, g, b) < 120 || (r in 120..205 && g < 100 && b < 100)
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
