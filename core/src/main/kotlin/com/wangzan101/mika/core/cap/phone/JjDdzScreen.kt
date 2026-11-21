package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import kotlin.math.roundToInt

internal enum class JjColor { Yellow, Green, Gray, Red }

internal data class JjButton(val left: Int, val right: Int, val color: JjColor) {
    val x: Int get() = (left + right) / 2
}

/** JJ 斗地主此刻的局面:[phase] 只有 Call、Lead、Follow、Other;[turn] 是闹钟在谁桌前(轮到我看按钮)。 */
internal data class JjView(val phase: DdzPhase, val buttons: List<JjButton>, val turn: DdzSeat?) {
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

    val ROWS = listOf(TOP_ROW, BOTTOM_ROW) + CLOCK_ROWS

    fun read(frame: RawFrame): JjView {
        if (frame.height != FRAME_HEIGHT) return JjView(DdzPhase.Other, emptyList(), null)
        val scale = frame.width / BASE_WIDTH
        val top = buttons(frame, TOP_ROW, scale)
        val bottom = buttons(frame, BOTTOM_ROW, scale)
        val row = top.filter { t ->
            bottom.any { b -> b.color == t.color && minOf(b.right, t.right) - maxOf(b.left, t.left) > 0.6 * minOf(b.right - b.left, t.right - t.left) }
        }
        val phase = phase(row, frame.width)
        if (phase != DdzPhase.Other) return JjView(phase, row, DdzSeat.Me)
        return JjView(DdzPhase.Other, emptyList(), clock(frame, scale))
    }

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
