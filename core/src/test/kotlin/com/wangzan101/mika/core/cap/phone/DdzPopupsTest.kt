package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.cap.phone.DdzPopups.Step
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.toPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.imageio.ImageIO

/** Element lists and OCR lines are the ones read live on 2026-11-11 (relief claim with a Taobao reward video). */
class DdzPopupsTest {
    private fun el(name: String, role: String, l: Int, t: Int, r: Int, b: Int) =
        UiElement("@e", role, name, toPx((l + r) / 2, 2412), toPx((t + b) / 2, 1080), listOf(l, t, r, b), null, false, false, false)

    private fun screen(vararg els: UiElement) = ScreenSnapshot("p", PhoneDdzTurn.PACKAGE, els.toList(), 2412, 1080)
    private fun ocr(vararg lines: String) = lines.map { VisibleTextLine(it, listOf(0, 0, 999, 999)) }
    private val game = screen(el("Game view", "SurfaceView", 0, 0, 999, 999))

    private val videoBar = arrayOf(
        el("看27s/打开应用/查看详情可立即获得奖励", "TextView", 35, 56, 356, 111),
        el("", "TextView", 884, 56, 909, 111),
        el("", "ImageView", 930, 56, 955, 111),
        el("立刻打开", "TextView", 614, 824, 709, 903),
    )

    private fun frame(name: String): RawFrame {
        val image = ImageIO.read((javaClass.getResourceAsStream("/ddz/$name.jpg") ?: javaClass.getResourceAsStream("/ddz/$name.png"))!!)
        return RawFrame(image.width, image.height, DdzPopups.SCAN_ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private fun tapped(step: Step?) = (step as Step.Tap).what

    @Test fun waitsOutTheRewardVideo() {
        assertEquals(Step.Wait, DdzPopups.next(screen(*videoBar), null, null))
    }

    @Test fun theBoostOfferIsClosedWithTheCornerCrossNeverItsButton() {
        // The countdown freezes while this layer is up; its big button opens Taobao
        val step = DdzPopups.next(screen(
            el("获得加速机会！", "TextView", 421, 163, 578, 221),
            el("我要直接拿奖励", "TextView", 429, 665, 571, 727),
            *videoBar,
        ), null, null) as Step.Tap
        assertEquals("广告 ✕", step.what)
        assertEquals(toPx(942, 2412), step.x)
    }

    @Test fun theGiveUpConfirmationKeepsWatching() {
        val step = DdzPopups.next(screen(
            el("前往“淘宝”浏览15秒可立即领取奖励。确认放弃直接拿奖励吗？", "TextView", 326, 483, 674, 711),
            el("放弃", "TextView", 296, 775, 499, 842),
            el("直接拿奖励", "TextView", 501, 775, 704, 842),
        ), null, null) as Step.Tap
        assertEquals("放弃", step.what)
        assertEquals(toPx(397, 2412), step.x)
    }

    @Test fun theEndCardIsClosedNotOpened() {
        val step = DdzPopups.next(screen(el("", "ImageView", 960, 50, 985, 100), el("立刻打开", "TextView", 614, 824, 709, 903)), null, null) as Step.Tap
        assertEquals("广告 ✕", step.what)
        assertEquals(toPx(972, 2412), step.x)
    }

    @Test fun aGrantedRewardVideoIsClosed() {
        // Live 2026-11-11 (Kuaishou): after the countdown the bar says the reward is granted while the video plays on
        val step = DdzPopups.next(screen(
            el("恭喜已获得奖励", "TextView", 35, 56, 200, 111),
            el("", "TextView", 884, 56, 909, 111),
            el("", "ImageView", 930, 56, 955, 111),
            el("立即下载", "TextView", 30, 650, 330, 760),
        ), null, null) as Step.Tap
        assertEquals("广告 ✕", step.what)
        assertEquals(toPx(942, 2412), step.x)
    }

    @Test fun anyAdEndCardIsClosedNotDownloaded() {
        // Live 2026-11-11: Kuaishou's end card says 立即下载 (Taobao's says 立刻打开); its cross is an empty TextView
        val step = DdzPopups.next(screen(
            el("快手极速版 刷视频即可领取现金红包随时提现 立即下载", "RelativeLayout", 0, 0, 999, 999),
            el("", "TextView", 948, 31, 986, 114),
            el("快手极速版", "TextView", 453, 409, 546, 466),
            el("立即下载", "TextView", 415, 668, 584, 762),
            el("摇一摇立即下载", "TextView", 443, 818, 600, 886),
        ), null, null) as Step.Tap
        assertEquals("广告 ✕", step.what)
        assertEquals(toPx(967, 2412), step.x)
    }

    @Test fun anotherNativeDialogIsLeftToTheModel() {
        assertNull(DdzPopups.next(screen(el("登录游戏中心", "TextView", 400, 300, 600, 350), el("退出游戏", "TextView", 300, 600, 450, 650)), null, null))
    }

    @Test fun theRewardScreenIsTappedOnABlankSpot() {
        // OCR reads 「继续」 as 「多展」; the start of the line is stable
        assertEquals("空白处", tapped(DdzPopups.next(game, ocr("X2000", "金币", "点击任意空白区域多展"), null)))
    }

    @Test fun reliefTakesTheFreeClaimWhenThereIsOne() {
        assertEquals("直接领取", tapped(DdzPopups.next(game, ocr("|系统送钱啦!", "1000币"), frame("popup_relief_two_buttons"))))
    }

    @Test fun reliefWithOnlyTheVideoClaimWatchesIt() {
        assertEquals("双倍领取", tapped(DdzPopups.next(game, ocr("|系统送钱啦!。", "1000币"), frame("popup_relief_video_only"))))
    }

    @Test fun theLobbyIsNotAPopup() {
        assertNull(DdzPopups.next(game, ocr("经典模式", "新手场", "快速开始", "|新手场"), frame("popup_classic_lobby")))
    }

    /** 2026-11-13:Mika 屏上的「段位降至白银 1 星」只有一个橙色「确定」(结算页和场次页各一张)。 */
    @Test fun aRankDropPopupIsConfirmedAtOneButton() {
        for (name in listOf("rank_down_on_result", "rank_down_on_rooms", "rank_protect_on_result")) {
            assertEquals(name, 1205 to 852, DdzCards.rankPopup(frame(name)))
        }
    }

    /** 段位提升时左边多了绿色「炫耀一下」,橙色「确定」移到右边(1458):只点确定,绝不点分享。 */
    @Test fun aRankUpPopupConfirmsTheRightButtonNeverShares() {
        for (name in listOf("rank_up_on_result", "rank_up_on_rooms")) {
            assertEquals(name, 1458 to 852, DdzCards.rankPopup(frame(name)))
        }
    }

    /** 结算页、真人场次页、救济金、大厅本身都不是段位弹窗。 */
    @Test fun plainScreensAreNeverRankPopups() {
        for (name in listOf(
            "human_result_leave_change_continue", "human_classic_rooms", "popup_relief_two_buttons",
            "popup_relief_video_only", "popup_classic_lobby",
        )) {
            assertNull(name, DdzCards.rankPopup(frame(name)))
        }
    }
}
