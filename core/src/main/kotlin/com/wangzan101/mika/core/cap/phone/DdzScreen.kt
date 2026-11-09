package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import kotlin.math.roundToInt

/** 斗地主经典版此刻要玩家做什么。 */
internal enum class DdzPhase { Call, Double, Follow, Lead, Result, AutoPlay, Other }

internal enum class DdzColor { Blue, Yellow, Green, Gray }

internal data class DdzButton(val left: Int, val right: Int, val y: Int, val color: DdzColor) {
    val x: Int get() = (left + right) / 2
}

/** [handLeft] 手牌左缘;[pitch] 相邻两张牌的间距(不取整);[lifted] 抬起的牌的序号(从左数,0 起)。 */
internal data class DdzView(
    val phase: DdzPhase,
    val buttons: List<DdzButton>,
    val handLeft: Int,
    val pitch: Double,
    val handCount: Int,
    val lifted: Set<Int>,
    /** 一定在每张手牌牌面上的一行(没抬的牌顶下方):从这里往上找牌顶。 */
    val handRow: Int = DdzScreen.HAND_ROW,
) {
    /** 第 [index] 张牌露出来的那一条的中点;最后一张整张露出。 */
    fun cardX(index: Int): Int =
        handLeft + (index * pitch + (if (index == handCount - 1) DdzScreen.CARD_WIDTH.toDouble() else pitch) / 2).roundToInt()
}

/**
 * 从一帧像素认出牌局界面。按钮和手牌只看颜色和尺寸,不靠固定横坐标:主屏和 Mika 的屏左右留白不一样。
 * 认不出的一律 [DdzPhase.Other],调用方不会去点。
 */
internal object DdzScreen {
    /** 要不起:跟牌时只剩一个不出。 */
    fun onlyPass(view: DdzView): Boolean = view.phase == DdzPhase.Follow && view.buttons.size == 1

    /** 牌宽不随屏幕变;间距跟着画面内容的宽度走:主屏左边留黑边,内容宽 2292,间距 96;Mika 的屏铺满,间距 101。 */
    const val CARD_WIDTH = 222
    private const val BASE_PITCH = 96.0
    private const val BASE_CONTENT_WIDTH = 2292.0
    const val CARD_Y = 800
    private const val FRAME_HEIGHT = 1080
    private const val BUTTON_ROW = 470
    private const val CALL_ROW = 488
    /** 闯关牌桌的按钮更高,字把上面两行都切断了:看字的上方和下方,两行认得一样才算。 */
    private const val STAGE_TOP_ROW = 444
    private const val STAGE_BOTTOM_ROW = 518
    /** 取消托管按钮的文字上方:再往下白字会把蓝色切断。 */
    private const val AUTO_ROW = 722
    private const val AUTO_TAP_Y = 760
    private const val RESULT_ROW = 870
    private const val RESULT_TAP_Y = 895
    /** 量手牌宽度的行:两种屏上都在牌面内,也避开最后一张右上角的地主绶带。 */
    const val HAND_ROW = 676
    /** 看牌抬没抬的行:牌顶往下一点,没抬的牌这里还没有字。Mika 的屏上牌桌整体高 26 像素。 */
    private const val LIFT_ROW = 676
    private const val LIFT_ROW_FULL_WIDTH = 650
    /** 闯关的牌桌上手牌再高 32 像素:没抬的牌在这一行已经是牌面(经典牌桌上只有抬起的牌到这里)。 */
    private const val STAGE_HAND_PROBE = 628
    private const val STAGE_HAND_DY = -32
    private const val STAGE_LIFT_ROW = 608
    /** 按钮中线在扫描行下方。 */
    private const val BUTTON_TAP_DY = 30
    private const val MIN_BUTTON = 250
    /** 再来一局约 400 宽;救济金弹窗里同一行的双倍领取只有 286,那个要看广告,绝不能当成它。 */
    private const val MIN_RESULT_BUTTON = 350
    /** 认界面只看这几行。 */
    val ROWS = listOf(BUTTON_ROW, CALL_ROW, STAGE_TOP_ROW, STAGE_BOTTOM_ROW, AUTO_ROW, RESULT_ROW, HAND_ROW, LIFT_ROW_FULL_WIDTH,
        STAGE_HAND_PROBE, HAND_ROW + STAGE_HAND_DY, STAGE_LIFT_ROW)

    fun read(frame: RawFrame): DdzView {
        if (frame.height != FRAME_HEIGHT) return DdzView(DdzPhase.Other, emptyList(), 0, 0.0, 0, emptySet())
        val pitch = pitch(frame)
        val dy = if (pitch > BASE_PITCH && stageHand(frame)) STAGE_HAND_DY else 0
        val liftRow = (if (pitch > BASE_PITCH) LIFT_ROW_FULL_WIDTH else LIFT_ROW) + dy
        val (handLeft, handCount, step) = hand(frame, pitch, HAND_ROW + dy)
        // 抬起的牌点数字上移到这一行;抬起的牌还会盖住右边一张,所以不能只看这一条是不是牌面。
        // 闯关的牌没抬时牌顶在 612、点数字从 611-629 起(看点数),原来那一行会碰到没抬的 K 这类字的顶:改看 608 这一行有没有字,
        // 没抬的牌在这里还是桌布(不算字),抬起 30 像素的牌这里正是点数(王的红字从牌顶下 23 像素起)
        val lifted = (0 until handCount).filterTo(LinkedHashSet()) { i ->
            val x = handLeft + (i * step).roundToInt() + 8
            if (dy != 0) (x until x + 70).count { ink(frame.pixel(it, STAGE_LIFT_ROW)) } >= 6
            else (x until x + 70).count { !isCard(frame.pixel(it, liftRow)) } >= 6
        }
        fun view(phase: DdzPhase, buttons: List<DdzButton>) = DdzView(phase, buttons, handLeft, step, handCount, lifted, HAND_ROW + dy)
        val result = buttons(frame, RESULT_ROW).filter { it.color == DdzColor.Yellow && it.right - it.left >= MIN_RESULT_BUTTON }
        if (result.size == 1) return view(DdzPhase.Result, result.map { it.copy(y = RESULT_TAP_Y) })
        val auto = buttons(frame, AUTO_ROW).filter { it.color == DdzColor.Blue }
        if (auto.size == 1) return view(DdzPhase.AutoPlay, auto.map { it.copy(y = AUTO_TAP_Y) })
        // 没有手牌就不是牌桌:大厅里也有颜色相近的色块
        if (handCount == 0) return view(DdzPhase.Other, emptyList())
        // 先看闯关牌桌的两行(字的上下方,两行一样才算):经典牌桌的按钮矮,够不着这两行;
        // 闯关的按钮被字切断后,470 那行可能只剩一段黄色,会被当成只有出牌
        val top = buttons(frame, STAGE_TOP_ROW)
        val bottom = buttons(frame, STAGE_BOTTOM_ROW)
        val stage = phase(top)
        if (stage != null && stage == phase(bottom)) return view(stage, top)
        val row = buttons(frame, BUTTON_ROW)
        phase(row)?.takeIf { it != DdzPhase.Call }?.let { return view(it, row) }
        val call = buttons(frame, CALL_ROW)
        if (phase(call) == DdzPhase.Call) return view(DdzPhase.Call, call)
        return view(DdzPhase.Other, emptyList())
    }

    /** 一行按钮像哪种局面。 */
    private fun phase(row: List<DdzButton>): DdzPhase? {
        val blue = row.firstOrNull()?.color == DdzColor.Blue
        return when {
            row.size == 3 && blue && row[2].color == DdzColor.Green -> DdzPhase.Double
            // 闯关的加倍只有两个:蓝色不加倍、黄色加倍(带看视频的图标,绝不点)
            row.size == 2 && blue && row[1].color == DdzColor.Yellow -> DdzPhase.Double
            row.size == 3 && blue -> DdzPhase.Follow
            // 闯关里要不起时只剩一个蓝色的不出
            row.size == 1 && blue -> DdzPhase.Follow
            row.size == 1 && !blue -> DdzPhase.Lead
            row.size == 4 && blue -> DdzPhase.Call
            else -> null
        }
    }

    /**
     * 手牌那一段在 [STAGE_HAND_PROBE] 行大半是牌面或牌上的字:闯关的牌桌。抬起的牌不会有大半。
     * 字也要算:只剩三四张时点数字占了这一行的一大块(大王、K、K 只有五成五是牌面)。
     */
    private fun stageHand(frame: RawFrame): Boolean {
        val span = handSpan(frame, HAND_ROW) ?: return false
        return span.count { frame.pixel(it, STAGE_HAND_PROBE).let { p -> isCard(p) || ink(p) } } > 0.6 * (span.last - span.first + 1)
    }

    /** 牌上的字:黑字或红字;桌布的蓝不算。 */
    private fun ink(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return (maxOf(r, g, b) < 110 && maxOf(r, g, b) - minOf(r, g, b) < 40) || (r > 150 && g < 110 && b < 110)
    }

    private fun handSpan(frame: RawFrame, row: Int): IntRange? =
        runs(frame.width, gap = 70, minWidth = 150) { isCard(frame.pixel(it, row)) }.maxByOrNull { it.last - it.first }

    private fun pitch(frame: RawFrame): Int = (BASE_PITCH * (frame.width - blackBar(frame)) / BASE_CONTENT_WIDTH).roundToInt()

    /** 主屏左边留的黑边宽度;Mika 的屏没有。 */
    fun blackBar(frame: RawFrame): Int = (0 until frame.width).firstOrNull { frame.pixel(it, HAND_ROW) and 0xffffff > 0x101010 } ?: 0

    /** 手牌左缘、张数(按标称间距数),和按这一帧手牌宽度量出的实际间距。 */
    private fun hand(frame: RawFrame, pitch: Int, row: Int): Triple<Int, Int, Double> {
        val span = handSpan(frame, row) ?: return Triple(0, 0, pitch.toDouble())
        val width = span.last - span.first + 1
        val count = ((width - CARD_WIDTH).toDouble() / pitch).roundToInt() + 1
        // 间距按内容宽度算、不取整(Mika 的屏上是 101.03,取整后 20 张牌累起来差好几像素);
        // 不按量出的手牌宽度算:主屏上量的那一行贴着牌的圆角,两头各少几像素
        return Triple(span.first, count, BASE_PITCH * (frame.width - blackBar(frame)) / BASE_CONTENT_WIDTH)
    }

    private fun buttons(frame: RawFrame, y: Int): List<DdzButton> = DdzColor.entries.flatMap { color ->
        runs(frame.width, gap = 30, minWidth = MIN_BUTTON) { color(frame.pixel(it, y)) == color }
            .map { DdzButton(it.first, it.last, y + BUTTON_TAP_DY, color) }
    }.sortedBy { it.left }

    private fun color(argb: Int): DdzColor? {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return when {
            b > 225 && r < 110 -> DdzColor.Blue
            r > 225 && g in 131..214 && b < 120 -> DdzColor.Yellow
            g > 150 && r < 140 && b < 120 -> DdzColor.Green
            kotlin.math.abs(r - g) < 14 && kotlin.math.abs(g - b) < 14 && r in 131..214 -> DdzColor.Gray
            else -> null
        }
    }

    private fun isCard(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        // 下限放到按住时变暗的牌面
        return minOf(r, g, b) > 140 && maxOf(r, g, b) - minOf(r, g, b) < 40
    }

    inline fun runs(width: Int, gap: Int, minWidth: Int, hit: (Int) -> Boolean): List<IntRange> {
        val found = mutableListOf<IntRange>()
        var start = -1
        var last = -1
        for (x in 0 until width) {
            if (!hit(x)) continue
            if (start >= 0 && x - last > gap) {
                if (last - start >= minWidth) found += start..last
                start = x
            }
            if (start < 0) start = x
            last = x
        }
        if (start >= 0 && last - start >= minWidth) found += start..last
        return found
    }
}
