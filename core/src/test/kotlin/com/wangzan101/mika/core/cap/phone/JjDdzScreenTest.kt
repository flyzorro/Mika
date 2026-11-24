package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/** 素材是 2026-11-20、11-21 新手场的录屏(2408×1080)和一张 screencap(2412×1080)。 */
class JjDdzScreenTest {
    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz-jj/$name.jpg")!!)
        return RawFrame(image.width, image.height, JjDdzScreen.ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private fun colors(view: JjView) = view.buttons.map { it.color }

    @Test fun followHasPassHintAndPlay() {
        val view = JjDdzScreen.read(frame("follow"))
        assertEquals(DdzPhase.Follow, view.phase)
        assertEquals(listOf(JjColor.Yellow, JjColor.Yellow, JjColor.Green), colors(view))
        assertEquals(DdzSeat.Me, view.turn)
        assertFalse(view.onlyPass)
    }

    @Test fun leadHasGrayPassAndHint() {
        val view = JjDdzScreen.read(frame("lead"))
        assertEquals(DdzPhase.Lead, view.phase)
        assertEquals(listOf(JjColor.Gray, JjColor.Gray, JjColor.Green), colors(view))
    }

    @Test fun cannotBeatLeavesOneButtonInTheMiddle() {
        val view = JjDdzScreen.read(frame("only_pass"))
        assertEquals(DdzPhase.Follow, view.phase)
        assertTrue(view.onlyPass)
    }

    @Test fun callOnTheBeachTable() {
        val view = JjDdzScreen.read(frame("call_beach"))
        assertEquals(DdzPhase.Call, view.phase)
        assertEquals(listOf(JjColor.Red, JjColor.Yellow, JjColor.Yellow, JjColor.Yellow), colors(view))
    }

    @Test fun callOnTheSeaTableAtFullScreenWidth() {
        // screencap 是 2412 宽:别人叫过 2 分,1、2 变灰
        val view = JjDdzScreen.read(frame("call_sea_2412"))
        assertEquals(DdzPhase.Call, view.phase)
        assertEquals(listOf(JjColor.Red, JjColor.Gray, JjColor.Gray, JjColor.Yellow), colors(view))
    }

    @Test fun theFinalPageOffersPlayAgain() {
        val view = JjDdzScreen.read(frame("final_page"))
        assertEquals(DdzPhase.Result, view.phase)
        // 「再来一局」在 x≈1278–1638
        assertTrue("${view.buttons}", view.buttons.single().x in 1400..1520)
    }

    @Test fun playAgainOnTheTableAfterEachHand() {
        // 自由桌每手打完,牌桌上弹「再来一局」(正中,深绿)
        val view = JjDdzScreen.read(frame("table_again"))
        assertEquals(DdzPhase.Result, view.phase)
        assertTrue("${view.buttons}", view.buttons.single().x in 1100..1300)
    }

    @Test fun autoPlayShowsCancel() {
        // 超时后游戏替我托管:手牌上盖着蓝色「取消托管」
        val view = JjDdzScreen.read(frame("auto_play"))
        assertEquals(DdzPhase.AutoPlay, view.phase)
        assertTrue("${view.buttons}", view.buttons.single().x in 1100..1300)
    }

    @Test fun callButtonsAreNamedByPosition() {
        // 按位置认几分:漏认一个按钮时按序号会错位(2026-11-21 想叫 3 分点成了不叫)
        for (name in listOf("call_beach", "call_sea_2412")) {
            val f = frame(name)
            assertEquals(name, listOf(0, 1, 2, 3), JjDdzScreen.read(f).buttons.map { JjDdzScreen.callLevel(it, f.width) })
        }
    }

    @Test fun theLandlordWearsRed() {
        // 出牌时地主是红衣红帽的卡通人:海景 f0060 右家、f0300 左家,沙滩托管那手左家
        assertEquals(DdzSeat.Right, JjDdzScreen.read(frame("left_turn")).redLord)
        assertEquals(DdzSeat.Left, JjDdzScreen.read(frame("right_turn")).redLord)
        assertEquals(DdzSeat.Left, JjDdzScreen.read(frame("auto_play")).redLord)
    }

    @Test fun clockTellsWhoseTurnItIs() {
        assertEquals(DdzSeat.Left, JjDdzScreen.read(frame("left_turn")).turn)
        assertEquals(DdzSeat.Right, JjDdzScreen.read(frame("right_turn")).turn)
    }

    @Test fun nothingToTapOutsideMyTurn() {
        // 沙滩上黄的沙子和人物在按钮那一行连成一段;刚出的牌飞在按钮上
        for (name in listOf("beach_scenery", "my_cards_flying", "left_turn", "right_turn")) {
            val view = JjDdzScreen.read(frame(name))
            assertEquals(name, DdzPhase.Other, view.phase)
            assertTrue(name, view.buttons.isEmpty())
        }
    }
}
