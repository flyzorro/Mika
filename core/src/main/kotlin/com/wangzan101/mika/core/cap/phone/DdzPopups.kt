package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.toPx

/**
 * 斗地主经典版里工具自己收拾的弹窗:救济金(能直接领就直接领,只剩看视频的就看)、激励视频广告、领取结果。
 * 广告是原生界面,按无障碍树认;救济金和领取结果画在游戏里,按 OCR 认得稳的几个字认,按钮按固定位置点。
 * 广告里「立刻打开」「我要直接拿奖励」「直接拿奖励」会跳去别的 App,永远不点。认不出为 null,交还模型。
 */
internal object DdzPopups {
    sealed interface Step {
        /** [x]、[y] 为像素。 */
        data class Tap(val x: Int, val y: Int, val what: String) : Step
        /** 视频还在倒计时:等着,什么都不点。 */
        data object Wait : Step
    }

    /** 「确定」:段位弹窗唯一要点的按钮(绿色「炫耀一下」绝不点)。 */
    const val RANK_CONFIRM = "确定"

    /** 判断救济金是两个按钮还是只有看视频那一个要读的行(左边按钮的高度)。 */
    val ROWS: List<Int> = (BUTTON_Y0 until BUTTON_Y1 step 3).toList()

    /** 认弹窗要截的行:救济金按钮那一块,加上段位弹窗的标题和徽章。 */
    val SCAN_ROWS: List<Int> = (ROWS + DdzCards.RANK_ROWS).distinct()

    fun next(snapshot: ScreenSnapshot, texts: List<VisibleTextLine>?, frame: RawFrame?): Step? {
        val els = snapshot.elements
        fun el(text: String) = els.firstOrNull { it.name.trim() == text }
        fun has(text: String) = els.any { text in it.name } || texts.orEmpty().any { text in it.text }
        fun at(nx: Int, ny: Int, what: String) = Step.Tap(toPx(nx, snapshot.width), toPx(ny, snapshot.height), what)
        // 段位弹窗(结算页、真人场次页):只点橙色「确定」,绝不点绿色「炫耀一下」
        if (frame != null) DdzCards.rankPopup(frame)?.let { (x, y) -> return Step.Tap(x, y, RANK_CONFIRM) }
        // 关掉「直接拿奖励」的确认框:选放弃,回去接着看视频
        if (has("确认放弃直接拿奖励")) return el("放弃")?.let { Step.Tap(it.centerX, it.centerY, "放弃") }
        // 「获得加速机会」浮层盖着时倒计时不走:点右上角 ✕,会先问一句是否放弃
        if (has("获得加速机会")) return close(els, snapshot) ?: at(VIDEO_CLOSE_X, CLOSE_Y, "广告 ✕")
        if (els.any { COUNTDOWN.containsMatchIn(it.name) }) return Step.Wait
        // 倒计时完了:视频还在放(「恭喜已获得奖励」)或换成了结束页(淘宝「立刻打开」、快手「立即下载」),都点右上角 ✕
        if (els.any { el -> AD_MARKS.any { it in el.name } }) {
            return close(els, snapshot) ?: if (has("已获得奖励")) at(VIDEO_CLOSE_X, CLOSE_Y, "广告 ✕") else at(END_CLOSE_X, CLOSE_Y, "广告 ✕")
        }
        val ocr = texts.orEmpty()
        if (ocr.any { "点击任意空白" in it.text }) return at(BLANK_X, BLANK_Y, "空白处")
        if (ocr.any { "送钱" in it.text }) {
            return if (frame != null && warm(frame, DIRECT_X0, DIRECT_X1) >= TWO_BUTTONS) at(DIRECT_X, BUTTON_Y, "直接领取")
            else at(DOUBLE_X, DOUBLE_Y, "双倍领取")
        }
        return null
    }

    /** 右上角最靠右的那个小控件(视频页是喇叭和 ✕ 并排,✕ 在右)。 */
    private fun close(els: List<UiElement>, snapshot: ScreenSnapshot): Step.Tap? =
        els.filter { it.bounds.size == 4 && it.bounds[0] >= 860 && it.bounds[3] <= 160 && it.bounds[2] - it.bounds[0] <= 80 && it.name.isBlank() }
            .maxByOrNull { it.bounds[0] }
            ?.let { Step.Tap(it.centerX, it.centerY, "广告 ✕") }

    /** 左边按钮那一块里暖色(奶黄、橙)像素的占比:两个按钮时左边是「直接领取」。 */
    private fun warm(frame: RawFrame, nx0: Int, nx1: Int): Double {
        var hit = 0
        var all = 0
        val x0 = toPx(nx0, frame.width)
        val x1 = toPx(nx1, frame.width)
        for (y in ROWS) for (x in x0 until x1 step 3) {
            val p = frame.pixel(x, y)
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff
            all++
            if (r > 200 && g > 110 && r - b > 40) hit++
        }
        return if (all == 0) 0.0 else hit.toDouble() / all
    }

    private val COUNTDOWN = Regex("看\\d+s")
    /** 激励视频和它结束页上才有的字;别的原生弹框(如登录游戏中心)不碰。 */
    private val AD_MARKS = listOf("奖励", "广告", "立刻打开", "下载", "隐私")
    // 坐标按 0–999;救济金按钮的行按 1080 高的屏取像素
    private const val VIDEO_CLOSE_X = 942
    private const val END_CLOSE_X = 971
    private const val CLOSE_Y = 80
    private const val BLANK_X = 400
    private const val BLANK_Y = 300
    private const val DIRECT_X0 = 320
    private const val DIRECT_X1 = 460
    private const val DIRECT_X = 390
    private const val BUTTON_Y = 780
    private const val DOUBLE_X = 508
    private const val DOUBLE_Y = 787
    private const val BUTTON_Y0 = 810
    private const val BUTTON_Y1 = 875
    private const val TWO_BUTTONS = 0.6
}
