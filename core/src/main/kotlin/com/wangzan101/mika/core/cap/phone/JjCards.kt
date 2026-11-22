package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.cap.phone.DdzSnapshotRecovery.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs

/** 电脑 /cards 认出的一样东西:一张牌的点数,或 PASS(「不出」的气泡)。坐标是截图像素。 */
internal data class JjCard(
    val rank: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val score: Double,
    val alternateRank: String? = null,
) {
    val cx: Double get() = (x0 + x1) / 2
    val cy: Double get() = (y0 + y1) / 2
}

/** 一帧牌桌按位置分好:手牌从左到右,[lifted] 是抬起的那几张的序号。 */
internal data class JjTable(
    val hand: List<JjCard>,
    val lifted: Set<Int>,
    val left: DdzSeen,
    val right: DdzSeen,
    val mine: DdzSeen,
    /** 底牌槽从左到右，每槽保留当帧的候选框，供跨帧投票。 */
    val bottomSlots: List<List<JjCard>>,
    /** 左右两家出牌区认到的原始框(含 PASS),给「连着几帧一样就算认准」用。 */
    val leftCards: List<JjCard> = emptyList(),
    val rightCards: List<JjCard> = emptyList(),
) {
    val handRanks: List<String> get() = hand.map { it.rank }
    /** 三个槽都只有一个高分框时，可以直接取本帧的票。 */
    val bottom: List<String>?
        get() = bottomSlots.map { it.singleOrNull()?.takeIf { card -> card.score >= JjCards.SURE }?.rank ?: return null }
    /** 有一张手牌认不准:这一帧的手牌不能拿去算。 */
    val handUnsure: Boolean get() = hand.any { it.score < JjCards.SURE }
    fun seen(side: DdzSide): DdzSeen = if (side == DdzSide.Left) left else right
    fun opponentBoxes(side: DdzSide): List<JjCard> =
        (if (side == DdzSide.Left) leftCards else rightCards).sortedBy { it.cx }
}

/**
 * JJ 斗地主的牌交给电脑认(tools/douzero 的 POST /cards,模型在 tools/cardvision),这里只按位置分。
 * 位置按 2408×1080 的录屏量(框中心):底牌 y<60;两家出的牌和「不出」y 160–450,按挨着的牌分组再看在哪边;
 * 我出的牌、我的「不出」y 450–700;手牌 y≥700(点数中心平放约 805、抬起约 768,王的竖排字中心约 840)。
 */
internal object JjCards {
    /** 同点数优先保留分高的框,保持牌桌原顺序和张数。 */
    fun maskExcess(boxes: List<JjCard>, copiesLeft: Map<String, Int>): List<String> {
        val left = copiesLeft.toMutableMap()
        val ranks = boxes.map { it.rank }.toMutableList()
        for (i in boxes.indices.sortedByDescending { boxes[it].score }) {
            val rank = boxes[i].rank
            val copies = left.getOrDefault(rank, 0)
            if (copies == 0) ranks[i] = DdzCards.UNKNOWN else left[rank] = copies - 1
        }
        return ranks
    }

    /** 优先用同牌型最小的压牌。压不住时用最小可赢炸弹，再考虑王炸。 */
    fun smallestOvercall(table: JjTable, play: List<String>): List<String>? {
        if (table.handUnsure) return null
        val lead = DdzSnapshotRecovery.classify(play) ?: return null
        if (lead.kind == Kind.Pass || lead.kind == Kind.Rocket) return null
        val counts = table.handRanks.groupingBy { it }.eachCount()
        val ranks = DdzCards.RANKS.filter { it in counts }
        val remaining = IntArray(ranks.size + 1)
        for (i in ranks.indices.reversed()) remaining[i] = remaining[i + 1] + counts.getValue(ranks[i])
        val cards = mutableListOf<String>()
        var best: List<String>? = null
        var bestMain = Int.MAX_VALUE
        fun search(index: Int, need: Int) {
            if (bestMain == lead.main + 1 || remaining[index] < need) return
            if (need == 0) {
                val move = DdzSnapshotRecovery.classify(cards) ?: return
                if (move.kind == lead.kind && DdzSnapshotRecovery.beats(lead, move) && move.main < bestMain) {
                    best = cards.toList()
                    bestMain = move.main
                }
                return
            }
            val rank = ranks[index]
            val count = counts.getValue(rank)
            for (take in minOf(count, need) downTo 0) {
                if (count == 4 && take in 1..3) continue
                repeat(take) { cards.add(rank) }
                search(index + 1, need - take)
                repeat(take) { cards.removeAt(cards.lastIndex) }
            }
        }
        if (lead.kind != Kind.Bomb) search(0, lead.length)
        best?.let { return it }
        for (rank in ranks) {
            if (counts.getValue(rank) != 4) continue
            val bomb = DdzSnapshotRecovery.Move(Kind.Bomb, 4, DdzCards.ORDER.getValue(rank))
            if (DdzSnapshotRecovery.beats(lead, bomb)) return List(4) { rank }
        }
        return if ("SJ" in counts && "BJ" in counts) listOf("SJ", "BJ") else null
    }
    private const val BASE_WIDTH = 2408.0
    /** 录屏里左家出的牌最左在 x≈600–700,右家的最右到 x≈1700–1800,翻底牌的动画只在 1000–1400 之间。 */
    private const val LEFT_EDGE = 1000.0
    private const val RIGHT_EDGE = 1400.0
    /** 同一手牌相邻两张的点数框间距约 55,两家之间隔着 400 以上。 */
    private const val GROUP_GAP = 150.0
    /** 低于这个分数的牌算认不准。 */
    const val SURE = 0.8
    private const val BOTTOM_Y = 60.0
    private val BOTTOM_SLOT_X = listOf(870.0, 940.0, 1010.0)
    private const val BOTTOM_SLOT_HALF_WIDTH = 25.0
    private const val PLAY_Y0 = 160.0
    private const val MINE_Y0 = 450.0
    private const val HAND_Y0 = 700.0
    /** 牌角上沿:平放约 770,抬起约 735。 */
    private const val LIFTED_TOP = 752.0
    private const val HAND_MIN_H = 70.0
    private const val HAND_MIN_TOP = 720.0
    /** 点手牌点在牌身上(点数下方),抬没抬都点得到。 */
    const val HAND_TAP_Y = 950
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val PASS = "PASS"
    /** 两个同点数的框重叠超过小框的这么多,算同一张牌。 */
    private const val SAME_CARD = 0.5
    private const val RANK_OVERLAP = 0.75
    /** 18:11:33–41 同一张 8/3 的重叠占大框的 0.827–0.890，分数接近时也必须并框。 */
    private const val NEARLY_IDENTICAL_RANK_OVERLAP = 0.82
    private const val RANK_WINNER_SCORE = 0.95
    private const val RANK_SCORE_GAP = 0.08
    /** 赢的框过了 SURE、弱框低一大截也算同一张(16:08:50 左家 K K K 8 8:8 是 0.90,同一处还有个 0.53 的 3)。 */
    private const val RANK_WIDE_GAP = 0.2

    /** 把一帧 JPEG 交给电脑认牌;连不上、没配认牌模型、回的不对都是 null。 */
    suspend fun detect(url: String, jpeg: ByteArray): List<JjCard>? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI("${url.trimEnd('/')}/cards").toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.outputStream.use { it.write(jpeg) }
                if (conn.responseCode != 200) {
                    println("JJTRACE cards_http ${conn.responseCode}")
                    return@runCatching null
                }
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.onFailure { println("JJTRACE cards_error ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    fun parse(body: String): List<JjCard>? = runCatching {
        Json.parseToJsonElement(body).jsonObject["cards"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val box = o["box"]!!.jsonArray.map { it.jsonPrimitive.double }
            JjCard(o["rank"]!!.jsonPrimitive.content, box[0], box[1], box[2], box[3], o["score"]!!.jsonPrimitive.double)
        }
    }.getOrNull()

    fun table(detected: List<JjCard>, width: Int): JjTable {
        val cards = dedupe(detected)
        fun seen(found: List<JjCard>): DdzSeen {
            val ranks = found.filter { it.rank != PASS }
            val pass = found.filter { it.rank == PASS }
            return when {
                found.isEmpty() -> DdzSeen.Empty
                // 有牌又有不出,或有一张认不准:牌在飞、气泡在消,先别算
                ranks.isNotEmpty() -> if (pass.isEmpty() && ranks.all { it.score >= SURE }) DdzSeen.Cards(ranks.sortedBy { it.cx }.map { it.rank }) else DdzSeen.Unsure
                pass.any { it.score >= SURE } -> DdzSeen.Pass
                else -> DdzSeen.Unsure
            }
        }
        // 出牌区按挨着的牌分组:左家的牌从左边排起、伸过 [LEFT_EDGE],右家的排到 [RIGHT_EDGE] 外;
        // 整组都在中间的是叫完分翻底牌的动画(三张牌从中间飞到顶栏),不是谁出的牌
        val scale = width / BASE_WIDTH
        val groups = groups(cards.filter { it.cy >= PLAY_Y0 && it.cy < MINE_Y0 }, GROUP_GAP * scale)
        val left = groups.filter { g -> g.minOf { it.x0 } < LEFT_EDGE * scale }.flatMap(::dedupeRankLabels)
        val right = groups.filter { g -> g.minOf { it.x0 } >= LEFT_EDGE * scale && g.maxOf { it.x1 } > RIGHT_EDGE * scale }.flatMap(::dedupeRankLabels)
        // 手牌的点数字高约 78(王的竖排字约 178),上沿平放 770、抬起 735;
        // 每手结算浮层里摆的各家剩牌也在这一带,字矮(约 50–68)、上沿高(670 起)
        val hand = cards.filter { it.cy >= HAND_Y0 && it.rank != PASS && it.y0 >= HAND_MIN_TOP && it.y1 - it.y0 >= HAND_MIN_H }.sortedBy { it.cx }
        val top = cards.filter { it.cy < BOTTOM_Y && it.rank in DdzCards.RANKS }
        val bottomSlots = BOTTOM_SLOT_X.map { center ->
            top.filter { abs(it.cx / scale - center) <= BOTTOM_SLOT_HALF_WIDTH }
        }
        return JjTable(
            hand = hand,
            lifted = hand.indices.filterTo(LinkedHashSet()) { hand[it].y0 < LIFTED_TOP },
            left = seen(left),
            right = seen(right),
            mine = seen(cards.filter { it.cy >= MINE_Y0 && it.cy < HAND_Y0 }),
            bottomSlots = bottomSlots,
            leftCards = left,
            rightCards = right,
        )
    }

    /**
     * 同一张牌认出两个框的只留分高的:顺子里的 10 字宽,会多出一个盖过半张邻牌的宽框(真机 10:05、11:11:
     * 右家 5–J 的顺子读成 8 张、两个 10,记牌断了,地主剩一张时没拿王炸压)。
     * 同点数、重叠超过小框一半才算同一张;真的两张 10 并排时框不重叠。
     */
    private fun dedupe(cards: List<JjCard>): List<JjCard> {
        val kept = mutableListOf<JjCard>()
        for (c in cards.sortedByDescending { it.score }) {
            if (kept.none { it.rank == c.rank && overlap(it, c) > SAME_CARD * minOf(area(it), area(c)) }) kept += c
        }
        return kept
    }

    /**
     * 同一张牌报出两个点数。重叠很高就并框，重叠稍低时要求分数拉开，把弱框的点数留作备选。
     * 用大框面积核对，避免宽框盖住旁边真正的一张。只处理对手出牌区，且不去掉 PASS。
     */
    private fun dedupeRankLabels(cards: List<JjCard>): List<JjCard> {
        val kept = mutableListOf<JjCard>()
        for (card in cards.sortedByDescending { it.score }) {
            val duplicate = kept.indexOfFirst { other ->
                if (card.rank == PASS || other.rank == PASS || other.rank == card.rank) return@indexOfFirst false
                val intersect = overlap(other, card)
                val larger = maxOf(area(other), area(card))
                val gap = other.score - card.score
                intersect > NEARLY_IDENTICAL_RANK_OVERLAP * larger ||
                    intersect > RANK_OVERLAP * larger &&
                    (other.score >= RANK_WINNER_SCORE && gap >= RANK_SCORE_GAP ||
                        other.score >= SURE && gap >= RANK_WIDE_GAP)
            }
            if (duplicate == -1) {
                kept += card
            } else if (kept[duplicate].alternateRank == null) {
                kept[duplicate] = kept[duplicate].copy(alternateRank = card.rank)
            }
        }
        return kept
    }

    private fun area(c: JjCard) = (c.x1 - c.x0) * (c.y1 - c.y0)

    private fun overlap(a: JjCard, b: JjCard): Double =
        maxOf(0.0, minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)) * maxOf(0.0, minOf(a.y1, b.y1) - maxOf(a.y0, b.y0))

    /** 按横向挨着(间隙不到 [gap])分组。 */
    private fun groups(cards: List<JjCard>, gap: Double): List<List<JjCard>> {
        val out = mutableListOf<MutableList<JjCard>>()
        for (c in cards.sortedBy { it.x0 }) {
            val last = out.lastOrNull()
            if (last != null && c.x0 - last.maxOf { it.x1 } < gap) last += c else out += mutableListOf(c)
        }
        return out
    }

    /** 点第 [index] 张手牌的位置(像素)。 */
    fun tapX(table: JjTable, index: Int): Int = table.hand[index].cx.toInt()
}
