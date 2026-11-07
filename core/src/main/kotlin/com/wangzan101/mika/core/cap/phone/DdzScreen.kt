package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import kotlin.math.roundToInt

/** 斗地主经典版此刻要玩家做什么。 */
internal enum class DdzPhase { Call, Double, Follow, Lead, Result, AutoPlay, Other }

internal enum class DdzColor { Blue, Yellow, Green, Gray }

internal data class DdzButton(val left: Int, val right: Int, val y: Int, val color: DdzColor) {
    val x: Int get() = (left + right) / 2
}

/** [handLeft] 手牌左缘;[pitch] 相邻两张牌的间距;[lifted] 抬起的牌的序号(从左数,0 起)。 */
internal data class DdzView(
    val phase: DdzPhase,
    val buttons: List<DdzButton>,
    val handLeft: Int,
    val pitch: Int,
    val handCount: Int,
    val lifted: Set<Int>,
) {
    /** 第 [index] 张牌露出来的那一条的中点;最后一张整张露出。 */
    fun cardX(index: Int): Int =
        handLeft + index * pitch + (if (index == handCount - 1) DdzScreen.CARD_WIDTH else pitch) / 2
}

/**
 * 从一帧像素认出牌局界面。按钮和手牌只看颜色和尺寸,不靠固定横坐标:主屏和 Mika 的屏左右留白不一样。
 * 认不出的一律 [DdzPhase.Other],调用方不会去点。
 */
internal object DdzScreen {
    /** 牌宽不随屏幕变;间距跟着画面内容的宽度走:主屏左边留黑边,内容宽 2292,间距 96;Mika 的屏铺满,间距 101。 */
    const val CARD_WIDTH = 222
    private const val BASE_PITCH = 96.0
    private const val BASE_CONTENT_WIDTH = 2292.0
    const val CARD_Y = 800
    private const val FRAME_HEIGHT = 1080
    private const val BUTTON_ROW = 470
    private const val CALL_ROW = 488
    /** 取消托管按钮的文字上方:再往下白字会把蓝色切断。 */
    private const val AUTO_ROW = 722
    private const val AUTO_TAP_Y = 760
    private const val RESULT_ROW = 870
    private const val RESULT_TAP_Y = 895
    /** 量手牌宽度的行:两种屏上都在牌面内,也避开最后一张右上角的地主绶带。 */
    private const val HAND_ROW = 676
    /** 看牌抬没抬的行:牌顶往下一点,没抬的牌这里还没有字。Mika 的屏上牌桌整体高 26 像素。 */
    private const val LIFT_ROW = 676
    private const val LIFT_ROW_FULL_WIDTH = 650
    /** 按钮中线在扫描行下方。 */
    private const val BUTTON_TAP_DY = 30
    private const val MIN_BUTTON = 250
    /** 再来一局约 400 宽;救济金弹窗里同一行的双倍领取只有 286,那个要看广告,绝不能当成它。 */
    private const val MIN_RESULT_BUTTON = 350
    /** 认界面只看这几行。 */
    val ROWS = listOf(BUTTON_ROW, CALL_ROW, AUTO_ROW, RESULT_ROW, HAND_ROW, LIFT_ROW_FULL_WIDTH)

    fun read(frame: RawFrame): DdzView {
        if (frame.height != FRAME_HEIGHT) return DdzView(DdzPhase.Other, emptyList(), 0, 0, 0, emptySet())
        val pitch = pitch(frame)
        val liftRow = if (pitch > BASE_PITCH) LIFT_ROW_FULL_WIDTH else LIFT_ROW
        val (handLeft, handCount) = hand(frame, pitch)
        // 抬起的牌点数字上移到这一行;抬起的牌还会盖住右边一张,所以不能只看这一条是不是牌面
        val lifted = (0 until handCount).filterTo(LinkedHashSet()) { i ->
            val x = handLeft + i * pitch + 8
            (x until x + 70).count { !isCard(frame.pixel(it, liftRow)) } >= 6
        }
        fun view(phase: DdzPhase, buttons: List<DdzButton>) = DdzView(phase, buttons, handLeft, pitch, handCount, lifted)
        val result = buttons(frame, RESULT_ROW).filter { it.color == DdzColor.Yellow && it.right - it.left >= MIN_RESULT_BUTTON }
        if (result.size == 1) return view(DdzPhase.Result, result.map { it.copy(y = RESULT_TAP_Y) })
        val auto = buttons(frame, AUTO_ROW).filter { it.color == DdzColor.Blue }
        if (auto.size == 1) return view(DdzPhase.AutoPlay, auto.map { it.copy(y = AUTO_TAP_Y) })
        // 没有手牌就不是牌桌:大厅里也有颜色相近的色块
        if (handCount == 0) return view(DdzPhase.Other, emptyList())
        val row = buttons(frame, BUTTON_ROW)
        when {
            row.size == 3 && row[0].color == DdzColor.Blue && row[2].color == DdzColor.Green -> return view(DdzPhase.Double, row)
            row.size == 3 && row[0].color == DdzColor.Blue -> return view(DdzPhase.Follow, row)
            row.size == 1 && row[0].color != DdzColor.Blue -> return view(DdzPhase.Lead, row)
        }
        val call = buttons(frame, CALL_ROW)
        if (call.size == 4 && call[0].color == DdzColor.Blue) return view(DdzPhase.Call, call)
        return view(DdzPhase.Other, emptyList())
    }

    private fun pitch(frame: RawFrame): Int = (BASE_PITCH * (frame.width - blackBar(frame)) / BASE_CONTENT_WIDTH).roundToInt()

    /** 主屏左边留的黑边宽度;Mika 的屏没有。 */
    fun blackBar(frame: RawFrame): Int = (0 until frame.width).firstOrNull { frame.pixel(it, HAND_ROW) and 0xffffff > 0x101010 } ?: 0

    private fun hand(frame: RawFrame, pitch: Int): Pair<Int, Int> {
        val span = runs(frame.width, gap = 70, minWidth = 150) { isCard(frame.pixel(it, HAND_ROW)) }
            .maxByOrNull { it.last - it.first } ?: return 0 to 0
        val width = span.last - span.first + 1
        return span.first to ((width - CARD_WIDTH).toDouble() / pitch).roundToInt() + 1
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
