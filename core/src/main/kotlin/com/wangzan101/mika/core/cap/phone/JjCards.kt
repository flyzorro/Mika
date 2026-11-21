package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/** 电脑 /cards 认出的一样东西:一张牌的点数,或 PASS(「不出」的气泡)。坐标是截图像素。 */
internal data class JjCard(val rank: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val score: Double) {
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
    /** 顶栏的三张底牌;不是正好三张(还没翻开、动画盖着)为 null。 */
    val bottom: List<String>?,
) {
    val handRanks: List<String> get() = hand.map { it.rank }
    /** 有一张手牌认不准:这一帧的手牌不能拿去算。 */
    val handUnsure: Boolean get() = hand.any { it.score < JjCards.SURE }
    fun seen(side: DdzSide): DdzSeen = if (side == DdzSide.Left) left else right
}

/**
 * JJ 斗地主的牌交给电脑认(tools/douzero 的 POST /cards,模型在 tools/cardvision),这里只按位置分。
 * 位置按 2408×1080 的录屏量(框中心):底牌 y<90;两家出的牌和「不出」y 160–450,按挨着的牌分组再看在哪边;
 * 我出的牌、我的「不出」y 450–700;手牌 y≥700(点数中心平放约 805、抬起约 768,王的竖排字中心约 840)。
 */
internal object JjCards {
    private const val BASE_WIDTH = 2408.0
    /** 录屏里左家出的牌最左在 x≈600–700,右家的最右到 x≈1700–1800,翻底牌的动画只在 1000–1400 之间。 */
    private const val LEFT_EDGE = 1000.0
    private const val RIGHT_EDGE = 1400.0
    /** 同一手牌相邻两张的点数框间距约 55,两家之间隔着 400 以上。 */
    private const val GROUP_GAP = 150.0
    /** 低于这个分数的牌算认不准。 */
    const val SURE = 0.8
    private const val BOTTOM_Y = 90.0
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
                if (conn.responseCode != 200) return@runCatching null
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun parse(body: String): List<JjCard>? = runCatching {
        Json.parseToJsonElement(body).jsonObject["cards"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val box = o["box"]!!.jsonArray.map { it.jsonPrimitive.double }
            JjCard(o["rank"]!!.jsonPrimitive.content, box[0], box[1], box[2], box[3], o["score"]!!.jsonPrimitive.double)
        }
    }.getOrNull()

    fun table(cards: List<JjCard>, width: Int): JjTable {
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
        val left = groups.filter { g -> g.minOf { it.x0 } < LEFT_EDGE * scale }.flatten()
        val right = groups.filter { g -> g.minOf { it.x0 } >= LEFT_EDGE * scale && g.maxOf { it.x1 } > RIGHT_EDGE * scale }.flatten()
        // 手牌的点数字高约 78(王的竖排字约 178),上沿平放 770、抬起 735;
        // 每手结算浮层里摆的各家剩牌也在这一带,字矮(约 50–68)、上沿高(670 起)
        val hand = cards.filter { it.cy >= HAND_Y0 && it.rank != PASS && it.y0 >= HAND_MIN_TOP && it.y1 - it.y0 >= HAND_MIN_H }.sortedBy { it.cx }
        val bottom = cards.filter { it.cy < BOTTOM_Y && it.rank != PASS }.sortedBy { it.cx }
        return JjTable(
            hand = hand,
            lifted = hand.indices.filterTo(LinkedHashSet()) { hand[it].y0 < LIFTED_TOP },
            left = seen(left),
            right = seen(right),
            mine = seen(cards.filter { it.cy >= MINE_Y0 && it.cy < HAND_Y0 }),
            bottom = bottom.takeIf { it.size == 3 && it.all { c -> c.score >= SURE } }?.map { it.rank },
        )
    }

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
