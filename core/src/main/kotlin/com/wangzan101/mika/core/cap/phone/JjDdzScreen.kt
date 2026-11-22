package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import kotlin.math.roundToInt

internal enum class JjColor { Yellow, Green, Gray, Red }

internal data class JjButton(val left: Int, val right: Int, val color: JjColor) {
    val x: Int get() = (left + right) / 2
}

/**
 * JJ 斗地主此刻的局面:[phase] 只有 Call、Lead、Follow、Other;[turn] 是闹钟在谁桌前(轮到我看按钮);
 * [redLord] 左右两家谁穿着地主的红衣服(都不是为 null;出牌时我是地主或人物被特效挡住都是 null)。
 */
internal data class JjView(val phase: DdzPhase, val buttons: List<JjButton>, val turn: DdzSeat?, val redLord: DdzSeat? = null) {
    /** 要不起:跟牌时只剩一个按钮。 */
    val onlyPass: Boolean get() = phase == DdzPhase.Follow && buttons.size == 1
}

/**
 * 从一帧像素认 JJ 斗地主(cn.jj)的界面。牌不在这里认(交给电脑的 /cards),这里只看按钮和闹钟。
 * 坐标按 2408×1080 的录屏量的,横向按画面宽度等比换算。认不出的一律 [DdzPhase.Other],调用方不点。
 *
 * 按钮一行(主屏量的,x 是中心):叫分 不叫(粉红)≈0.29、1≈0.51、2≈0.66、3≈0.81,可叫的黄、不可叫的灰;
 * 出牌 不出≈0.31、提示≈0.53、出牌≈0.69,跟牌时前两个黄、首出时前两个灰,出牌在选的牌能出时绿、不然灰;
 * 要不起只剩中间一个黄的。按钮上有白字,只看字上方 [TOP_ROW] 和下方 [BOTTOM_ROW] 两行,两行在同一处都是同一种颜色才算:
 * 沙滩牌桌上黄的沙子、人物在一行里也会连成一段。
 */
internal object JjDdzScreen {
    const val PACKAGE = "cn.jj"
    private const val FRAME_HEIGHT = 1080
    private const val BASE_WIDTH = 2408.0
    private const val TOP_ROW = 600
    private const val BOTTOM_ROW = 675
    /** 点按钮点在字的中间。 */
    const val TAP_Y = 640
    private const val MIN_BUTTON = 200
    /** 按钮约 300 宽;叫分的 1 旁边紧挨着闹钟,字上方那行会连成 380。沙滩上连成一片的黄沙有 800 多。 */
    private const val MAX_BUTTON = 420
    /** 按钮的白字、高光会把颜色切出小缺口。 */
    private const val GAP = 12
    /** 别家的闹钟(约 87×98):左家中心 x≈740、右家 x≈1660,y≈470。中间有白字和高光,有的行几乎没有金色:看四行。 */
    private val CLOCK_ROWS = listOf(460, 470, 480, 495)
    private const val LEFT_CLOCK_X = 740
    private const val RIGHT_CLOCK_X = 1660
    private const val CLOCK_HALF = 50
    /** 一行里闹钟的金色至少这么多像素,四行里至少两行够才算(实测有闹钟 10~34,另一边是 0)。 */
    private const val CLOCK_PIXELS = 10
    /** 要不起的那个按钮在中间。 */
    private const val PASS_X0 = 0.45
    private const val PASS_X1 = 0.60

    /**
     * 「再来一局」(绿):每手打完牌桌上弹的在正中(x≈0.50,y 920–1015),整轮的终局页在偏右(x≈0.60,y 880–1010)。
     * 看两种都在按钮上、又在白字上方 / 下方的两行。
     */
    private val AGAIN_ROWS = listOf(925, 1000)
    const val AGAIN_TAP_Y = 960
    private const val AGAIN_X0 = 0.45
    private const val AGAIN_X1 = 0.68
    private const val MIN_AGAIN = 200
    /** 按钮上沿的高光把绿色切出 15 像素左右的缺口。 */
    private const val AGAIN_GAP = 24
    private const val MAX_AGAIN = 460

    /**
     * 出牌时地主是穿红衣戴红帽的卡通人,农民是白头巾。只看人物身上一条竖带(左家 x 440–590、右家 1830–1960):
     * 再往里就是他们出的牌,红桃方块在有的牌桌上也够红。两段录屏出牌阶段 1176 帧:有红衣 50 以上、没有的 0(一帧 20)。
     */
    private val LORD_ROWS = (240..480 step 20).toList()
    private val LORD_BANDS = mapOf(DdzSeat.Left to (440..590), DdzSeat.Right to (1830..1960))
    private const val LORD_PIXELS = 40

    val ROWS = listOf(TOP_ROW, BOTTOM_ROW) + CLOCK_ROWS + AGAIN_ROWS + listOf(903, 980) + LORD_ROWS

    fun read(frame: RawFrame): JjView {
        if (frame.height != FRAME_HEIGHT) return JjView(DdzPhase.Other, emptyList(), null)
        val scale = frame.width / BASE_WIDTH
        val top = buttons(frame, TOP_ROW, scale)
        val bottom = buttons(frame, BOTTOM_ROW, scale)
        val row = top.filter { t ->
            bottom.any { b -> b.color == t.color && minOf(b.right, t.right) - maxOf(b.left, t.left) > 0.6 * minOf(b.right - b.left, t.right - t.left) }
        }
        val phase = phase(row, frame.width)
        val lord = redLord(frame, scale)
        if (phase != DdzPhase.Other) return JjView(phase, row, DdzSeat.Me, lord)
        auto(frame, scale)?.let { return JjView(DdzPhase.AutoPlay, listOf(it), null, lord) }
        again(frame, scale)?.let { return JjView(DdzPhase.Result, listOf(it), null) }
        return JjView(DdzPhase.Other, emptyList(), clock(frame, scale), lord)
    }

    /** 叫分按钮中心(画面宽度的比例):不叫、1、2、3 分。 */
    private val CALL_X = listOf(0.29, 0.51, 0.66, 0.81)

    /** 叫分按钮是几分(0 是不叫),按中心离哪个最近。 */
    fun callLevel(button: JjButton, width: Int): Int =
        CALL_X.indices.minBy { kotlin.math.abs(button.x.toDouble() / width - CALL_X[it]) }

    private fun phase(row: List<JjButton>, width: Int): DdzPhase {
        val c = row.map { it.color }
        return when {
            c.size >= 2 && c[0] == JjColor.Red && c.drop(1).all { it == JjColor.Yellow || it == JjColor.Gray } -> DdzPhase.Call
            c.size == 3 && c[0] == JjColor.Yellow && c[1] == JjColor.Yellow && c[2] != JjColor.Red -> DdzPhase.Follow
            c.size == 3 && c[0] == JjColor.Gray && c[1] == JjColor.Gray && c[2] != JjColor.Red -> DdzPhase.Lead
            c == listOf(JjColor.Yellow) && row[0].x.toDouble() / width in PASS_X0..PASS_X1 -> DdzPhase.Follow
            else -> DdzPhase.Other
        }
    }

    private fun buttons(frame: RawFrame, y: Int, scale: Double): List<JjButton> {
        val minW = (MIN_BUTTON * scale).roundToInt()
        val maxW = (MAX_BUTTON * scale).roundToInt()
        val out = mutableListOf<JjButton>()
        var cur: JjColor? = null
        var start = 0
        var miss = 0
        fun close(end: Int) {
            val c = cur ?: return
            if (end - start in minW..maxW) out += JjButton(start, end, c)
        }
        var x = 0
        while (x < frame.width) {
            val c = color(frame.pixel(x, y))
            when {
                c == cur -> miss = 0
                cur != null && miss < GAP -> miss += 2
                else -> { close(x - miss); cur = c; start = x; miss = 0 }
            }
            x += 2
        }
        close(frame.width - miss)
        return out
    }

    internal fun color(argb: Int): JjColor? {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return when {
            r > 180 && g in 141..214 && b < 120 -> JjColor.Yellow
            g > 150 && g - r > 50 && b < 160 -> JjColor.Green
            r > 190 && g in 86..139 && b < 135 && r - g > 60 -> JjColor.Red
            kotlin.math.abs(r - g) < 15 && kotlin.math.abs(g - b) < 15 && r in 131..214 -> JjColor.Gray
            else -> null
        }
    }

    /** 终局页的「再来一局」:两行在同一处都是那种绿,宽度、位置都对才算。 */
    private fun again(frame: RawFrame, scale: Double): JjButton? {
        val minW = (MIN_AGAIN * scale).roundToInt()
        val maxW = (MAX_AGAIN * scale).roundToInt()
        fun runs(y: Int): List<IntRange> = DdzScreen.runs(frame.width, gap = AGAIN_GAP, minWidth = minW) { teal(frame.pixel(it, y)) }
            .filter { it.last - it.first <= maxW }
        val bottom = runs(AGAIN_ROWS[1])
        val hit = runs(AGAIN_ROWS[0]).firstOrNull { t ->
            val cx = (t.first + t.last) / 2.0 / frame.width
            cx in AGAIN_X0..AGAIN_X1 && bottom.any { b -> minOf(b.last, t.last) - maxOf(b.first, t.first) > 0.5 * (t.last - t.first) }
        } ?: return null
        return JjButton(hit.first, hit.last, JjColor.Green)
    }

    /** 两种「再来一局」的绿:牌桌上的约 (72,180,100),终局页的偏青约 (55,180,150)。 */
    private fun teal(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return r < 100 && g > 160 && g - b > 15
    }

    /** 托管时手牌上盖着蓝色「取消托管」(约 (90,160,200),正中,y 900–990)。看白字上方、下方两行。 */
    private val AUTO_ROWS = listOf(903, 980)
    const val AUTO_TAP_Y = 945
    private const val AUTO_X0 = 0.42
    private const val AUTO_X1 = 0.58

    private fun auto(frame: RawFrame, scale: Double): JjButton? {
        val minW = (MIN_AGAIN * scale).roundToInt()
        val maxW = (MAX_AGAIN * scale).roundToInt()
        fun runs(y: Int): List<IntRange> = DdzScreen.runs(frame.width, gap = AGAIN_GAP, minWidth = minW) { blue(frame.pixel(it, y)) }
            .filter { it.last - it.first <= maxW }
        val bottom = runs(AUTO_ROWS[1])
        val hit = runs(AUTO_ROWS[0]).firstOrNull { t ->
            val cx = (t.first + t.last) / 2.0 / frame.width
            cx in AUTO_X0..AUTO_X1 && bottom.any { b -> minOf(b.last, t.last) - maxOf(b.first, t.first) > 0.5 * (t.last - t.first) }
        } ?: return null
        return JjButton(hit.first, hit.last, JjColor.Gray)
    }

    private fun blue(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return r < 115 && g in 140..180 && b > 180
    }

    private fun redLord(frame: RawFrame, scale: Double): DdzSeat? {
        val red = LORD_BANDS.filter { (_, band) ->
            val x0 = (band.first * scale).roundToInt()
            val x1 = (band.last * scale).roundToInt().coerceAtMost(frame.width - 1)
            LORD_ROWS.sumOf { y -> (x0..x1 step 2).count { costume(frame.pixel(it, y)) } } >= LORD_PIXELS
        }.keys
        return red.singleOrNull()
    }

    private fun costume(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return r > 170 && g < 70 && b < 70
    }

    private fun gold(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return r > 200 && g in 131..199 && b < 90
    }

    /** 闹钟在左家还是右家桌前;都不在(或两边都像)为 null。 */
    private fun clock(frame: RawFrame, scale: Double): DdzSeat? {
        fun at(cx: Int): Boolean {
            val x0 = ((cx - CLOCK_HALF) * scale).roundToInt().coerceAtLeast(0)
            val x1 = ((cx + CLOCK_HALF) * scale).roundToInt().coerceAtMost(frame.width - 1)
            return CLOCK_ROWS.count { y -> (x0..x1).count { gold(frame.pixel(it, y)) } >= CLOCK_PIXELS * scale } >= 2
        }
        val left = at(LEFT_CLOCK_X)
        val right = at(RIGHT_CLOCK_X)
        return when {
            left && !right -> DdzSeat.Left
            right && !left -> DdzSeat.Right
            else -> null
        }
    }
}
