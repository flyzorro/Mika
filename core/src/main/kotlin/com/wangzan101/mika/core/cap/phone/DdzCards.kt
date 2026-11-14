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
    private const val STAGE_LEFT_EDGE = 460
    private const val STAGE_RIGHT_EDGE = 1990
    private const val CONTENT_WIDTH = 2292.0
    private const val MIKA_PLAY_PITCH = 51.5

    private val handGlyphs by lazy { load("/ddz/hand.txt", HAND_W * HAND_H) }
    private val tableGlyphs by lazy { load("/ddz/table.txt", TABLE_W * TABLE_H) }
    private val countGlyphs by lazy { load("/ddz/count.txt", COUNT_W * COUNT_H) }
    private const val COUNT_W = 12
    private const val COUNT_H = 18

    /** 认牌要读的行;只在要认的那一刻读,平时轮询只读 [DdzScreen.ROWS]。 */
    val ROWS: List<Int> = ((5 until 105) + (STAGE_PLAY_Y0 until 420) + (HAND_TOP until 800) + STAGE_CONTINUE_SCAN_Y).toList()

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
            // 左家的牌从 450 往右排,右家的排到 2000 为止;正中翻开的三张底牌两边都不挨着
            val edge = if (side == DdzSide.Left) STAGE_LEFT_EDGE else STAGE_RIGHT_EDGE
            // 王的竖排红字正好压在边上那一列:往里多看两列,有一列够白就算
            val inward = if (side == DdzSide.Left) 1 else -1
            val anchored = listOf(edge - 8 * inward, edge, edge + 10 * inward, edge + 20 * inward).any { x ->
                (STAGE_PLAY_Y0 until STAGE_PLAY_Y1).count { y -> frame.pixel(x, y).let { p -> min(p) > 190 && spread(p) < 45 } } >= 100
            }
            if (!anchored) null else strip(frame, x0, x1, STAGE_PLAY_Y0, STAGE_PLAY_Y1, pitch = 67.5, width = 130.0, glyphRows = 76, glyphWidth = 58, k = k * STAGE_SCALE, wideLast = 72, minRows = 150, probeNearBottom = true, wrapDy = WRAP_DY)
        } else {
            val (x0, x1) = if (side == DdzSide.Left) bar + (280 * k).toInt() to bar + (880 * k).toInt()
            else bar + (1360 * k).toInt() to bar + (1990 * k).toInt()
            // 出的牌间距:主屏 53;Mika 的屏(没黑边)只有 51.5,不跟着横向放大,按 53 × 1.05 算六七张就错位
            // 真人右家十张牌排满后,地主绶带那张会在最后一张下面错开一格
            // 经典牌桌上一排摆满九张后,第十张也从同一个左边接着摆第二排:牌顶比第一排低 [CLASSIC_WRAP_DY],
            // 第一排的牌面下沿以下 [CLASSIC_WRAP_BELOW] 那一行探它
            strip(frame, x0, x1, 220, 420, pitch = if (bar == 0) MIKA_PLAY_PITCH else 53 * k, width = 103 * k, glyphRows = 60, glyphWidth = (44 * k).toInt(),
                k = k, wideLast = (48 * k).toInt(), tailDy = if (side == DdzSide.Right) HUMAN_RIBBON_DY else null,
                probeTop = CLASSIC_PROBE_TOP, wrapDy = CLASSIC_WRAP_DY, wrapBelow = CLASSIC_WRAP_BELOW,
                wrapFull = true, wrapWhite = CLASSIC_WRAP_WHITE)
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
        counterBottom(frame)?.let { return it }
        val (bar, k) = content(frame)
        val cards = strip(frame, bar + (1050 * k).toInt(), bar + (1380 * k).toInt(), 5, 105, pitch = 44 * k, width = 57 * k,
            glyphRows = 46, glyphWidth = (32 * k).toInt(), k = k, minRows = 60, sure = BOTTOM_SURE, margin = BOTTOM_MARGIN)
        cards?.map { it.rank }?.takeIf { it.size == 3 && it.all { r -> r != null } }?.let { return it.map { r -> r!! } }
        return revealedBottom(frame, bar, k)
    }

    /**
     * 叫完地主、加倍时,三张底牌会放大摆在牌桌上方正中(K Q 8 这一帧):牌面从 120 行到约 290 行,
     * 三张牌宽约 123、间距约 135,整排以内容区居中。认不出为 null。
     */
    private fun revealedBottom(frame: RawFrame, bar: Int, k: Double): List<String>? {
        val cards = strip(frame, bar + (953 * k).toInt(), bar + (1347 * k).toInt(), 120, 310, pitch = 135 * k, width = 123 * k,
            glyphRows = 60, glyphWidth = (56 * k).toInt(), k = k, minRows = 60, sure = BOTTOM_SURE, margin = BOTTOM_MARGIN,
            probeNearBottom = true, gap = 20, spanAll = true) ?: return null
        return cards.map { it.rank }.takeIf { it.size == 3 && it.all { r -> r != null } }?.map { it!! }
    }

    /**
     * 开了「记牌器」时的底牌:顶上正中换成一条横条,右端紧接着三张小号底牌。三张牌背是浅灰(比横条的白底暗一截),
     * 在点数与花色之间那一行正好是三段并排的浅灰;点数比桌面牌还小,用桌面牌的字形认,认不准为 null。
     * 认不出横条(老画面、叫分阶段扣着的牌背)为 null,交回 [bottom] 原来的读法。
     */
    private fun counterBottom(frame: RawFrame): List<String>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        if ((COUNTER_RANK_Y0 until COUNTER_RANK_Y0 + COUNTER_RANK_H).any { frame.row(it) == null }) return null
        fun cardBg(p: Int): Boolean {
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            return minOf(r, g, b) >= 200 && maxOf(r, g, b) < 232 && maxOf(r, g, b) - minOf(r, g, b) < 20
        }
        val cards = DdzScreen.runs(frame.width, gap = 2, minWidth = 35) { x -> cardBg(frame.pixel(x, COUNTER_GAP_Y)) }
        if (cards.size != 3) return null
        // 三张并排、都在横条右端;横条左边的点数表头和剩余张数不是这种浅灰,绝不会被当成底牌
        if (cards[0].first !in COUNTER_CARDS_X0 || cards[2].last !in COUNTER_CARDS_X1) return null
        if (cards[1].first - cards[0].last > 4 || cards[2].first - cards[1].last > 4) return null
        return cards.map { card ->
            val ink = Array(COUNTER_RANK_H) { r -> BooleanArray(card.last - card.first + 1) { c ->
                val p = frame.pixel(card.first + c, COUNTER_RANK_Y0 + r)
                minOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) < COUNTER_INK
            } }
            val box = inkBox(ink) ?: return null
            val red = share(frame, card.first, COUNTER_RANK_Y0, ink) { r, g, _ -> r - g > 50 } > 0.5
            sure(ranked(tableGlyphs, boxSig(box, TABLE_W, TABLE_H)), COUNTER_SURE, COUNTER_MARGIN)?.let { joker(it, red) } ?: return null
        }
    }

    private const val COUNTER_GAP_Y = 80
    private const val COUNTER_RANK_Y0 = 18
    private const val COUNTER_RANK_H = 66
    private const val COUNTER_INK = 120
    private const val COUNTER_SURE = 0.3
    private const val COUNTER_MARGIN = 0.05
    private val COUNTER_CARDS_X0 = 1260..1280
    private val COUNTER_CARDS_X1 = 1420..1440

    /** 认大厅、闯关页要读的行。 */
    val LOBBY_ROWS: List<Int> = ((5 until 105) + (100 until 135) + (885 until 925) + STAGE_BUTTON_ROW).toList()
    private const val STAGE_BUTTON_ROW = 950

    private val lobbyRibbon by lazy { mark("/ddz/lobby.txt") }
    private val stageTitle by lazy { mark("/ddz/stage.txt") }
    private val stageTableTitle by lazy { mark("/ddz/stage_table.txt") }
    private val singleRoomsTitle by lazy { mark("/ddz/single_rooms.txt") }
    private val stageWinBanner by lazy { mark("/ddz/stage_win.txt") }
    private val stageLossBanner by lazy { mark("/ddz/stage_loss.txt") }
    private const val MARK_W = 96
    private const val MARK_H = 20

    /**
     * 是不是大厅、右下角快速开始上面标着「单机模式」。只认 Mika 的屏(铺满、没有黑边):主屏左边有黑边,
     * 上面还压着 Mika 自己的悬浮胶囊,认不准。真人模式的标签字不一样,对不上。
     */
    fun singleLobby(frame: RawFrame): Boolean = matches(frame, 2125, 885, 195, 40, lobbyRibbon, MARK_SLACK, ink = ::cream)

    /** 是不是单机模式里的闯关页(左上角写着「闯关模式」,报名首次免费、过关奖励金币)。同样只认 Mika 的屏。 */
    fun singleStage(frame: RawFrame): Boolean = matches(frame, 190, 60, 255, 45, stageTitle, STAGE_SLACK) { min(it) > 200 }

    /**
     * 游戏主大厅:左绿「单机模式」、中橙「真人对战」、右蓝「好友约牌」三张卡片。真人局从这里点中间的卡片进真人场。
     * 只认 Mika 的屏(铺满、没有黑边):主屏左边有黑边,上面还压着 Mika 的悬浮胶囊。
     */
    fun humanMainLobby(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080 || DdzScreen.blackBar(frame) != 0) return false
        fun rgb(x: Int, y: Int) = frame.pixel(x, y).let { p -> Triple(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) }
        fun teal(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> r < 90 && g > 150 && b > 145 && kotlin.math.abs(g - b) < 70 }
        fun gold(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> r > 220 && g > 180 && b < 190 && r - b > 45 && g - b > 25 }
        fun blue(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> b > 200 && r < 135 && g > 135 }
        val tealCards = listOf(580 to 600, 620 to 600, 980 to 600).count { (x, y) -> teal(x, y) }
        val goldCards = listOf(1400 to 300, 1400 to 400, 1450 to 400).count { (x, y) -> gold(x, y) }
        val blueCards = listOf(1600 to 300, 1900 to 300, 1600 to 400, 1980 to 400).count { (x, y) -> blue(x, y) }
        return tealCards >= 2 && goldCards >= 2 && blueCards >= 2
    }

    /**
     * 金豆输光后冷启动,游戏会开在商店的金豆页:4×2 张米色礼包卡,每张顶上都是一整条平整的米色边。
     * 大厅、场次页、牌桌、结算、弹窗、广告都没有这样八个位置同时是米色的。只认 Mika 的屏。
     * 这一页上除了左上角的返回箭头,点哪儿都要花钻石,所以认出来只回它的返回箭头,别的一概不点。
     */
    fun humanShop(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080 || DdzScreen.blackBar(frame) != 0) return false
        fun pale(x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
            var creamy = 0
            var pixels = 0
            for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
                if (cream(frame.pixel(x, y))) creamy++
                pixels++
            }
            return creamy.toDouble() / pixels >= SHOP_PALE_DENSITY
        }
        return SHOP_COLUMNS.all { (x0, x1) -> SHOP_CARDS.all { (y0, y1) -> pale(x0, x1, y0, y1) } }
    }

    /** 八张礼包卡的横坐标(卡面左右留一点边)和上下两排卡片顶上的米色边。 */
    private val SHOP_COLUMNS = listOf(600 to 910, 1007 to 1316, 1413 to 1722, 1820 to 2129)
    private val SHOP_CARDS = listOf(180 to 200, 612 to 632)
    private const val SHOP_PALE_DENSITY = 0.9

    /**
     * 真人经典场次页:左边绿色的「普通场」,右边青色的「精英场」、蓝色的「土豪场」(金豆场次)。
     * 单机的场次页是 M 币、左边写着「经典模式」、上方 S11 赛季,颜色对不上。只认 Mika 的屏。
     *
     * 卡片会整体左右平移(2026-11-12 真机上比仓库里的老图右移约 50 px),所以不按死点 x 认:
     * 在卡片顶部那几行里找绿、青、蓝三段连续的色块,要求从左到右排列、每段宽度约一张卡(400 px)、
     * 段与段之间隔着卡片间隙。这样横移 ±80 px 都还在。
     */
    fun humanClassicRooms(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080 || DdzScreen.blackBar(frame) != 0) return false
        fun rgb(x: Int, y: Int) = frame.pixel(x, y).let { p -> Triple(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) }
        fun green(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> r < 120 && g > 165 && b > 100 && g - r > 60 }
        fun cyan(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> r < 80 && g > 170 && b > 160 }
        fun blue(x: Int, y: Int) = rgb(x, y).let { (r, g, b) -> b > 200 && r < 135 && g > 135 }
        // 卡片顶部纯色带:老图在 253-272,新图在 252-272,取中间那几行最稳
        for (y in ROOMS_BAND_ROWS) {
            val g = DdzScreen.runs(frame.width, gap = 30, minWidth = ROOMS_CARD_MIN_W) { green(it, y) }
            val c = DdzScreen.runs(frame.width, gap = 30, minWidth = ROOMS_CARD_MIN_W) { cyan(it, y) }
            val b = DdzScreen.runs(frame.width, gap = 30, minWidth = ROOMS_CARD_MIN_W) { blue(it, y) }
            val normal = g.firstOrNull() ?: continue
            val elite = c.firstOrNull { it.first > normal.last } ?: continue
            val rich = b.firstOrNull { it.first > elite.last } ?: continue
            if (normal.last - normal.first > ROOMS_CARD_MAX_W) continue
            if (elite.last - elite.first > ROOMS_CARD_MAX_W) continue
            if (rich.last - rich.first > ROOMS_CARD_MAX_W) continue
            return true
        }
        return false
    }

    /** 场次卡顶部色带所在的行:老图 253 起、新图 252 起,取 255-270 这几行都干净。 */
    private val ROOMS_BAND_ROWS = 255..270
    /** 一张场次卡约 400 px 宽;色块至少这么宽才算一张卡,再宽就不像了。 */
    private const val ROOMS_CARD_MIN_W = 300
    private const val ROOMS_CARD_MAX_W = 470

    /**
     * 单机模式的「经典模式」场次页(左上角「经典模式」、上方 S11 赛季、场次用 M 币):不是真人场,绝不能点。
     * 这页左边本身就是暗的,不看黑边,只靠标题字形认。
     */
    fun singleClassicRooms(frame: RawFrame): Boolean =
        matches(frame, 190, 100, 300, 35, singleRoomsTitle, STAGE_SLACK, bar = false) { min(it) > 200 }

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

    /**
     * 闯关打完一手赢了的结算(正中金色的「胜利」),[stageContinue] 是它的黄色「继续」:过了关只有这一个,
     * 没过关左边还有蓝色的「离开」。输了的结算不认:可能有看视频复活之类的按钮,交给模型。
     */
    fun stageWin(frame: RawFrame): Boolean = matches(frame, 1030, 130, 360, 120, stageWinBanner, STAGE_SLACK) { p ->
        (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) > 150 && (p and 0xff) < 140
    }

    /**
     * 闯关打完一手输了的结算(正中白色的「失败」)。左上有看视频免扣分的按钮,不点;只认左边蓝色「离开」、右边黄色「继续」
     * 这一种摆法([stageContinue] 才点继续),别的样子(比如分数不够、整关失败)交给模型。结算的遮罩把左右两边压得近乎全黑,
     * 不按黑边排除主屏,靠这两个按钮的摆法认。
     */
    fun stageLoss(frame: RawFrame): Boolean = matches(frame, 1030, 130, 360, 130, stageLossBanner, STAGE_SLACK, bar = false) { min(it) > 215 } &&
        DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
            frame.pixel(x, STAGE_CONTINUE_SCAN_Y).let { p -> (p and 0xff) > 200 && (p shr 16 and 0xff) < 120 }
        }.any { blue -> stageContinue(frame)?.let { blue.last < it.first } == true }

    /** 结算上黄色「继续」的中线;找不到为 null。在字下面那一行量(字会把黄色切开),点按钮正中。 */
    fun stageContinue(frame: RawFrame): Pair<Int, Int>? = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
        frame.pixel(x, STAGE_CONTINUE_SCAN_Y).let { p -> (p shr 16 and 0xff) > 225 && (p shr 8 and 0xff) in 131..214 && (p and 0xff) < 120 }
    }.lastOrNull()?.let { (it.first + it.last) / 2 to STAGE_CONTINUE_Y }
    private const val STAGE_CONTINUE_SCAN_Y = 805
    private const val STAGE_CONTINUE_Y = 765

    /** 是不是闯关的牌桌(右上角写着「闯关模式」):闯关只在单机模式里有。同样只认 Mika 的屏。 */
    fun singleStageTable(frame: RawFrame): Boolean = matches(frame, 1830, 8, 190, 52, stageTableTitle, STAGE_SLACK) { min(it) > 200 }

    /**
     * Positive marker for the current classic table. The center watermark is present on the
     * classic table and absent from the stage table, lobbies, and overlays. Phase recognition
     * alone is deliberately insufficient because colored buttons can occur on unrelated screens.
     */
    fun classicHumanTable(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080) return false
        // 闯关牌桌下面单独拒绝,这里按经典牌桌读:整手牌被点高时不会被误判成闯关
        val view = DdzScreen.read(frame, classic = true)
        // 轮到别家时自己没有按钮(phase 是 Other):手牌还在就还是在牌桌上等(下面照样要水印)。
        // 不看闹钟:三家同时加倍时没有闹钟,闹钟也常读不出
        // 广告视频大半是黑的(水印那一块也算「暗」)、左边一张白卡片会读成手牌:轮到别家的牌桌中间上方都是蓝色桌布,广告最多一成
        val waiting = view.phase == DdzPhase.Other && view.handCount > 0 && felt(frame) >= FELT_DENSITY
        // 真人场次页的场次卡会被读成一两张手牌,绝不能当牌桌;先按页面本身否定掉
        if ((view.phase == DdzPhase.Other && !waiting) || singleStageTable(frame) || singleLobby(frame) || singleStage(frame) || classicLobby(frame) || humanClassicRooms(frame)) return false
        // The game's "取消托管" overlay covers the table watermark. Its dedicated blue
        // button is a stronger positive signal than the obscured marker, and the caller
        // already handles AutoPlay by tapping that button before reading the table again.
        if (view.phase == DdzPhase.AutoPlay) return true
        var marked = 0
        var pixels = 0
        for (y in CLASSIC_MARK_Y until CLASSIC_MARK_Y + CLASSIC_MARK_H) for (x in CLASSIC_MARK_X until CLASSIC_MARK_X + CLASSIC_MARK_W) {
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            if (r < 75 && g < 120 && b < 180) marked++
            pixels++
        }
        return marked.toDouble() / pixels >= CLASSIC_MARK_DENSITY || view.phase == DdzPhase.Result
    }

    /**
     * 真人场(普通场等)的结算:下面一排绿「离开」、蓝「换桌」、黄「继续」三个大按钮。只看这一行里从左到右
     * 依次有够宽的绿、蓝、黄三段,不靠固定横坐标。
     */
    fun humanResult(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080) return false
        fun color(p: Int): Char? {
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            return when {
                g > 150 && r < 120 && b < 120 -> 'G'
                b > 200 && r < 120 -> 'B'
                r > 200 && g > 150 && b < 90 -> 'Y'
                else -> null
            }
        }
        val seen = StringBuilder()
        var run: Char? = null
        var length = 0
        for (x in 0 until frame.width) {
            val c = color(frame.pixel(x, HUMAN_RESULT_Y))
            if (c == run) { length++; continue }
            if (run != null && length >= HUMAN_RESULT_MIN_RUN && seen.lastOrNull() != run) seen.append(run)
            run = c
            length = 1
        }
        if (run != null && length >= HUMAN_RESULT_MIN_RUN && seen.lastOrNull() != run) seen.append(run)
        return "GBY" in seen
    }

    /**
     * 真人场结算页上绿色「离开」的中点。只认 [humanResult] 的画面;最左边那段绿色就是「离开」,
     * 绝不返回蓝色「换桌」、黄色「继续」;认不出为 null,调用方一下都不点。
     */
    fun humanResultLeave(frame: RawFrame): Pair<Int, Int>? {
        if (!humanResult(frame)) return null
        val run = DdzScreen.runs(frame.width, gap = 30, minWidth = HUMAN_RESULT_MIN_RUN) { x ->
            frame.pixel(x, HUMAN_RESULT_Y).let { p -> (p shr 8 and 0xff) > 150 && (p shr 16 and 0xff) < 120 && (p and 0xff) < 120 }
        }.firstOrNull() ?: return null
        return (run.first + run.last) / 2 to HUMAN_RESULT_TAP_Y
    }

    /** 三个按钮上半截的一行(按钮在 790-880 之间,字在中间);读屏的行里有它(手牌那一段到 800 为止)。 */
    private const val HUMAN_RESULT_Y = 795
    private const val HUMAN_RESULT_TAP_Y = 833
    private const val HUMAN_RESULT_MIN_RUN = 60

    /** A dimmed centered overlay, such as the relief popup, blocks the table. */
    /**
     * 经典模式的场次列表:左边一列选中的「经典模式」是一条金色的签。白色的场次卡会被读成手牌、蓝色的中级场读成按钮,
     * 不先排除就会当成轮到自己跟牌。牌桌这一块最多 6% 是金色,场次列表约 69%。
     */
    fun classicLobby(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080) return false
        var gold = 0
        var pixels = 0
        for (y in LOBBY_TAB_Y0 until LOBBY_TAB_Y1 step 2) for (x in LOBBY_TAB_X0 until LOBBY_TAB_X1 step 3) {
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            if (r > 190 && g > 140 && b < 110 && r - b > 100) gold++
            pixels++
        }
        return gold.toDouble() / pixels >= LOBBY_TAB_DENSITY
    }

    /** 牌桌中间上方(底牌下面、出牌区上面)一条里蓝色桌布的占比。 */
    private fun felt(frame: RawFrame): Double {
        var blue = 0
        var pixels = 0
        for (y in FELT_Y0 until FELT_Y1 step 3) for (x in FELT_X0 until FELT_X1 step 4) {
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val b = p and 0xff
            if (b > 110 && b - r > 50) blue++
            pixels++
        }
        return blue.toDouble() / pixels
    }

    private const val FELT_X0 = 1000
    private const val FELT_X1 = 1400
    private const val FELT_Y0 = 150
    private const val FELT_Y1 = 200
    private const val FELT_DENSITY = 0.5
    private const val LOBBY_TAB_X0 = 20
    private const val LOBBY_TAB_X1 = 380
    private const val LOBBY_TAB_Y0 = 250
    private const val LOBBY_TAB_Y1 = 310
    private const val LOBBY_TAB_DENSITY = 0.4

    fun unknownPopup(frame: RawFrame): Boolean {
        if (frame.width != 2412 || frame.height != 1080) return false
        // 真人结算页本来就是暗底、正中一大块亮面板,比例上刚好也够暗、够亮;它有确定的按钮排布,
        // 认出来就绝不是弹窗(炸弹特效散场后正是这一帧)。按钮被特效盖住时 humanResult 认不出,才走帧数确认。
        if (humanResult(frame)) return false
        var dark = 0
        var bright = 0
        var pixels = 0
        for (y in POPUP_SCAN_Y until POPUP_SCAN_Y + POPUP_SCAN_H) for (x in POPUP_SCAN_X until POPUP_SCAN_X + POPUP_SCAN_W) {
            val p = frame.pixel(x, y)
            if (min(p) < 35) dark++
            if (min(p) > 190) bright++
            pixels++
        }
        return dark.toDouble() / pixels >= POPUP_DARK_DENSITY && bright.toDouble() / pixels >= POPUP_BRIGHT_DENSITY
    }

    /**
     * 真人场金豆不够时弹的领金豆弹窗:米色底板、正中一个黄色「领取」。返回「领取」的中点;认不准为 null。
     * 和 [humanResult] / [unknownPopup] 一样固定区域比颜色,只认 2412×1080 的 Mika 屏。
     */
    fun humanReliefPopup(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        // 弹窗底板:正中一大片米色,别的画面没有这么整块的
        var creamy = 0
        var pixels = 0
        for (y in RELIEF_PANEL_Y0 until RELIEF_PANEL_Y1) for (x in RELIEF_PANEL_X0 until RELIEF_PANEL_X1) {
            if (cream(frame.pixel(x, y))) creamy++
            pixels++
        }
        if (creamy.toDouble() / pixels < RELIEF_PANEL_DENSITY) return null
        // 「领取」:按钮上下两条都是一整条金黄(中间被白字切开),正中够宽的那一条就是它
        var cx = 0
        for (y in listOf(RELIEF_CLAIM_TOP_Y, RELIEF_CLAIM_BOTTOM_Y)) {
            val run = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
                frame.pixel(x, y).let { p -> (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) in 121..214 && (p and 0xff) < 110 }
            }.singleOrNull() ?: return null
            if (run.first !in RELIEF_CLAIM_LEFT || run.last !in RELIEF_CLAIM_RIGHT) return null
            cx = (run.first + run.last) / 2
        }
        return cx to RELIEF_CLAIM_TAP_Y
    }

    /**
     * 「恭喜获得 金豆x5万」页:满屏亮黄横幅,下面绿「双倍领取」(带视频图标,绝不点)、右边文字链「普通领取」。
     * 返回「普通领取」的中点;认不准为 null。同样只认 Mika 的屏。
     */
    fun humanReliefClaimed(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        // 满屏亮黄横幅:中间一带几乎全是亮黄,别的画面没有
        var bright = 0
        var pixels = 0
        for (y in RELIEF_BANNER_Y0 until RELIEF_BANNER_Y1) for (x in RELIEF_BANNER_X0 until RELIEF_BANNER_X1) {
            val p = frame.pixel(x, y)
            if ((p shr 16 and 0xff) > 220 && (p shr 8 and 0xff) > 140 && (p and 0xff) < 140) bright++
            pixels++
        }
        if (bright.toDouble() / pixels < RELIEF_BANNER_DENSITY) return null
        // 绿色「双倍领取」:按钮下沿一整条绿,只用来认摆法,绝不给它的坐标
        val green = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
            frame.pixel(x, RELIEF_DOUBLE_Y).let { p -> (p shr 8 and 0xff) > 130 && (p shr 16 and 0xff) < 140 && (p and 0xff) < 140 }
        }.singleOrNull() ?: return null
        if (green.first !in RELIEF_DOUBLE_LEFT || green.last !in RELIEF_DOUBLE_RIGHT) return null
        // 「普通领取」文字链:它下面那条浅灰下划线,取中点;绝不会落进绿色按钮里
        val link = DdzScreen.runs(RELIEF_LINK_X1 - RELIEF_LINK_X0, gap = 10, minWidth = 80) { c ->
            frame.pixel(RELIEF_LINK_X0 + c, RELIEF_LINK_UNDERLINE_Y).let { p -> min(p) > 140 && spread(p) < 45 }
        }.singleOrNull() ?: return null
        return (RELIEF_LINK_X0 + (link.first + link.last) / 2) to RELIEF_LINK_TAP_Y
    }

    /**
     * 豆豆多了点低级场弹的「您的豆豆足够到高级的房间游戏啦」:米色底板从 y≈335 起(领金豆弹窗的底板从 200 起),
     * 底下一个黄色「立即前往」。返回右上角关闭叉的位置(点它只关弹窗,不点「立即前往」);认不准为 null。
     */
    fun humanBeansTooMany(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        fun density(y0: Int, y1: Int): Double {
            var creamy = 0
            var pixels = 0
            for (y in y0 until y1 step 2) for (x in BEANS_PANEL_X0 until BEANS_PANEL_X1 step 3) {
                if (cream(frame.pixel(x, y))) creamy++
                pixels++
            }
            return creamy.toDouble() / pixels
        }
        if (density(BEANS_PANEL_Y0, BEANS_PANEL_Y1) < 0.8) return null
        if (density(BEANS_ABOVE_Y0, BEANS_ABOVE_Y1) > 0.15) return null
        for (y in listOf(RELIEF_CLAIM_TOP_Y, RELIEF_CLAIM_BOTTOM_Y)) {
            val run = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
                frame.pixel(x, y).let { p -> (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) in 121..230 && (p and 0xff) < 110 }
            }.singleOrNull() ?: return null
            if (run.first !in 1000..1100 || run.last !in 1300..1420) return null
        }
        return BEANS_CLOSE_X to BEANS_CLOSE_Y
    }

    /**
     * 单机经典场一手打完,结算上弹的「当前金币可前往更高级场次」:正中一大块米色底板(从 y≈330 起),
     * 底下一个黄色「确定」,没有右上角的叉。返回「确定」的中点;认不准为 null。
     * 和 [humanBeansTooMany] 一样固定区域比颜色:豆豆太多弹窗的底板位置相同,但它的按钮不在这一行,
     * 结算页、牌桌也没有这么大一块米色。只认 2412×1080 的 Mika 屏。
     */
    fun singleMoreCoinsPopup(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        var creamy = 0
        var pixels = 0
        for (y in MORE_COINS_PANEL_Y0 until MORE_COINS_PANEL_Y1 step 3) for (x in MORE_COINS_PANEL_X0 until MORE_COINS_PANEL_X1 step 3) {
            if (cream(frame.pixel(x, y))) creamy++
            pixels++
        }
        if (creamy.toDouble() / pixels < MORE_COINS_PANEL_DENSITY) return null
        // 「确定」上下两行都是一整条金黄(中间被白字切开),正中够宽的那一条才是它
        var cx = 0
        for (y in listOf(MORE_COINS_OK_TOP_Y, MORE_COINS_OK_BOTTOM_Y)) {
            val run = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
                frame.pixel(x, y).let { p -> (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) in 121..230 && (p and 0xff) < 110 }
            }.singleOrNull() ?: return null
            if (run.first !in MORE_COINS_OK_LEFT || run.last !in MORE_COINS_OK_RIGHT) return null
            cx = (run.first + run.last) / 2
        }
        return cx to MORE_COINS_OK_Y
    }

    private const val MORE_COINS_PANEL_X0 = 800
    private const val MORE_COINS_PANEL_X1 = 1600
    private const val MORE_COINS_PANEL_Y0 = 340
    private const val MORE_COINS_PANEL_Y1 = 410
    private const val MORE_COINS_PANEL_DENSITY = 0.9
    private const val MORE_COINS_OK_TOP_Y = 730
    private const val MORE_COINS_OK_BOTTOM_Y = 805
    private val MORE_COINS_OK_LEFT = 1000..1100
    private val MORE_COINS_OK_RIGHT = 1300..1420
    private const val MORE_COINS_OK_Y = 765

    /**
     * 段位弹窗(真人结算页、真人场次页上):正中一个大段位徽章(绿梅花加银翅膀)、顶上一行彩色标题、
     * 下面一个橙色「确定」。只有一个按钮时「确定」在正中;有绿色「炫耀一下」时它在右边。
     * 返回橙色「确定」的中点,绝不返回左边的绿色「炫耀一下」;认不准为 null。只认 2412×1080 的 Mika 屏。
     */
    fun rankPopup(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        // 只截了部分行的帧(测试里的手牌帧)上没这些行:当作认不出,别去读到不存在的行
        if ((RANK_ROWS + RANK_CONFIRM_ROW).any { frame.row(it) == null }) return null
        // 正中徽章的绿梅花:场次页的绿色场次卡也有大片绿色,所以还要橙色「确定」和上面那行标题一起对上
        var green = 0
        var badge = 0
        for (y in RANK_BADGE_ROWS) for (x in RANK_BADGE_X0 until RANK_BADGE_X1 step 8) {
            badge++
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            if (g > 110 && g - r > 45 && b >= 60 && b < 205) green++
        }
        if (green.toDouble() / badge < RANK_BADGE_GREEN) return null
        // 顶上一行彩色大标题(降段是蓝的、升段是橙红的、段位保护是蓝的)
        var bright = 0
        for (y in RANK_TITLE_ROWS) for (x in RANK_TITLE_X0 until RANK_TITLE_X1 step 8) {
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            if (r + g + b > 430 && maxOf(r, g, b) - minOf(r, g, b) > 60) bright++
        }
        if (bright < RANK_TITLE_MIN) return null
        // 橙色「确定」:整条够宽的那一段;绿色「炫耀一下」不是橙色,永远不会落进来
        val confirm = DdzScreen.runs(frame.width, gap = 30, minWidth = 300) { x ->
            frame.pixel(x, RANK_CONFIRM_ROW).let { p ->
                (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) in 120..230 && (p and 0xff) < 120
            }
        }.singleOrNull() ?: return null
        return (confirm.first + confirm.last) / 2 to RANK_CONFIRM_Y
    }

    /** 段位弹窗要读的行:标题那一行和中间徽章一片,稀疏取够认色(每帧多截的行要少,等人时一轮帧很多)。 */
    val RANK_ROWS: List<Int> = ((RANK_TITLE_Y0 until RANK_TITLE_Y1 step RANK_TITLE_STEP) + (RANK_BADGE_Y0 until RANK_BADGE_Y1 step RANK_BADGE_STEP)).toList()

    private const val RANK_TITLE_STEP = 6
    private const val RANK_BADGE_STEP = 20
    private const val RANK_TITLE_Y0 = 130
    private const val RANK_TITLE_Y1 = 180
    private const val RANK_TITLE_X0 = 700
    private const val RANK_TITLE_X1 = 1700
    private const val RANK_TITLE_MIN = 40
    private const val RANK_BADGE_Y0 = 348
    private const val RANK_BADGE_Y1 = 558
    private const val RANK_BADGE_X0 = 1060
    private const val RANK_BADGE_X1 = 1390
    private const val RANK_BADGE_GREEN = 0.09
    private const val RANK_CONFIRM_ROW = 810
    private const val RANK_CONFIRM_Y = 852
    private val RANK_TITLE_ROWS = (RANK_TITLE_Y0 until RANK_TITLE_Y1 step RANK_TITLE_STEP).toList()
    private val RANK_BADGE_ROWS = (RANK_BADGE_Y0 until RANK_BADGE_Y1 step RANK_BADGE_STEP).toList()

    private const val PRIVILEGE_BADGE_Y0 = 340
    private const val PRIVILEGE_BADGE_Y1 = 460
    private const val PRIVILEGE_BADGE_STEP = 20
    private const val PRIVILEGE_BADGE_X0 = 1100
    private const val PRIVILEGE_BADGE_X1 = 1240
    private const val PRIVILEGE_BADGE_RED = 0.25
    private const val PRIVILEGE_BANNER_Y0 = 472
    private const val PRIVILEGE_BANNER_Y1 = 536
    private const val PRIVILEGE_BANNER_STEP = 8
    private const val PRIVILEGE_BANNER_X0 = 1000
    private const val PRIVILEGE_BANNER_X1 = 1390
    private const val PRIVILEGE_BANNER_RED = 0.5
    private const val PRIVILEGE_BUTTON_ROW = 870
    private const val PRIVILEGE_TAP_Y = 840
    private val PRIVILEGE_BADGE_ROWS = (PRIVILEGE_BADGE_Y0 until PRIVILEGE_BADGE_Y1 step PRIVILEGE_BADGE_STEP).toList()
    private val PRIVILEGE_BANNER_ROWS = (PRIVILEGE_BANNER_Y0 until PRIVILEGE_BANNER_Y1 step PRIVILEGE_BANNER_STEP).toList()

    /**
     * 「至尊特权升级」弹窗要读的行:红色六边形等级徽章、红色「新增特权」横幅,和底部按钮那一行。
     * 认这个弹窗要截的行比段位弹窗少一点,每帧多读的行也少一些。
     */
    val PRIVILEGE_ROWS: List<Int> = (PRIVILEGE_BADGE_ROWS + PRIVILEGE_BANNER_ROWS + PRIVILEGE_BUTTON_ROW).distinct()

    /**
     * 「至尊特权升级」弹窗(真人结算页上):正中一个红色六边形等级徽章、下面一条红色「新增特权」横幅、
     * 底部左边蓝色「我知道了」、右边橙色「查看更多特权」。返回蓝色「我知道了」的中点,
     * 绝不返回橙色「查看更多特权」(会进特权/充值页);认不准为 null。只认 2412×1080 的 Mika 屏。
     */
    fun privilegePopup(frame: RawFrame): Pair<Int, Int>? {
        if (frame.width != 2412 || frame.height != 1080) return null
        // 只截了部分行的帧上没这些行:当作认不出,别去读到不存在的行
        if (PRIVILEGE_ROWS.any { frame.row(it) == null }) return null
        fun red(p: Int): Boolean {
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            return r > 150 && g < 105 && b < 120 && r - g > 80
        }
        // 正中红色六边形等级徽章:别的画面正中没这么一片饱和的红
        var redBadge = 0
        var badge = 0
        for (y in PRIVILEGE_BADGE_ROWS) for (x in PRIVILEGE_BADGE_X0 until PRIVILEGE_BADGE_X1 step 8) {
            badge++
            if (red(frame.pixel(x, y))) redBadge++
        }
        if (redBadge.toDouble() / badge < PRIVILEGE_BADGE_RED) return null
        // 「新增特权」红色横幅:和上面的徽章一起对上,排除别处的红
        var redBanner = 0
        var banner = 0
        for (y in PRIVILEGE_BANNER_ROWS) for (x in PRIVILEGE_BANNER_X0 until PRIVILEGE_BANNER_X1 step 8) {
            banner++
            if (red(frame.pixel(x, y))) redBanner++
        }
        if (redBanner.toDouble() / banner < PRIVILEGE_BANNER_RED) return null
        // 底部左边蓝色「我知道了」,右边橙色「查看更多特权」:橙色只用来确认摆法,绝不返回它的坐标
        val blue = DdzScreen.runs(frame.width, gap = 30, minWidth = 250) { x ->
            val p = frame.pixel(x, PRIVILEGE_BUTTON_ROW)
            (p shr 16 and 0xff) < 130 && (p shr 8 and 0xff) > 120 && (p and 0xff) > 170
        }.singleOrNull() ?: return null
        if (blue.first !in 730..790 || blue.last !in 1040..1100) return null
        val orange = DdzScreen.runs(frame.width, gap = 30, minWidth = 250) { x ->
            val p = frame.pixel(x, PRIVILEGE_BUTTON_ROW)
            (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) in 130..235 && (p and 0xff) < 110
        }.singleOrNull() ?: return null
        if (orange.first !in 1320..1390 || orange.last !in 1630..1700) return null
        if (orange.first <= blue.last) return null
        return (blue.first + blue.last) / 2 to PRIVILEGE_TAP_Y
    }

    private const val BEANS_PANEL_X0 = 740
    private const val BEANS_PANEL_X1 = 1680
    private const val BEANS_PANEL_Y0 = 345
    private const val BEANS_PANEL_Y1 = 420
    private const val BEANS_ABOVE_Y0 = 200
    private const val BEANS_ABOVE_Y1 = 325
    private const val BEANS_CLOSE_X = 1676
    private const val BEANS_CLOSE_Y = 284
    private const val RELIEF_PANEL_X0 = 720
    private const val RELIEF_PANEL_X1 = 1720
    private const val RELIEF_PANEL_Y0 = 200
    private const val RELIEF_PANEL_Y1 = 400
    private const val RELIEF_PANEL_DENSITY = 0.6
    private const val RELIEF_CLAIM_TOP_Y = 700
    private const val RELIEF_CLAIM_BOTTOM_Y = 770
    private val RELIEF_CLAIM_LEFT = 940..1100
    private val RELIEF_CLAIM_RIGHT = 1300..1420
    private const val RELIEF_CLAIM_TAP_Y = 730
    private const val RELIEF_BANNER_X0 = 300
    private const val RELIEF_BANNER_X1 = 2100
    private const val RELIEF_BANNER_Y0 = 300
    private const val RELIEF_BANNER_Y1 = 400
    private const val RELIEF_BANNER_DENSITY = 0.75
    private const val RELIEF_DOUBLE_Y = 900
    private val RELIEF_DOUBLE_LEFT = 940..1100
    private val RELIEF_DOUBLE_RIGHT = 1340..1500
    private const val RELIEF_LINK_X0 = 1450
    private const val RELIEF_LINK_X1 = 1850
    private const val RELIEF_LINK_UNDERLINE_Y = 895
    private const val RELIEF_LINK_TAP_Y = 870

    private const val CLASSIC_MARK_X = 1050
    private const val CLASSIC_MARK_Y = 350
    private const val CLASSIC_MARK_W = 350
    private const val CLASSIC_MARK_H = 70
    private const val CLASSIC_MARK_DENSITY = 0.14
    private const val POPUP_SCAN_X = 700
    private const val POPUP_SCAN_Y = 150
    private const val POPUP_SCAN_W = 1050
    private const val POPUP_SCAN_H = 250
    private const val POPUP_DARK_DENSITY = 0.15
    private const val POPUP_BRIGHT_DENSITY = 0.03

    /** 这块地方的字和记下的样子差不多:同一位置、同一大小,差的格子不超过 [slack]。 */
    private inline fun matches(
        frame: RawFrame, x: Int, y: Int, w: Int, h: Int, mark: BooleanArray, slack: Double, bar: Boolean = true, ink: (Int) -> Boolean,
    ): Boolean {
        if (frame.height != 1080 || frame.width != 2412 || (bar && DdzScreen.blackBar(frame) != 0)) return false
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

    /**
     * 左右两家手里还剩几张:头像边上牌背小框里的金色数字。框被挡住、字形不像就是 null。
     * 闯关牌桌的框在帽子下面、比农民的低一截;经典牌桌的框贴在头像下边、更靠边,数字字形一样。
     * 两种都先按牌背的浅蓝找框。
     */
    fun counts(frame: RawFrame): Map<DdzSide, Int?> {
        val stage = singleStageTable(frame)
        val left = if (stage) COUNT_LEFT else CLASSIC_COUNT_LEFT
        val right = if (stage) COUNT_RIGHT else CLASSIC_COUNT_RIGHT
        val band = if (stage) COUNT_BAND else CLASSIC_COUNT_BAND
        val y0 = if (stage) COUNT_Y0 else CLASSIC_COUNT_Y0
        val y1 = if (stage) COUNT_Y1 else CLASSIC_COUNT_Y1
        return mapOf(DdzSide.Left to count(frame, left, band, y0, y1), DdzSide.Right to count(frame, right, band, y0, y1))
    }

    private fun count(frame: RawFrame, x0: Int, band: Int = COUNT_BAND, y0: Int = COUNT_Y0, y1: Int = COUNT_Y1): Int? {
        val x1 = x0 + band
        fun face(p: Int): Boolean {
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            return r in 75..145 && g in 105..180 && b in 160..225 && b - r in 55..115
        }
        fun gold(p: Int) = (p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) > 110 && (p and 0xff) < 130
        // 数字所在那几行牌背只剩两边一点,门槛放低;两位数宽,中间会断开十几行,断开不超过 20 行算同一个框
        val rows = (y0 until y1).filter { y -> (x0 until x1).count { face(frame.pixel(it, y)) } >= 10 }
        var best: IntRange? = null
        var start = -1
        var last = -1
        for (y in rows + Int.MAX_VALUE) {
            if (start >= 0 && y - last > 20) {
                if (last - start >= 60 && (best == null || last - start > best.last - best.first)) best = start..last
                start = -1
            }
            if (start < 0) start = y
            last = y
        }
        val box = best ?: return null
        val cols = DdzScreen.runs(x1 - x0, gap = 2, minWidth = 4) { c -> box.any { y -> gold(frame.pixel(x0 + c, y)) } }
        if (cols.size !in 1..2) return null
        var n = 0
        for (col in cols) {
            val inked = box.filter { y -> col.any { c -> gold(frame.pixel(x0 + c, y)) } }
            val top = inked.first()
            val bottom = inked.last()
            if (bottom - top !in 25..45) return null
            val ink = Array(bottom - top + 1) { r -> BooleanArray(col.last - col.first + 1) { c -> gold(frame.pixel(x0 + col.first + c, top + r)) } }
            val digit = nearest(countGlyphs, boxSig(ink, COUNT_W, COUNT_H), COUNT_SURE, COUNT_MARGIN)?.toIntOrNull() ?: return null
            n = n * 10 + digit
        }
        return n.takeIf { it in 0..20 }
    }

    private const val COUNT_LEFT = 360
    private const val COUNT_RIGHT = 2013
    private const val COUNT_BAND = 80
    private const val COUNT_Y0 = 130
    private const val COUNT_Y1 = 330
    /** 经典牌桌的框(实测):左框 230-279、右框 2134-2183,数字在 364-395 行。 */
    private const val CLASSIC_COUNT_LEFT = 226
    private const val CLASSIC_COUNT_RIGHT = 2130
    private const val CLASSIC_COUNT_BAND = 62
    private const val CLASSIC_COUNT_Y0 = 330
    private const val CLASSIC_COUNT_Y1 = 420
    private const val COUNT_SURE = 0.15
    private const val COUNT_MARGIN = 0.04

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
            val white = { r: Int, g: Int, b: Int -> minOf(r, g, b) > 235 && maxOf(r, g, b) - minOf(r, g, b) < 25 }
            // 「不出」只是 160-215 那一条字;正在飞过来、还没认出来的牌面一直白到 340,不能当成不出
            return count(frame, x0, x1, 150, 230, white) >= 300 && count(frame, x0, x1, 260, 340, white) < 50
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
        wideLast: Int? = null, wrapDy: Int? = null, tailDy: Int? = null, wrapBelow: Int = WRAP_BELOW,
        wrapFull: Boolean = false, wrapWhite: Int = 100, probeTop: Int? = null,
        gap: Int = 12, spanAll: Boolean = false,
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
        // 闯关的牌上会压着「顺子」「炸弹」这类特效,只量一行会量短:牌顶下面一点(点数还没开始)和下沿附近都量,取最宽的
        // 真人桌地主出的王下半张是地主的图案,只在下面量会量短(算成 0 张):牌顶下面一点也量,取最宽的
        // 真人牌点字在顶下十几行会把白牌切开,多探一行找完整牌面
        // 真人地主的王炸:上面几行被绶带切开、中间是两张王的图案,只有牌的下沿那几行是整片白(两张宽),也探一行。
        // 按牌高从牌顶往下数,不用 bottom:王炸的火光在出牌区左边连成一片白,bottom 会落到区域底边
        val probes = if (probeNearBottom) listOf(top + 5, bottom - 10, bottom - 30)
        else listOfNotNull(top + 10, top + 20, minOf(top + minRows, y1 - 1), probeTop?.let { top + it }, minOf(top + CARD_FOOT, y1 - 1))
        val span = probes.mapNotNull { probe ->
            val found = DdzScreen.runs(right - x0, gap = gap, minWidth = 40) { white(x0 + it, probe) }
            // 底牌放大摆开时三张牌中间隔着桌布:按最左到最右的整排宽度数张数,不能只取最宽的一张
            if (spanAll && found.size > 1) found.first().first..found.last().last else found.maxByOrNull { it.last - it.first }
        }.maxByOrNull { it.last - it.first } ?: return null
        val n = ((span.last - span.first + 1 - width) / pitch).roundToInt() + 1
        if (n !in 1..20) return null
        val first = row(frame, x0 + span.first, top, n, pitch, glyphRows, glyphWidth, k, sure, margin, wideLast)
        // 真人右家最后一张带绶带时会下移,横向宽度仍和上一张重叠,不能靠牌面总宽计数
        if (tailDy != null) {
            val tailLeft = x0 + span.first + ((n - 1) * pitch).toInt()
            val tailTop = top + tailDy
            val ribbon = (0 until 6).sumOf { r -> (52 until 62).count { c ->
                frame.pixel(tailLeft + 3 + c, tailTop + 4 + r).let { p ->
                    (p shr 16 and 0xff) > 170 && (p shr 8 and 0xff) < 110 && (p and 0xff) < 110
                }
            } } >= 45
            if (ribbon) {
                val tail = row(frame, tailLeft, tailTop, 1, pitch, glyphRows, glyphWidth, k, sure, margin, wideLast ?: 72)
                return first + tail
            }
        }
        // 闯关里一手出得多(十张的飞机带对子),一排摆满九张后接着从同一个左边摆第二排,比第一排低 [WRAP_DY]、压在它下面。
        // 第一排牌面下沿以下还有白色牌面就是有第二排;宽度只在第一排下面量
        if (wrapDy == null) return first
        val below = top + wrapBelow
        if (below >= y1 || (x0 until right).count { white(it, below) } < wrapWhite) return first
        val span2 = DdzScreen.runs(right - x0, gap = 12, minWidth = 40) { white(x0 + it, below) }.maxByOrNull { it.last - it.first } ?: return first
        val n2 = ((span2.last - span2.first + 1 - width) / pitch).roundToInt() + 1
        if (n2 !in 1..20) return first
        // 经典牌桌的牌更矮(高 148):第一排九张摆满,第二排的牌顶比第一排低 [wrapDy],正好探在第二排的牌面上。
        // 第二排接着第一排的哪一头摆,看这一家的牌往哪边排:左家的从同一个左边起(单机经典牌桌的十张顺子),
        // 右家的贴着右边(2074),第二排接着从最后一张下面摆(真人右家的十张飞机带对子)。
        // 九张没摆满不会有第二排,也别把真人右家掉下去那张绶带牌(在右边)当成第二排
        if (wrapFull && (n != 9 || !wrapsUnder(span, span2))) return first
        return first + row(frame, x0 + span2.first, top + wrapDy, n2, pitch, glyphRows, glyphWidth, k, sure, margin, wideLast)
    }

    /** 一排 [n] 张牌,最左一张从 [left] 起、牌顶在 [top]:每张的点数。 */
    private fun row(
        frame: RawFrame, left: Int, top: Int, n: Int, pitch: Double, glyphRows: Int, glyphWidth: Int, k: Double,
        sure: Double, margin: Double, wideLast: Int?,
    ): List<Read> {
        return (0 until n).map { i ->
            val cx = left + (i * pitch).toInt() + 3
            // 闯关地主的最后一张:绶带的黑边从点数右上斜着往下(第 r 行在 43+0.85r 列),会和点数连成一块;
            // 看得到绶带的红就把那条边和它右边都擦掉,那里本来就被绶带盖着
            val ribbon = wideLast != null && i == n - 1 && (0 until 6).sumOf { r -> (52 until 62).count { c ->
                frame.pixel(cx + c, top + 4 + r).let { p -> (p shr 16 and 0xff) > 170 && (p shr 8 and 0xff) < 110 && (p and 0xff) < 110 }
            } } >= 45
            // 只有最后一张右上角可能压着绶带;前面的牌点数(10 有两位)可以看全,但别碰到下一张牌左边的阴影。
            // Mika 的屏间距 51.5:黑 10 的 0 伸到第 47 列,窗口只留 46 会切掉右边,认不准;留到 pitch - 3
            // 最后一张整张露着:窄了 10 的 0 会切掉,宽了地主的绶带会进来;给了 [wideLast] 就两种都量,取更像的
            // 最后一张右边没有下一张牌,宽窗封顶到 pitch 就够、不用减那 3 的余量(窄窗仍减 3 避绶带):
            // 这手末尾的黑 10 偏右、0 伸到第 49 列,47 的宽窗量不全,整手读成 ?K,7,J;量到 50 才认出 10
            val widths = if (i == n - 1) listOfNotNull(
                (minOf(glyphWidth.toDouble(), pitch) - 3).toInt(),
                wideLast?.let { minOf(it.toDouble(), pitch).toInt() },
            ).distinct()
            else listOf(minOf(pitch - 3, 48 * k).toInt())
            // 真人牌桌的绶带更靠左:黑边在亮红左边 5 列(第 r 行约 39+r),落在上面那条斜线以内,和点数连成一块
            // (AKQJ10 的 10 读成 ?K,A,7,王炸的小王读成 ?K,7,A)。每行再从亮红往左 [RIBBON_EDGE] 列擦掉;
            // 点数的红最亮约 195,绶带 240,按 [RIBBON_RED] 分得开
            val edge = if (!ribbon) null else IntArray(glyphRows) { r ->
                (0 until RIBBON_SCAN).firstOrNull { c -> frame.pixel(cx + c, top + 4 + r).let { p ->
                    (p shr 16 and 0xff) > RIBBON_RED && (p shr 8 and 0xff) < 110 && (p and 0xff) < 110
                } }?.minus(RIBBON_EDGE) ?: Int.MAX_VALUE
            }
            widths.mapNotNull { wide ->
                val ink = Array(glyphRows) { r -> BooleanArray(wide) { c ->
                    (edge == null || (c < RIBBON_COL + RIBBON_SLOPE * r && c < edge[r])) && textInk(frame.pixel(cx + c, top + 4 + r))
                } }
                val box = rankBox(ink) ?: return@mapNotNull null
                val red = share(frame, cx, top + 4, ink) { r, g, _ -> r - g > 50 } > 0.5
                ranked(tableGlyphs, boxSig(box, TABLE_W, TABLE_H)).map { joker(it.first, red) to it.second }
            }.minByOrNull { it.first().second }
                ?.let { ranked -> Read(sure(ranked, sure, margin), ranked.take(GUESSES).map { it.first }) }
                ?: Read(null, emptyList())
        }
    }

    private const val WRAP_DY = 90
    private const val HUMAN_RIBBON_DY = 70
    /** 第一排牌顶往下这么多已经出了第一排的牌面(闯关的牌高约 188),还是白的就是第二排。 */
    private const val WRAP_BELOW = 215
    /**
     * 经典牌桌的牌矮得多(高约 148):第一排九张摆满后,第十张从同一个左边摆第二排,牌顶比第一排低 68;
     * 第一排的牌面只盖到 392,往下 170 那一行已经是第二排整张的牌面。
     */
    private const val CLASSIC_PROBE_TOP = 5
    private const val CLASSIC_WRAP_DY = 68
    private const val CLASSIC_WRAP_BELOW = 170
    /** 第二排那行至少要有半张牌的白:单张牌下面桌布上的白点(张数框、闹钟)不够。 */
    private const val CLASSIC_WRAP_WHITE = 60
    private const val RIBBON_COL = 40
    private const val RIBBON_SLOPE = 0.85
    private const val RIBBON_SCAN = 70
    /** 经典 / 真人牌桌的牌高约 148:牌顶往下这么多是牌的下沿,王的图案到不了。 */
    private const val CARD_FOOT = 140
    private const val RIBBON_EDGE = 6
    private const val RIBBON_RED = 215

    /**
     * 第二排接着第一排摆:贴着同一个左边(左家的牌从左边起摆),或者贴着同一个右边(右家的牌贴着右边摆,
     * 第二排从最后一张下面接着摆)。两头都留一点余量:第二排的边和第一排差几像素。
     */
    private fun wrapsUnder(first: IntRange, second: IntRange): Boolean =
        second.first in first.first - 6..first.first + 12 || second.last in first.last - 12..first.last + 6

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

    /** 只留点数字:整体墨迹的外框。小牌的字中间可能有一两行空白,不能像 [rankBox] 那样在第一处空白就截断。 */
    private fun inkBox(ink: Array<BooleanArray>): Array<BooleanArray>? {
        val rows = ink.indices.filter { r -> ink[r].any { it } }
        if (rows.isEmpty()) return null
        val top = rows.first()
        val bottom = rows.last()
        val cols = ink[top].indices.filter { c -> (top..bottom).any { r -> ink[r][c] } }
        if (cols.isEmpty()) return null
        return Array(bottom - top + 1) { r -> ink[top + r].copyOfRange(cols.first(), cols.last() + 1) }
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
